package org.thoughtcrime.securesms.screenlock

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ScreenLockFailureCleanupTest {
  private val armed = ScreenLockFailurePolicy(enabled = true, lastRecipient = 42)

  @Test fun onlyTheFifthActualIncorrectPinTriggers() {
    var policy = armed
    repeat(4) { policy = policy.incorrect(false); assertEquals(ScreenLockFailurePolicy.Phase.IDLE, policy.phase) }
    policy = policy.incorrect(false)
    assertEquals(ScreenLockFailurePolicy.Phase.WARNING_PENDING, policy.phase)
    assertTrue(policy.triggered)
    assertEquals(42L, policy.lastRecipient)
  }

  @Test fun disabledAndDecoyAttemptsDoNotAdvanceTheDestructiveCounter() {
    assertEquals(ScreenLockFailurePolicy(), ScreenLockFailurePolicy().incorrect(false))
    assertEquals(armed, armed.incorrect(true))
    val four = armed.copy(failures = 4)
    assertEquals(four, four.incorrect(true))
  }

  @Test fun correctNormalPinRestartsTheConsecutiveCount() {
    assertEquals(1, armed.copy(failures = 4).authenticated().incorrect(false).failures)
    assertFalse(armed.copy(triggered = true, failures = 5).authenticated().triggered)
  }

  @Test fun warningIsAttemptedBeforeDeletionEvenWhenOffline() {
    val operations = FakeOperations(fifth())
    operations.warningFails = true
    assertTrue(ScreenLockFailureCleanupRunner.run(operations))
    assertEquals(listOf("commit:DELETE_PENDING", "warn:42", "delete", "commit:IDLE"), operations.events)
    assertTrue(operations.policy.triggered)
    assertNull(operations.policy.lastRecipient)
    assertFalse(ScreenLockFailureCleanupRunner.run(operations))
  }

  @Test fun interruptionAfterCommittingDispatchResumesDeletionWithoutRepeatingWarning() {
    val operations = FakeOperations(fifth())
    operations.deletionFails = true
    try { ScreenLockFailureCleanupRunner.run(operations); fail("Expected deletion failure") } catch (_: IOException) { }
    assertEquals(ScreenLockFailurePolicy.Phase.DELETE_PENDING, operations.policy.phase)
    operations.events.clear()
    operations.deletionFails = false
    assertTrue(ScreenLockFailureCleanupRunner.run(operations))
    assertEquals(listOf("delete", "commit:IDLE"), operations.events)
  }

  @Test fun deletionDoesNotDependOnHavingAnEligibleRecipient() {
    val operations = FakeOperations(fifth().copy(lastRecipient = null))
    ScreenLockFailureCleanupRunner.run(operations)
    assertTrue(operations.events.contains("delete"))
    assertEquals(ScreenLockFailurePolicy.Phase.IDLE, operations.policy.phase)
  }

  @Test fun cannotRepeatWarningUntilARealPinAuthenticates() {
    val operations = FakeOperations(fifth())
    ScreenLockFailureCleanupRunner.run(operations)
    assertEquals(operations.policy, operations.policy.incorrect(false))
    var rearmed = operations.policy.authenticated()
    repeat(5) { rearmed = rearmed.incorrect(false) }
    assertEquals(ScreenLockFailurePolicy.Phase.WARNING_PENDING, rearmed.phase)
  }

  @Test fun failedDispatchCommitDoesNotSendOrDelete() {
    val operations = FakeOperations(fifth())
    operations.writeFails = true
    try { ScreenLockFailureCleanupRunner.run(operations); fail("Expected write failure") } catch (_: IOException) { }
    assertTrue(operations.events.isEmpty())
    assertEquals(ScreenLockFailurePolicy.Phase.WARNING_PENDING, operations.policy.phase)
  }

  @Test fun oldDraftWorkCannotRestoreDataAfterCleanup() {
    ScreenLockFailureCleanup.pending = false
    val before = ScreenLockFailureCleanup.draftGeneration()
    ScreenLockFailureCleanup.pending = true
    ScreenLockFailureCleanup.pending = false
    var writes = 0
    ScreenLockFailureCleanup.saveDraftIfCurrent(before) { writes++ }
    assertEquals(0, writes)
    ScreenLockFailureCleanup.saveDraftIfCurrent(ScreenLockFailureCleanup.draftGeneration()) { writes++ }
    assertEquals(1, writes)
  }

  private fun fifth(): ScreenLockFailurePolicy {
    var policy = armed
    repeat(5) { policy = policy.incorrect(false) }
    return policy
  }

  private class FakeOperations(var policy: ScreenLockFailurePolicy) : ScreenLockFailureCleanupRunner.Operations {
    val events = mutableListOf<String>()
    var warningFails = false
    var deletionFails = false
    var writeFails = false
    override fun read() = policy
    override fun write(policy: ScreenLockFailurePolicy) {
      if (writeFails) throw IOException("Storage unavailable")
      this.policy = policy
      events += "commit:${policy.phase}"
    }
    override fun warn(recipient: Long?) {
      events += "warn:$recipient"
      if (warningFails) throw IOException("Offline")
    }
    override fun deleteConversations() {
      events += "delete"
      if (deletionFails) throw IOException("Database unavailable")
    }
  }
}
