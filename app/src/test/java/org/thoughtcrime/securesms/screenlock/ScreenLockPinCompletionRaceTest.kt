package org.thoughtcrime.securesms.screenlock

import android.app.Application
import android.os.Looper
import android.view.View
import com.google.android.material.textfield.TextInputEditText
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.ScreenLockController
import org.thoughtcrime.securesms.biometric.BiometricDialogFragment
import org.thoughtcrime.securesms.service.KeyCachingService
import org.thoughtcrime.securesms.testing.CoroutineDispatcherRule
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class ScreenLockPinCompletionRaceTest {
  @get:Rule val dispatchers = CoroutineDispatcherRule(UnconfinedTestDispatcher())
  private lateinit var application: Application
  private val controllers = mutableListOf<ActivityController<ScreenLockPinDialogFragmentTest.HostActivity>>()

  @Before fun setUp() {
    clearPreferenceCache()
    application = RuntimeEnvironment.getApplication()
    File(application.noBackupFilesDir, "screen-lock-mode").writeText("OFF")
    KeyCachingService.clearMasterSecret()
    ScreenLockPreferences.write(application, ScreenLockPreferences.Record(ScreenLockMode.PIN, 0))
    ScreenLockController.configure(application, false)
    mockkObject(ScreenLockPinStore)
    every { ScreenLockPinStore.verify(any(), any(), any()) } returns ScreenLockPinStore.Result.Success
  }

  @After fun tearDown() {
    controllers.forEach { it.pause().stop().destroy() }
    shadowOf(Looper.getMainLooper()).idle()
    unmockkObject(ScreenLockPinStore)
    ScreenLockPreferences.write(application, ScreenLockPreferences.Record(ScreenLockMode.OFF))
    ScreenLockController.configure(application, false)
    clearPreferenceCache()
  }

  private fun clearPreferenceCache() {
    for (name in listOf("cached", "file")) {
      ScreenLockPreferences::class.java.getDeclaredField(name).apply { isAccessible = true }.set(null, null)
    }
  }

  private fun host(): ActivityController<ScreenLockPinDialogFragmentTest.HostActivity> =
    Robolectric.buildActivity(ScreenLockPinDialogFragmentTest.HostActivity::class.java).setup().also { controllers.add(it) }

  private fun prompt(activity: ScreenLockPinDialogFragmentTest.HostActivity) =
    activity.supportFragmentManager.fragments.filterIsInstance<ScreenLockPinDialogFragment>().single()

  private fun submit(fragment: ScreenLockPinDialogFragment) {
    fragment.requireView().findViewById<TextInputEditText>(R.id.screen_lock_pin_input).setText("1234")
    fragment.requireView().findViewById<View>(R.id.screen_lock_pin_submit).performClick()
  }

  @Test fun successfulUnlockCommitsBeforeDismissingAndBeforePostedFocusCallbacks() {
    val activity = host().get()
    val generation = ScreenLockController.authenticationGeneration()
    var successes = 0
    var dialogVisibleAtSuccess = false
    lateinit var fragment: ScreenLockPinDialogFragment
    ScreenLockAuthentication.authenticateForUnlock(activity, object : BiometricDialogFragment.Listener {
      override fun onSuccess(): Boolean {
        successes++
        dialogVisibleAtSuccess = fragment.requireDialog().isShowing
        return ScreenLockController.completeAuthentication(generation)
      }
      override fun onError(message: CharSequence) = true
    }, true)
    fragment = prompt(activity)
    submit(fragment)

    // Do not drain the main queue: authentication must already be committed here.
    assertEquals(1, successes)
    assertTrue(dialogVisibleAtSuccess)
    assertTrue(ScreenLockController.isAccessAllowed())
    shadowOf(Looper.getMainLooper()).idle()
    assertTrue(activity.supportFragmentManager.fragments.filterIsInstance<ScreenLockPinDialogFragment>().isEmpty())
    assertEquals(1, successes)
  }

  @Test fun settingsConfirmationStillWaitsUntilTheOldDialogIsRemoved() {
    ScreenLockController.configure(application, true)
    val activity = host().get()
    var successes = 0
    ScreenLockAuthentication.authenticate(activity, object : BiometricDialogFragment.Listener {
      override fun onSuccess(): Boolean {
        assertTrue(activity.supportFragmentManager.fragments.filterIsInstance<ScreenLockPinDialogFragment>().isEmpty())
        successes++
        return true
      }
      override fun onError(message: CharSequence) = true
    }, true)
    submit(prompt(activity))
    assertEquals(0, successes)
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals(1, successes)
  }

  @Test fun stalePromptIsCancelledOnResumeInsteadOfChangingOnlyItsGeneration() {
    val controller = host()
    val activity = controller.get()
    var cancellations = 0
    ScreenLockAuthentication.authenticateForUnlock(activity, object : BiometricDialogFragment.Listener {
      override fun onSuccess(): Boolean = error("A stale request cannot authenticate")
      override fun onCancel(fromUser: Boolean): Boolean {
        assertFalse(fromUser)
        cancellations++
        return true
      }
      override fun onError(message: CharSequence) = true
    }, true)
    controller.pause()
    ScreenLockController.onActivitiesHidden(application)
    ScreenLockController.onActivityResuming()
    controller.resume().postResume()
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(1, cancellations)
    assertFalse(ScreenLockController.isAccessAllowed())
    assertTrue(activity.supportFragmentManager.fragments.filterIsInstance<ScreenLockPinDialogFragment>().isEmpty())
  }

  @Test fun aQueuedReplacementDoesNotReopenPinAfterAnotherActivityUnlocks() {
    val activity = host().get()
    val listener = object : BiometricDialogFragment.Listener {
      override fun onSuccess() = true
      override fun onError(message: CharSequence) = true
    }
    ScreenLockAuthentication.authenticateForUnlock(activity, listener, true)
    ScreenLockController.configure(application, false)
    ScreenLockAuthentication.authenticateForUnlock(activity, listener, true)
    assertTrue(ScreenLockController.completeAuthentication(ScreenLockController.authenticationGeneration()))
    shadowOf(Looper.getMainLooper()).idle()

    assertTrue(ScreenLockController.isAccessAllowed())
    assertTrue(activity.supportFragmentManager.fragments.filterIsInstance<ScreenLockPinDialogFragment>().isEmpty())
  }
}
