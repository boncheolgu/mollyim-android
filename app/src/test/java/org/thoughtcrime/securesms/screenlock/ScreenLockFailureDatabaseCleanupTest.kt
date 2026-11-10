package org.thoughtcrime.securesms.screenlock

import android.app.Application
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.database.DraftTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.testutil.RecipientTestRule

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [28])
class ScreenLockFailureDatabaseCleanupTest {
  @get:Rule val recipients = RecipientTestRule()

  @Test fun deletesAllConversationsAndDraftsWhileRetainingRecipients() {
    val alice = recipients.createRecipient("Alice")
    val bob = recipients.createRecipient("Bob")
    val aliceThread = SignalDatabase.threads.getOrCreateThreadIdFor(Recipient.resolved(alice))
    val bobThread = SignalDatabase.threads.getOrCreateThreadIdFor(Recipient.resolved(bob))
    recipients.insertOutgoingMessage(alice, "first conversation")
    recipients.insertOutgoingMessage(bob, "second conversation")
    SignalDatabase.drafts.replaceDrafts(aliceThread, listOf(DraftTable.Draft(DraftTable.Draft.TEXT, "private draft")))
    assertEquals(1, SignalDatabase.messages.getMessageCountForThread(aliceThread))
    assertEquals(1, SignalDatabase.messages.getMessageCountForThread(bobThread))

    SignalDatabase.threads.deleteAllConversations()

    assertEquals(0, SignalDatabase.messages.getMessageCountForThread(aliceThread))
    assertEquals(0, SignalDatabase.messages.getMessageCountForThread(bobThread))
    assertTrue(SignalDatabase.drafts.getDrafts(aliceThread).isEmpty())
    assertFalse(SignalDatabase.threads.hasActiveThread(alice))
    assertFalse(SignalDatabase.threads.hasActiveThread(bob))
    assertNotNull(SignalDatabase.recipients.getRecord(alice))
    assertNotNull(SignalDatabase.recipients.getRecord(bob))
    assertNotNull(SignalDatabase.recipients.getRecord(recipients.self))
    SignalDatabase.threads.deleteAllConversations()
  }
}
