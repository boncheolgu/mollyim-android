package org.thoughtcrime.securesms.jobs

import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.screenlock.ScreenLockFailureCleanup
import java.io.IOException

/** No network constraint: lack of connectivity must never defer deletion. BaseJob requires the database key. */
class ScreenLockFailureCleanupJob private constructor(parameters: Parameters) : BaseJob(parameters) {
  constructor() : this(Parameters.Builder().setQueue(KEY).setGlobalPriority(Parameters.PRIORITY_HIGH)
    .setMaxInstancesForQueue(1).setMaxAttempts(Parameters.UNLIMITED).build())

  override fun getFactoryKey() = KEY
  override fun serialize(): ByteArray? = null
  override fun onRun() = ScreenLockFailureCleanup.runPending(context)
  override fun onShouldRetry(e: Exception) = e is IOException
  override fun getNextRunAttemptBackoff(pastAttemptCount: Int, exception: Exception) = 1_000L
  override fun onFailure() = Unit

  class Factory : Job.Factory<ScreenLockFailureCleanupJob> {
    override fun create(parameters: Parameters, serializedData: ByteArray?) = ScreenLockFailureCleanupJob(parameters)
  }

  companion object { const val KEY = "ScreenLockFailureCleanupJob" }
}
