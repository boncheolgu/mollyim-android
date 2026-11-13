package org.thoughtcrime.securesms.screenlock

import android.app.Application
import android.os.Looper
import android.view.View
import com.google.android.material.textfield.TextInputEditText
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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
import org.signal.core.util.crypto.KeyStoreHelper
import org.signal.core.util.concurrent.SignalDispatchers
import java.io.File
import java.security.UnrecoverableEntryException
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class ScreenLockPinCompletionRaceTest {
  @get:Rule val dispatchers = CoroutineDispatcherRule(UnconfinedTestDispatcher())
  private lateinit var application: Application
  private val controllers = mutableListOf<ActivityController<ScreenLockPinDialogFragmentTest.HostActivity>>()
  private val keys = mutableMapOf<String, SecretKey>()

  @Before fun setUp() {
    clearPreferenceCache()
    application = RuntimeEnvironment.getApplication()
    File(application.noBackupFilesDir, "screen-lock-mode").writeText("OFF")
    KeyCachingService.clearMasterSecret()
    // Use the real PIN store and verifier; replace only Android's hardware key store.
    // Mocking the Kotlin object itself failed in CI before any assertion ran.
    mockkStatic(KeyStoreHelper::class)
    every { KeyStoreHelper.createKeyStoreEntryHmac(any(), false) } answers {
      SecretKeySpec(ByteArray(32) { 7 }, "HmacSHA256").also { keys[firstArg()] = it }
    }
    every { KeyStoreHelper.getKeyStoreEntryHmac(any()) } answers { keys[firstArg()] ?: throw UnrecoverableEntryException() }
    every { KeyStoreHelper.deleteKeyStoreEntry(any()) } answers { keys.remove(firstArg<String>()); Unit }
    ScreenLockPreferences.write(application, ScreenLockPreferences.Record(ScreenLockMode.OFF, 0))
    ScreenLockController.configure(application, true)
    assertTrue(ScreenLockPinStore.install(application, "1234".toCharArray(), ScreenLockController.authenticationGeneration()) { true })
    ScreenLockController.configure(application, false)
  }

  @After fun tearDown() {
    controllers.forEach { it.pause().stop().destroy() }
    shadowOf(Looper.getMainLooper()).idle()
    unmockkStatic(KeyStoreHelper::class)
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
    var cancellations = 0
    var dialogVisibleAtSuccess = false
    lateinit var fragment: ScreenLockPinDialogFragment
    ScreenLockAuthentication.authenticateForUnlock(activity, object : BiometricDialogFragment.Listener {
      override fun onSuccess(): Boolean {
        successes++
        dialogVisibleAtSuccess = fragment.requireDialog().isShowing
        return ScreenLockController.completeAuthentication(generation)
      }
      override fun onError(message: CharSequence) = true
      override fun onCancel(fromUser: Boolean): Boolean { cancellations++; return true }
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
    assertEquals(0, cancellations)
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

  @Test fun pausedPromptIsCancelledOnceAcrossResumeInsteadOfRetargetingItsGeneration() {
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

  @Test fun freshUnlockRequestAfterSessionChangeAcceptsOneCorrectPin() {
    val activity = host().get()
    ScreenLockAuthentication.authenticateForUnlock(activity, object : BiometricDialogFragment.Listener {
      override fun onSuccess(): Boolean = error("The superseded request must not authenticate")
      override fun onError(message: CharSequence) = true
    }, true)

    // Invalidate the session while retaining this resumed PIN fragment, then issue
    // a fresh app-unlock request. This deliberately forces a request mismatch;
    // it does not demonstrate a real task-removal lifecycle on a device.
    // The old code replaces only the listener, retaining the old fragment token.
    ScreenLockController.onTaskRemoved(application)
    ScreenLockController.onActivityResuming()
    val currentGeneration = ScreenLockController.authenticationGeneration()
    var successes = 0
    ScreenLockAuthentication.authenticateForUnlock(activity, object : BiometricDialogFragment.Listener {
      override fun onSuccess(): Boolean {
        successes++
        return ScreenLockController.completeAuthentication(currentGeneration)
      }
      override fun onError(message: CharSequence) = true
    }, true)
    shadowOf(Looper.getMainLooper()).idle()
    submit(prompt(activity))
    shadowOf(Looper.getMainLooper()).idle()

    val remaining = activity.supportFragmentManager.fragments.filterIsInstance<ScreenLockPinDialogFragment>()
    assertEquals(
      "One correct PIN must unlock the current request (accessAllowed=${ScreenLockController.isAccessAllowed()}, PIN windows=${remaining.size})",
      1,
      successes
    )
    assertTrue(ScreenLockController.isAccessAllowed())
    assertTrue(activity.supportFragmentManager.fragments.filterIsInstance<ScreenLockPinDialogFragment>().isEmpty())
  }

  @Test fun ordinaryBackgroundResumeWithFreshRequestAcceptsOneCorrectPin() {
    val controller = host()
    val activity = controller.get()
    ScreenLockAuthentication.authenticateForUnlock(activity, object : BiometricDialogFragment.Listener {
      override fun onSuccess(): Boolean = error("The old request must not authenticate")
      override fun onError(message: CharSequence) = true
    }, true)

    controller.pause().stop()
    // This bare host has no application observer, so deliver its hidden/resume hooks.
    ScreenLockController.onActivitiesHidden(application)
    ScreenLockController.onActivityResuming()
    controller.start().resume().postResume()
    val generation = ScreenLockController.authenticationGeneration()
    var successes = 0
    ScreenLockAuthentication.authenticateForUnlock(activity, object : BiometricDialogFragment.Listener {
      override fun onSuccess(): Boolean {
        successes++
        return ScreenLockController.completeAuthentication(generation)
      }
      override fun onError(message: CharSequence) = true
    }, true)
    shadowOf(Looper.getMainLooper()).idle()
    submit(prompt(activity))
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals("Ordinary background/resume must accept one correct PIN", 1, successes)
    assertTrue(ScreenLockController.isAccessAllowed())
    assertTrue(activity.supportFragmentManager.fragments.filterIsInstance<ScreenLockPinDialogFragment>().isEmpty())
  }

  @Test fun queuedReplacementCannotAdoptANewerGenerationWithItsOldListener() {
    val activity = host().get()
    val original = object : BiometricDialogFragment.Listener {
      override fun onSuccess(): Boolean = error("The original request must not authenticate")
      override fun onError(message: CharSequence) = true
    }
    ScreenLockAuthentication.authenticateForUnlock(activity, original, true)
    ScreenLockController.configure(application, false)
    var cancellations = 0
    ScreenLockAuthentication.authenticateForUnlock(activity, object : BiometricDialogFragment.Listener {
      override fun onSuccess(): Boolean = error("The queued stale request must not authenticate")
      override fun onCancel(fromUser: Boolean): Boolean {
        assertFalse(fromUser)
        cancellations++
        return true
      }
      override fun onError(message: CharSequence) = true
    }, true)
    ScreenLockController.configure(application, false)
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(1, cancellations)
    assertFalse(ScreenLockController.isAccessAllowed())
    assertTrue(activity.supportFragmentManager.fragments.filterIsInstance<ScreenLockPinDialogFragment>().isEmpty())
  }

  @Test fun pausingDuringVerificationNotifiesOnceAndOnlyTheFreshRequestCanUnlock() {
    val queued = StandardTestDispatcher()
    SignalDispatchers.setDispatcherProvider(object : SignalDispatchers.DispatcherProvider {
      override val main = Dispatchers.Main
      override val io = queued
      override val default = Dispatchers.Default
      override val unconfined = Dispatchers.Unconfined
    })
    val controller = host()
    val activity = controller.get()
    var cancellations = 0
    ScreenLockAuthentication.authenticateForUnlock(activity, object : BiometricDialogFragment.Listener {
      override fun onSuccess(): Boolean = error("Paused verification must not authenticate")
      override fun onCancel(fromUser: Boolean): Boolean {
        assertFalse(fromUser)
        cancellations++
        return true
      }
      override fun onError(message: CharSequence) = true
    }, true)
    submit(prompt(activity))
    controller.pause()
    queued.scheduler.advanceUntilIdle()
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals(1, cancellations)
    assertFalse(ScreenLockController.isAccessAllowed())
    assertTrue(activity.supportFragmentManager.fragments.filterIsInstance<ScreenLockPinDialogFragment>().isEmpty())

    controller.resume().postResume()
    val generation = ScreenLockController.authenticationGeneration()
    var successes = 0
    ScreenLockAuthentication.authenticateForUnlock(activity, object : BiometricDialogFragment.Listener {
      override fun onSuccess(): Boolean {
        successes++
        return ScreenLockController.completeAuthentication(generation)
      }
      override fun onError(message: CharSequence) = true
    }, true)
    submit(prompt(activity))
    queued.scheduler.advanceUntilIdle()
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(1, successes)
    assertEquals(1, cancellations)
    assertTrue(ScreenLockController.isAccessAllowed())
    assertTrue(activity.supportFragmentManager.fragments.filterIsInstance<ScreenLockPinDialogFragment>().isEmpty())
  }
}
