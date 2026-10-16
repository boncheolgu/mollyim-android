package org.thoughtcrime.securesms.screenlock

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
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
import org.robolectric.shadows.ShadowDialog
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.ScreenLockController
import org.thoughtcrime.securesms.decoynotes.DecoyNotesActivity
import org.thoughtcrime.securesms.decoynotes.DecoyNotesStore
import org.thoughtcrime.securesms.service.KeyCachingService
import java.io.File
import java.time.Duration
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class ScreenLockDecoyNotesActivityTest {
  private lateinit var application: Application
  private val controllers = mutableListOf<ActivityController<out Activity>>()
  private var observer: ScreenLockVisibilityObserver? = null

  @Before fun setUp() {
    clearPreferenceCache()
    application = RuntimeEnvironment.getApplication()
    // Config.NONE omits the AndroidX merged-manifest permission used below API 33.
    shadowOf(application).grantPermissions("${application.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
    listOf("decoy-notes.json", "decoy-notes.json.bak", "decoy-notes.json.new").forEach { File(application.noBackupFilesDir, it).delete() }
    File(application.noBackupFilesDir, "screen-lock-mode").writeText("OFF")
    KeyCachingService.clearMasterSecret()
    ScreenLockPreferences.write(application, ScreenLockPreferences.Record(ScreenLockMode.PIN))
    ScreenLockController.configure(application, false)
  }

  @After fun tearDown() {
    observer?.let { application.unregisterActivityLifecycleCallbacks(it) }
    controllers.filterNot { it.get().isDestroyed }.forEach { it.pause().stop().destroy() }
    drainStorage()
    ScreenLockPreferences.write(application, ScreenLockPreferences.Record(ScreenLockMode.OFF))
    ScreenLockController.configure(application, false)
    clearPreferenceCache()
  }

  private fun clearPreferenceCache() {
    for (name in listOf("cached", "file")) {
      ScreenLockPreferences::class.java.getDeclaredField(name).apply { isAccessible = true }.set(null, null)
    }
  }

  private fun drainStorage() {
    // AlertDialog posts its button listener to the main queue; dispatch it before
    // waiting for IO, then dispatch the completion posted back by that IO task.
    shadowOf(Looper.getMainLooper()).idle()
    DecoyNotesStore.io.submit {}.get(10, TimeUnit.SECONDS)
    shadowOf(Looper.getMainLooper()).idle()
  }

  private fun notes(state: Bundle? = null): ActivityController<DecoyNotesActivity> {
    val controller = Robolectric.buildActivity(DecoyNotesActivity::class.java)
    controllers.add(controller)
    controller.get().setTheme(R.style.TextSecure_LightNoActionBar)
    controller.create(state).start().resume().visible()
    drainStorage()
    return controller
  }

  @Test fun backgroundFlushAndRecreationRetainDraftWithoutUnlockingMemoly() {
    val controller = notes()
    val activity = controller.get()
    activity.findViewById<View>(R.id.decoy_notes_new).performClick()
    activity.findViewById<EditText>(R.id.decoy_notes_title_input).setText("장보기")
    activity.findViewById<EditText>(R.id.decoy_notes_body_input).setText("우유\n빵 🍞")
    val state = Bundle()
    controller.saveInstanceState(state).pause().stop().destroy()
    drainStorage()
    val reopened = notes(state).get()
    assertEquals("장보기", reopened.findViewById<EditText>(R.id.decoy_notes_title_input).text.toString())
    assertEquals("우유\n빵 🍞", reopened.findViewById<EditText>(R.id.decoy_notes_body_input).text.toString())
    assertFalse(ScreenLockController.isAccessAllowed())
  }

  @Test fun autosaveAndConfirmedDeletePersistAcrossStoreReopening() {
    val activity = notes().get()
    val samples = DecoyNotesStore(application.noBackupFilesDir).read()
    assertEquals(4, samples.size)
    val sampleIds = samples.map { it.id }.toSet()
    activity.findViewById<View>(R.id.decoy_notes_new).performClick()
    activity.findViewById<EditText>(R.id.decoy_notes_body_input).setText("A useful note")
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
    drainStorage()
    assertEquals("A useful note", DecoyNotesStore(application.noBackupFilesDir).read().single { it.id !in sampleIds }.body)
    activity.findViewById<View>(R.id.decoy_notes_delete).performClick()
    (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
    drainStorage()
    assertEquals(samples, DecoyNotesStore(application.noBackupFilesDir).readOrInitializeSamples())
    assertEquals(View.GONE, activity.findViewById<View>(R.id.decoy_notes_editor).visibility)
    assertFalse(ScreenLockController.isAccessAllowed())
  }

  @Test fun failedSaveKeepsTheEditorAndTextInsteadOfLeavingForTheList() {
    val activity = notes().get()
    activity.findViewById<View>(R.id.decoy_notes_new).performClick()
    val body = activity.findViewById<EditText>(R.id.decoy_notes_body_input)
    body.setText("Keep these edits")
    val file = File(application.noBackupFilesDir, "decoy-notes.json").apply { writeText("damaged storage") }
    activity.findViewById<View>(R.id.decoy_notes_back).performClick()
    drainStorage()
    assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.decoy_notes_editor).visibility)
    assertEquals("Keep these edits", body.text.toString())
    assertTrue(body.isEnabled)
    assertEquals("damaged storage", file.readText())
    assertFalse(ScreenLockController.isAccessAllowed())
  }

  @Test fun onlyNotesSurviveBlankingAndDecoyExitWhileProtectedActivityIsClosed() {
    val memo = notes().get()
    val hostController = Robolectric.buildActivity(Activity::class.java).create()
    controllers.add(hostController)
    val host = hostController.get()
    host.setContentView(FrameLayout(host))
    val visibility = ScreenLockVisibilityObserver(application).also { observer = it }
    visibility.starting(memo)
    visibility.onActivityResumed(memo)
    assertFalse(visibility.hasVisibleActivities)
    visibility.starting(host)
    assertEquals(View.INVISIBLE, host.findViewById<View>(android.R.id.content).visibility)
    assertTrue(visibility.hasVisibleActivities)
    assertNull(shadowOf(memo).nextStartedActivity)
    ScreenLockController.onScreenOff(application)
    visibility.prepareDecoyExit()
    visibility.finishDecoyExit()
    assertEquals(View.VISIBLE, memo.findViewById<View>(android.R.id.content).visibility)
    assertFalse(memo.isFinishing)
    assertTrue(host.isFinishing)
    assertFalse(ScreenLockController.isAccessAllowed())
  }

  @Test fun ordinaryActivityCannotImpersonateNotesWithExtrasOrViewIds() {
    val hostController = Robolectric.buildActivity(Activity::class.java, Intent().putExtra("decoy-notes", true)).create()
    controllers.add(hostController)
    val host = hostController.get()
    host.setContentView(FrameLayout(host).apply { id = R.id.decoy_notes_root })
    val visibility = ScreenLockVisibilityObserver(application).also { observer = it }
    visibility.starting(host)
    visibility.onActivityResumed(host)
    assertEquals(View.INVISIBLE, host.findViewById<View>(android.R.id.content).visibility)
    assertEquals(ScreenLockGateActivity::class.java.name, shadowOf(host).nextStartedActivity.component!!.className)
    assertFalse(ScreenLockController.isAccessAllowed())
  }
}
