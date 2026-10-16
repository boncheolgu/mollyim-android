package org.thoughtcrime.securesms.screenlock

import android.app.Activity
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.ScreenLockController
import org.thoughtcrime.securesms.decoynotes.DecoyNotesActivity

/** Launches the independent notepad or a selected external entry; never grants access to chats. */
internal object ScreenLockDecoyLauncher {
  const val BUILT_IN_NOTES = "memoly:notes"
  data class Target(val component: String, val label: String)

  @Suppress("DEPRECATION")
  fun targets(context: Context): List<Target> = listOf(Target(BUILT_IN_NOTES, context.getString(R.string.decoy_notes_target))) + externalTargets(context)

  @Suppress("DEPRECATION")
  private fun externalTargets(context: Context): List<Target> = try {
    context.packageManager.queryIntentActivities(launcherIntent(), 0)
      .filter { usable(context, it.activityInfo) }
      .map { Target(ComponentName(it.activityInfo.packageName, it.activityInfo.name).flattenToString(), it.loadLabel(context.packageManager).toString()) }
      .distinctBy { it.component }
      .sortedWith(compareBy({ it.label }, { it.component }))
  } catch (_: RuntimeException) { emptyList() }

  fun label(context: Context, value: String): String = try {
    if (value == BUILT_IN_NOTES) context.getString(R.string.decoy_notes_target) else ComponentName.unflattenFromString(value)?.let {
      @Suppress("DEPRECATION")
      context.packageManager.getActivityInfo(it, 0).loadLabel(context.packageManager).toString()
    } ?: value
  } catch (_: PackageManager.NameNotFoundException) { value }
    catch (_: RuntimeException) { value }

  fun isValidTarget(context: Context, value: String): Boolean = resolve(context, value) != null

  @Suppress("DEPRECATION")
  private fun resolve(context: Context, value: String): Intent? = try {
    val component = ComponentName.unflattenFromString(value)
    if (value == BUILT_IN_NOTES) Intent(context, DecoyNotesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    else if (component == null || component.packageName == context.packageName) null
    else context.packageManager.queryIntentActivities(launcherIntent().setPackage(component.packageName), 0)
      .firstOrNull { ComponentName(it.activityInfo.packageName, it.activityInfo.name) == component && usable(context, it.activityInfo) }
      ?.let { launcherIntent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED) }
  } catch (_: RuntimeException) { null }

  private fun usable(context: Context, info: android.content.pm.ActivityInfo): Boolean =
    info.packageName != context.packageName && info.exported && info.enabled && info.applicationInfo.enabled &&
      (info.permission == null || context.checkSelfPermission(info.permission) == PackageManager.PERMISSION_GRANTED)

  private fun launcherIntent() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

  /** Main thread, after verification and the dialog's lifecycle/generation checks. */
  fun launch(activity: Activity, result: ScreenLockPinStore.Result.Decoy, generation: Long): Boolean {
    if (activity.isFinishing || activity.isDestroyed) return false
    val record = ScreenLockPreferences.read(activity)
    val decoy = record.decoy ?: return false
    if (record.mode != ScreenLockMode.PIN || decoy.alias != result.alias || decoy.component != result.component) return false
    val tasks = try { activity.getSystemService(ActivityManager::class.java)?.appTasks.orEmpty().toList() }
      catch (_: RuntimeException) { emptyList() }
    if (!ScreenLockController.beginDecoyExit(activity, generation)) return false

    var launched = false
    try {
      resolve(activity, result.component)?.let { activity.startActivity(it); launched = true }
    } catch (_: RuntimeException) {
      // An uninstalled/disabled target or rejected launch must never unlock Memoly.
    } finally {
      tasks.forEach { task ->
        try {
          // Do not remove a foreign caller's task if it contains one of our activities.
          val root = task.taskInfo.baseIntent.component
          if (root?.packageName == activity.packageName && root != ComponentName(activity, DecoyNotesActivity::class.java)) task.finishAndRemoveTask()
        } catch (_: RuntimeException) { /* Finish our live activities below as a fallback. */ }
      }
      if (!activity.isFinishing && activity.isTaskRoot && activity.intent?.component?.packageName == activity.packageName) {
        try { activity.finishAndRemoveTask() } catch (_: RuntimeException) { activity.finish() }
      }
      ScreenLockController.finishDecoyExit()
      activity.finish()
    }
    if (!launched) Toast.makeText(activity.applicationContext, R.string.screen_lock_decoy_launch_failed, Toast.LENGTH_LONG).show()
    return true
  }
}
