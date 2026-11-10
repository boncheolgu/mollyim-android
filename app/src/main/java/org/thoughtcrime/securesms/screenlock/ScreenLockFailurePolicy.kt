package org.thoughtcrime.securesms.screenlock

import org.json.JSONObject

/** Device-local, opt-in policy. Retrying deletion never repeats the warning. */
internal data class ScreenLockFailurePolicy(
  val enabled: Boolean = false,
  val failures: Int = 0,
  val triggered: Boolean = false,
  val lastRecipient: Long? = null,
  val phase: Phase = Phase.IDLE
) {
  enum class Phase { IDLE, WARNING_PENDING, DELETE_PENDING }

  fun incorrect(isDecoy: Boolean): ScreenLockFailurePolicy {
    if (!enabled || isDecoy || triggered) return this
    val count = (failures + 1).coerceAtMost(5)
    return copy(failures = count, triggered = count == 5, phase = if (count == 5) Phase.WARNING_PENDING else Phase.IDLE)
  }

  fun authenticated() = copy(failures = 0, triggered = false)

  fun toJson(): JSONObject = JSONObject().put("enabled", enabled).put("failures", failures)
    .put("triggered", triggered).put("lastRecipient", lastRecipient ?: JSONObject.NULL).put("phase", phase.name)

  companion object {
    fun fromJson(json: JSONObject?): ScreenLockFailurePolicy {
      if (json == null) return ScreenLockFailurePolicy()
      return try { ScreenLockFailurePolicy(
        enabled = json.getBoolean("enabled"),
        failures = json.getInt("failures").also { require(it in 0..5) },
        triggered = json.getBoolean("triggered"),
        lastRecipient = if (json.isNull("lastRecipient")) null else json.getLong("lastRecipient").also { require(it > 0) },
        phase = Phase.valueOf(json.getString("phase"))
      ) } catch (_: Exception) {
        // A malformed optional policy must neither arm destructive actions nor discard the PIN.
        ScreenLockFailurePolicy()
      }
    }
  }
}
