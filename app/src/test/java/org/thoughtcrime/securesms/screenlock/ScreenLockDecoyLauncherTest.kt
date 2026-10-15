package org.thoughtcrime.securesms.screenlock

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.verify
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.ScreenLockController
import org.thoughtcrime.securesms.service.KeyCachingService
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [28])
class ScreenLockDecoyLauncherTest {
  class HostActivity : Activity() {
    var taskManager: ActivityManager? = null
    var rejectLaunch = false
    override fun getSystemService(name: String): Any? =
      if (name == Context.ACTIVITY_SERVICE && taskManager != null) taskManager else super.getSystemService(name)
    override fun startActivity(intent: Intent) {
      if (rejectLaunch) throw SecurityException("Test launch rejection")
      super.startActivity(intent)
    }
  }

  private lateinit var application: Application
  private val component = ComponentName("example.notes", "example.notes.Main")
  private val alternate = ScreenLockPreferences.Decoy("memoly-decoy-pin-test", "salt", "tag", component.flattenToString())
  private val result get() = ScreenLockPinStore.Result.Decoy(alternate.component, alternate.alias)

  @Before fun setUp() {
    clearCache()
    application = RuntimeEnvironment.getApplication()
    File(application.noBackupFilesDir, "screen-lock-mode").writeText("OFF")
    KeyCachingService.clearMasterSecret()
    ScreenLockPreferences.write(application, ScreenLockPreferences.Record(ScreenLockMode.PIN, decoy = alternate))
    ScreenLockController.configure(application, false)
  }

  @After fun tearDown() {
    ScreenLockPreferences.write(application, ScreenLockPreferences.Record(ScreenLockMode.OFF))
    ScreenLockController.configure(application, false)
    clearCache()
  }

  private fun clearCache() {
    for (name in listOf("cached", "file")) {
      ScreenLockPreferences::class.java.getDeclaredField(name).apply { isAccessible = true }.set(null, null)
    }
  }

  @Suppress("DEPRECATION")
  private fun addTarget(packageName: String = component.packageName, name: String = component.className, exported: Boolean = true): ResolveInfo {
    val resolve = ResolveInfo().apply {
      activityInfo = ActivityInfo().apply {
        this.packageName = packageName
        this.name = name
        this.exported = exported
        enabled = true
        nonLocalizedLabel = "Notes"
        applicationInfo = ApplicationInfo().apply { this.packageName = packageName; enabled = true }
      }
    }
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    shadowOf(application.packageManager).addResolveInfoForIntent(intent, resolve)
    shadowOf(application.packageManager).addResolveInfoForIntent(Intent(intent).setPackage(packageName), resolve)
    return resolve
  }

  private fun task(packageName: String): ActivityManager.AppTask = mockk<ActivityManager.AppTask>().also {
    every { it.taskInfo } returns ActivityManager.RecentTaskInfo().apply { baseIntent = Intent().setComponent(ComponentName(packageName, "$packageName.Root")) }
    every { it.finishAndRemoveTask() } just Runs
  }

  @Test fun alternateLaunchEndsAuthenticationAndRemovesOnlyOwnedTasks() {
    addTarget()
    val host = Robolectric.buildActivity(HostActivity::class.java).setup().get()
    val owned = task(application.packageName)
    val foreign = task("another.caller")
    host.taskManager = mockk<ActivityManager> { every { appTasks } returns listOf(owned, foreign) }
    val generation = ScreenLockController.authenticationGeneration()

    assertTrue(ScreenLockDecoyLauncher.launch(host, result, generation))

    val intent = shadowOf(host).nextStartedActivity
    assertEquals(component, intent.component)
    assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    assertTrue(host.isFinishing)
    verify(exactly = 1) { owned.finishAndRemoveTask() }
    verify(exactly = 0) { foreign.finishAndRemoveTask() }
    assertFalse(ScreenLockController.isAccessAllowed())
    assertFalse(ScreenLockController.completeAuthentication(generation))
    assertFalse(ScreenLockController.runIfAccessAllowed(generation, Runnable { fail("Protected work must not run") }))
  }

  @Test fun missingTargetStillLeavesMemolyLockedAndFinishesItsScreen() {
    val host = Robolectric.buildActivity(HostActivity::class.java).setup().get()
    assertTrue(ScreenLockDecoyLauncher.launch(host, result, ScreenLockController.authenticationGeneration()))
    assertNull(shadowOf(host).nextStartedActivity)
    assertTrue(host.isFinishing)
    assertFalse(ScreenLockController.isAccessAllowed())
  }

  @Test fun rejectedExternalLaunchDoesNotFallBackToAuthenticationSuccess() {
    addTarget()
    val host = Robolectric.buildActivity(HostActivity::class.java).setup().get().apply { rejectLaunch = true }
    assertTrue(ScreenLockDecoyLauncher.launch(host, result, ScreenLockController.authenticationGeneration()))
    assertTrue(host.isFinishing)
    assertFalse(ScreenLockController.isAccessAllowed())
  }

  @Test fun staleVerificationCannotLaunchAnAppOrFinishTheCurrentTask() {
    addTarget()
    val host = Robolectric.buildActivity(HostActivity::class.java).setup().get()
    val generation = ScreenLockController.authenticationGeneration()
    ScreenLockController.configure(application, false)
    assertFalse(ScreenLockDecoyLauncher.launch(host, result, generation))
    assertNull(shadowOf(host).nextStartedActivity)
    assertFalse(host.isFinishing)
  }

  @Test fun replacedAlternateCredentialRejectsTheOldVerificationResult() {
    val host = Robolectric.buildActivity(HostActivity::class.java).setup().get()
    ScreenLockPreferences.update(application) { it.copy(decoy = alternate.copy(alias = "memoly-decoy-pin-replaced")) }
    assertFalse(ScreenLockDecoyLauncher.launch(host, result, ScreenLockController.authenticationGeneration()))
    assertFalse(host.isFinishing)
  }

  @Test fun selectionRejectsMemolyAndNonExportedActivities() {
    addTarget(application.packageName, "MemolyLauncher")
    addTarget(exported = false)
    assertTrue(ScreenLockDecoyLauncher.targets(application).isEmpty())
    assertFalse(ScreenLockDecoyLauncher.isValidTarget(application, alternate.component))
    assertFalse(ScreenLockDecoyLauncher.isValidTarget(application, ComponentName(application.packageName, "MemolyLauncher").flattenToString()))
  }
}
