package org.thoughtcrime.securesms.screenlock

import android.content.Context
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.database.GroupTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.messages.GroupSendUtil
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.util.GroupUtil
import org.whispersystems.signalservice.api.crypto.ContentHint
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A bounded, one-shot send, with no outbox row, resend payload or durable send job to outlive deletion. */
internal object ScreenLockFailureAlert {
  private val TAG = Log.tag(ScreenLockFailureAlert::class.java)
  private const val SEND_BUDGET_SECONDS = 3L

  fun send(context: Context, recipientId: Long?) {
    if (recipientId == null || !NetworkConstraint.isMet(context)) return
    val cancelled = AtomicBoolean(false)
    val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "pin-failure-alert").apply { isDaemon = true } }
    val future = executor.submit {
      if (!SignalStore.registration.isRegistrationComplete || SignalStore.misc.isClientDeprecated) return@submit
      val recipient = Recipient.resolved(RecipientId.from(recipientId))
      if (recipient.isBlocked || recipient.isSelf || recipient.isReleaseNotes) return@submit
      val builder = SignalServiceDataMessage.newBuilder().withTimestamp(System.currentTimeMillis())
        .withBody(context.getString(R.string.screen_lock_failure_cleanup_message)).withExpiration(recipient.expiresInSeconds)
      val groupId = if (recipient.isPushGroup) recipient.requireGroupId().requireV2() else null
      val targets = if (groupId != null) {
        val group = SignalDatabase.groups.getGroup(groupId).orElse(null) ?: return@submit
        if (!group.isMember || !recipient.isActiveGroup || (group.isAnnouncementGroup && !group.isAdmin(Recipient.self()))) return@submit
        GroupUtil.setDataMessageGroupContext(context, builder, groupId)
        SignalDatabase.groups.getGroupMembers(groupId, GroupTable.MemberSet.FULL_MEMBERS_EXCLUDING_SELF)
      } else {
        if (!recipient.isIndividual || !recipient.isRegistered || !recipient.hasServiceId) return@submit
        listOf(recipient)
      }
      if (!cancelled.get() && targets.isNotEmpty()) {
        GroupSendUtil.sendUnresendableDataMessage(context, groupId, targets, false, ContentHint.DEFAULT, builder.build(), true) { cancelled.get() }
      }
    }
    try {
      future.get(SEND_BUDGET_SECONDS, TimeUnit.SECONDS)
    } catch (_: Exception) {
      Log.w(TAG, "Best-effort PIN failure warning did not finish; proceeding with local deletion")
    } finally {
      cancelled.set(true)
      future.cancel(true)
      executor.shutdownNow()
    }
  }
}
