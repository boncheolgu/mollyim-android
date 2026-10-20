package org.thoughtcrime.securesms.screenlock

import android.annotation.SuppressLint
import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import io.mockk.every
import io.mockk.just
import io.mockk.mockkStatic
import io.mockk.Runs
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.thoughtcrime.securesms.BaseActivity
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.ScreenLockController
import org.thoughtcrime.securesms.service.KeyCachingService
import org.thoughtcrime.securesms.util.WindowUtil
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class ScreenLockPromptTransitionTest {
  @SuppressLint("BaseActivitySubclass")
  class HostActivity : BaseActivity() {
    override fun onCreate(state: Bundle?) {
      setTheme(R.style.Theme_Signal_DayNight_NoActionBar)
      super.onCreate(state)
      setContentView(TextView(this).apply { text = "Protected content" })
    }
  }

  private lateinit var application: Application
  private val controllers = mutableListOf<ActivityController<HostActivity>>()

  @Before fun setUp() {
    clearPreferenceCache()
    application = RuntimeEnvironment.getApplication()
    File(application.noBackupFilesDir, "screen-lock-mode").writeText("OFF")
    KeyCachingService.clearMasterSecret()
    ScreenLockPreferences.write(application, ScreenLockPreferences.Record(ScreenLockMode.PIN, 0))
    ScreenLockController.configure(application, true)
    // The fixture has no application dependency graph; screenshot policy is tested separately.
    mockkStatic(WindowUtil::class)
    every { WindowUtil.initializeScreenshotSecurity(any(), any()) } just Runs
  }

  @After fun tearDown() {
    controllers.forEach {
      if (it.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) it.pause()
      it.stop().destroy()
    }
    shadowOf(Looper.getMainLooper()).idle()
    ScreenLockPreferences.write(application, ScreenLockPreferences.Record(ScreenLockMode.OFF))
    ScreenLockController.configure(application, false)
    clearPreferenceCache()
    unmockkStatic(WindowUtil::class)
  }

  private fun clearPreferenceCache() {
    for (name in listOf("cached", "file")) {
      ScreenLockPreferences::class.java.getDeclaredField(name).apply { isAccessible = true }.set(null, null)
    }
  }

  private fun host(): ActivityController<HostActivity> {
    val controller = Robolectric.buildActivity(HostActivity::class.java).setup()
    controllers.add(controller)
    controller.get().onTopResumedActivityChanged(true)
    shadowOf(Looper.getMainLooper()).idle()
    return controller
  }

  private fun prompts(activity: HostActivity) = activity.supportFragmentManager.fragments.filterIsInstance<ScreenLockPinDialogFragment>()

  @Test fun returningFromACallPromptsAfterPostResumeWithoutAWindowFocusCallback() {
    val controller = host()
    val activity = controller.get()
    controller.pause()
    ScreenLockController.onActivitiesHidden(application)
    ScreenLockController.hideProtectedWindow(activity.window)
    // AOSP finishes performResume (including onPostResume) before reporting top-resume.
    // This is a normal-return control, not proof of the reported intermittent bug.
    controller.resume().postResume()
    activity.onTopResumedActivityChanged(true)
    shadowOf(Looper.getMainLooper()).idle()

    assertFalse(ScreenLockController.isAccessAllowed())
    assertEquals(View.INVISIBLE, activity.findViewById<View>(android.R.id.content).visibility)
    assertEquals(1, prompts(activity).size)
    assertTrue(prompts(activity).single().requireDialog().isShowing)
  }

  @Test fun lockingAnAlreadyTopResumedScreenDoesNotDependOnAnotherFocusCallback() {
    val activity = host().get()
    ScreenLockController.configure(application, false)
    activity.onScreenLockStateChanged()
    shadowOf(Looper.getMainLooper()).idle()

    val first = prompts(activity).single()
    activity.onTopResumedActivityChanged(true)
    activity.onWindowFocusChanged(true)
    activity.onScreenLockStateChanged()
    shadowOf(Looper.getMainLooper()).idle()
    assertSame(first, prompts(activity).single())
    assertFalse(ScreenLockController.isAccessAllowed())
  }

  @Test fun queuedPromptCannotOpenAfterTheActivityIsPaused() {
    val controller = host()
    ScreenLockController.configure(application, false)
    controller.get().onScreenLockStateChanged()
    controller.pause()
    shadowOf(Looper.getMainLooper()).idle()
    assertTrue(prompts(controller.get()).isEmpty())
    assertFalse(ScreenLockController.isAccessAllowed())
  }

  @Test fun authenticationBeforeQueuedPromptRestoresContentWithoutReopeningPin() {
    val activity = host().get()
    ScreenLockController.configure(application, false)
    activity.onScreenLockStateChanged()
    assertTrue(ScreenLockController.completeAuthentication(ScreenLockController.authenticationGeneration()))
    activity.onScreenLockStateChanged()
    shadowOf(Looper.getMainLooper()).idle()

    assertTrue(ScreenLockController.isAccessAllowed())
    assertTrue(prompts(activity).isEmpty())
    assertEquals(View.VISIBLE, activity.findViewById<View>(android.R.id.content).visibility)
  }

  @Test fun aBackgroundSessionDoesNotLoopAndTheNextRealResumeShowsOnePrompt() {
    val controller = host()
    val activity = controller.get()
    ScreenLockController.onTaskRemoved(application)
    activity.onScreenLockStateChanged()
    shadowOf(Looper.getMainLooper()).idle()

    assertFalse(ScreenLockController.isAuthenticationReady())
    assertFalse(ScreenLockController.isAccessAllowed())
    assertTrue(prompts(activity).isEmpty())

    controller.pause().resume().postResume()
    activity.onTopResumedActivityChanged(true)
    shadowOf(Looper.getMainLooper()).idle()
    assertTrue(ScreenLockController.isAuthenticationReady())
    assertEquals(1, prompts(activity).size)
  }

  @Test fun aQueuedPromptRechecksWhetherTheSessionBecameBackground() {
    val activity = host().get()
    ScreenLockController.configure(application, false)
    activity.onScreenLockStateChanged()
    ScreenLockController.onTaskRemoved(application)
    shadowOf(Looper.getMainLooper()).idle()

    assertFalse(ScreenLockController.isAuthenticationReady())
    assertFalse(ScreenLockController.isAccessAllowed())
    assertTrue(prompts(activity).isEmpty())
  }

  @Test fun invalidatingAnOpenPromptReplacesItBeforeAnotherPinEntry() {
    val activity = host().get()
    ScreenLockController.configure(application, false)
    activity.onScreenLockStateChanged()
    shadowOf(Looper.getMainLooper()).idle()
    val previous = prompts(activity).single()

    ScreenLockController.configure(application, false)
    val generation = ScreenLockController.authenticationGeneration()
    activity.onScreenLockStateChanged()
    shadowOf(Looper.getMainLooper()).idle()

    val replacement = prompts(activity).single()
    assertNotSame(previous, replacement)
    assertEquals(generation, replacement.requireArguments().getLong("generation"))
    assertTrue(replacement.requireDialog().isShowing)
    assertFalse(ScreenLockController.isAccessAllowed())
  }
}
