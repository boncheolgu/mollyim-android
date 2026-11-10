package org.thoughtcrime.securesms.screenlock

import android.content.Context
import org.signal.core.util.concurrent.SignalExecutors
import org.signal.core.util.ThreadUtil
import com.bumptech.glide.Glide
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.ScreenLockController
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobs.ScreenLockFailureCleanupJob
import org.thoughtcrime.securesms.recipients.RecipientId
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

object ScreenLockFailureCleanup {
  private val TAG = Log.tag(ScreenLockFailureCleanup::class.java)

  private val generation = AtomicLong()
  @Volatile internal var pending: Boolean = false
    set(value) {
      if (value && !field) generation.incrementAndGet()
      field = value
    }

  internal fun draftGeneration(): Long = generation.get()

  @Synchronized internal fun saveDraftIfCurrent(expected: Long, action: () -> Unit) {
    if (!pending && generation.get() == expected) action()
  }

  /** Called only after current-lock authentication and the explicit destructive-action dialog. */
  fun setEnabled(context: Context, enabled: Boolean, generation: Long): Boolean =
    ScreenLockController.runIfCurrentAuthentication(generation) {
      check(ScreenLockController.isAccessAllowed())
      ScreenLockPreferences.update(context) {
        check(it.mode == ScreenLockMode.PIN && it.cleanup.phase == ScreenLockFailurePolicy.Phase.IDLE)
        it.copy(cleanup = ScreenLockFailurePolicy(enabled = enabled, lastRecipient = it.cleanup.lastRecipient))
      }
    }

  fun recordConversation(context: Context, recipientId: RecipientId) {
    if (recipientId.isUnknown) return
    if (!ScreenLockPreferences.isFailureCleanupEnabled(context)) return
    val generation = ScreenLockController.authenticationGeneration()
    ScreenLockController.runIfAccessAllowed(generation) {
      val record = ScreenLockPreferences.read(context)
      if (record.cleanup.enabled && record.cleanup.phase == ScreenLockFailurePolicy.Phase.IDLE && record.cleanup.lastRecipient != recipientId.toLong()) {
        ScreenLockPreferences.write(context, record.copy(cleanup = record.cleanup.copy(lastRecipient = recipientId.toLong())))
      }
    }
  }

  /** A dialog unlock does not resume the conversation fragment underneath it. */
  internal fun recordVisibleConversation(context: Context) {
    if (!ScreenLockPreferences.isFailureCleanupEnabled(context)) return
    SignalExecutors.BOUNDED_IO.execute {
      val visible = AppDependencies.messageNotifier.visibleThread.orElse(null) ?: return@execute
      val recipient = SignalDatabase.threads.getRecipientIdForThreadId(visible.threadId) ?: return@execute
      if (AppDependencies.messageNotifier.visibleThread.orElse(null) == visible) recordConversation(context, recipient)
    }
  }

  /** Also invoked on startup: an interrupted enqueue must not lose a committed incident. */
  @JvmStatic fun enqueue(context: Context) {
    if (ScreenLockPreferences.read(context).cleanup.phase == ScreenLockFailurePolicy.Phase.IDLE) return
    SignalExecutors.BOUNDED_IO.execute {
      try {
        AppDependencies.jobManager.add(ScreenLockFailureCleanupJob())
      } catch (_: Exception) {
        Log.w(TAG, "Cleanup enqueue deferred until application startup")
      }
    }
  }

  @Synchronized internal fun runPending(context: Context) {
    try {
      val operations = object : ScreenLockFailureCleanupRunner.Operations {
        override fun read() = ScreenLockPreferences.read(context).cleanup
        override fun write(policy: ScreenLockFailurePolicy) {
          ScreenLockPreferences.update(context) { it.copy(cleanup = policy) }
        }
        override fun warn(recipient: Long?) = ScreenLockFailureAlert.send(context, recipient)
        override fun deleteConversations() {
          // Dispose editors before clearing their persisted data; old queued draft saves are fenced.
          ThreadUtil.runOnMainSync {
            ScreenLockController.finishActivitiesForFailureCleanup()
            Glide.get(context).clearMemory()
          }
          // Same local-only deletion path as Storage > Clear message history. No delete-sync job.
          SignalDatabase.threads.deleteAllConversations()
          AppDependencies.blobs.clearConversationBlobs(context)
          Glide.get(context).clearDiskCache()
        }
      }
      if (ScreenLockFailureCleanupRunner.run(operations)) {
        ScreenLockController.onFailureCleanupStateChanged()
        AppDependencies.messageNotifier.clearVisibleThread()
        AppDependencies.messageNotifier.updateNotification(context)
      }
    } catch (e: Exception) {
      // BaseJob treats RuntimeException as fatal. Persist DELETE_PENDING and retry database failures.
      throw IOException("PIN failure cleanup has not completed", e)
    }
  }
}

internal object ScreenLockFailureCleanupRunner {
  interface Operations {
    fun read(): ScreenLockFailurePolicy
    fun write(policy: ScreenLockFailurePolicy)
    fun warn(recipient: Long?)
    fun deleteConversations()
  }

  fun run(operations: Operations): Boolean {
    var policy = operations.read()
    if (policy.phase == ScreenLockFailurePolicy.Phase.IDLE) return false
    if (policy.phase == ScreenLockFailurePolicy.Phase.WARNING_PENDING) {
      // Commit before dispatch: process death may lose a best-effort warning, never send it twice.
      policy = policy.copy(phase = ScreenLockFailurePolicy.Phase.DELETE_PENDING)
      operations.write(policy)
      try { operations.warn(policy.lastRecipient) } catch (_: Exception) { /* Deletion takes priority. */ }
    }
    operations.deleteConversations()
    operations.write(policy.copy(phase = ScreenLockFailurePolicy.Phase.IDLE, lastRecipient = null))
    return true
  }
}
