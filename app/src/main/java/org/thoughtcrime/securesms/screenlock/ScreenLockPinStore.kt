package org.thoughtcrime.securesms.screenlock

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import org.signal.core.util.crypto.KeyStoreHelper
import org.thoughtcrime.securesms.ScreenLockController
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

object ScreenLockPinStore {
  sealed class Result {
    object Success : Result()
    object Incorrect : Result()
    object Unavailable : Result()
    object Duplicate : Result()
    data class Decoy(val component: String, val alias: String) : Result()
    data class Blocked(val milliseconds: Long) : Result()
  }

  private const val PREFIX = "molly-screen-pin-"
  private const val DECOY_PREFIX = "memoly-decoy-pin-"

  @JvmStatic
  @Synchronized
  fun verify(context: Context, pin: CharArray, allowDecoy: Boolean = false): Result {
    try {
      var record = ScreenLockPreferences.read(context)
      if (record.mode != ScreenLockMode.PIN) return Result.Unavailable
      val now = SystemClock.elapsedRealtime()
      val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
      if (record.failures >= 5 && record.bootCount != boot) {
        record = record.copy(blockedUntil = now + ScreenLockBackoff.delayMillis(record.failures), bootCount = boot)
        ScreenLockPreferences.update(context) { it.copy(blockedUntil = record.blockedUntil, bootCount = boot) }
      }
      if (record.blockedUntil > now) return Result.Blocked(record.blockedUntil - now)
      val correct = matches(record.alias, record.salt, record.verifier, pin, false)
      // Classify one attempt before updating the common retry limit. Never authenticate
      // a settings confirmation with the alternate PIN, even when it is configured.
      val decoy = if (allowDecoy) record.decoy else null
      val decoyCorrect = decoy?.let {
        try { matches(it.alias, it.salt, it.verifier, pin, true) }
        catch (_: Exception) { false }
        catch (_: AssertionError) { false }
      } ?: false
      if (correct) {
        ScreenLockPreferences.update(context) { it.copy(failures = 0, blockedUntil = 0, bootCount = boot) }
        return Result.Success
      }
      // A decoy is not normal authentication: knowing it must not let someone
      // reset the normal PIN's guess counter between unsuccessful attempts.
      if (decoyCorrect) return checkNotNull(decoy).let { Result.Decoy(it.component, it.alias) }
      val failures = (record.failures + 1).coerceAtMost(30)
      val delay = ScreenLockBackoff.delayMillis(failures)
      ScreenLockPreferences.update(context) { it.copy(failures = failures, blockedUntil = now + delay, bootCount = boot) }
      return if (delay > 0) Result.Blocked(delay) else Result.Incorrect
    } catch (_: Exception) {
      return Result.Unavailable
    } catch (_: AssertionError) {
      return Result.Unavailable
    } finally {
      pin.fill('\u0000')
    }
  }

  @JvmStatic
  @Synchronized
  fun install(context: Context, pin: CharArray, generation: Long, active: () -> Boolean): Boolean {
    val alias = PREFIX + UUID.randomUUID()
    var installed = false
    try {
      require(pin.size in 4..16 && pin.all { it in '0'..'9' })
      val key = KeyStoreHelper.createKeyStoreEntryHmac(alias, false)
      val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
      val verifier = ScreenLockPinVerifier.calculate(key, salt, pin)
      val old = ScreenLockPreferences.read(context)
      installed = ScreenLockController.runIfCurrentAuthentication(generation) {
        check(active())
        ScreenLockPreferences.write(context, ScreenLockPreferences.Record(
          mode = ScreenLockMode.PIN,
          timeoutSeconds = ScreenLockPreferences.read(context).timeoutSeconds,
          alias = alias,
          salt = Base64.encodeToString(salt, Base64.NO_WRAP),
          verifier = Base64.encodeToString(verifier, Base64.NO_WRAP)
        ))
        ScreenLockController.configure(context, true)
      }
      verifier.fill(0)
      if (installed) {
        old.alias?.let { deleteKey(it) }
        old.decoy?.alias?.let { deleteKey(it) }
      }
      return installed
    } finally {
      pin.fill('\u0000')
      if (!installed) deleteKey(alias)
    }
  }

  private fun matches(alias: String?, salt: String?, verifier: String?, pin: CharArray, decoy: Boolean): Boolean {
    val keyAlias = requireNotNull(alias)
    require(keyAlias.startsWith(if (decoy) DECOY_PREFIX else PREFIX))
    val saltBytes = Base64.decode(requireNotNull(salt), Base64.NO_WRAP)
    val expected = Base64.decode(requireNotNull(verifier), Base64.NO_WRAP)
    require(saltBytes.size == 32 && expected.size == 32)
    val actual = ScreenLockPinVerifier.calculate(KeyStoreHelper.getKeyStoreEntryHmac(keyAlias), saltBytes, pin, decoy)
    return try { MessageDigest.isEqual(expected, actual) } finally { actual.fill(0) }
  }

  @JvmStatic
  @Synchronized
  fun installDecoy(context: Context, pin: CharArray, component: String, generation: Long, active: () -> Boolean): Result {
    val alias = DECOY_PREFIX + UUID.randomUUID()
    var installed = false
    try {
      val old = ScreenLockPreferences.read(context)
      if (old.mode != ScreenLockMode.PIN || !ScreenLockController.isAccessAllowed()) return Result.Unavailable
      if (matches(old.alias, old.salt, old.verifier, pin, false)) return Result.Duplicate
      if (!ScreenLockDecoyLauncher.isValidTarget(context, component)) return Result.Unavailable
      val key = KeyStoreHelper.createKeyStoreEntryHmac(alias, false)
      val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
      val verifier = ScreenLockPinVerifier.calculate(key, salt, pin, true)
      try {
        installed = ScreenLockController.runIfCurrentAuthentication(generation) {
          check(active() && ScreenLockController.isAccessAllowed())
          val current = ScreenLockPreferences.read(context)
          check(current.mode == ScreenLockMode.PIN && current.alias == old.alias)
          ScreenLockPreferences.write(context, current.copy(decoy = ScreenLockPreferences.Decoy(
            alias, Base64.encodeToString(salt, Base64.NO_WRAP), Base64.encodeToString(verifier, Base64.NO_WRAP), component
          )))
          ScreenLockController.configure(context, true)
        }
      } finally { verifier.fill(0) }
      if (installed) old.decoy?.alias?.let { deleteKey(it) }
      return if (installed) Result.Success else Result.Unavailable
    } finally {
      pin.fill('\u0000')
      if (!installed) deleteKey(alias)
    }
  }

  @JvmStatic
  @Synchronized
  fun clearDecoy(context: Context, generation: Long): Boolean {
    val old = ScreenLockPreferences.read(context)
    if (old.mode != ScreenLockMode.PIN || !ScreenLockController.isAccessAllowed()) return false
    val cleared = ScreenLockController.runIfCurrentAuthentication(generation) {
      ScreenLockPreferences.write(context, ScreenLockPreferences.read(context).copy(decoy = null))
      ScreenLockController.configure(context, true)
    }
    if (cleared) old.decoy?.alias?.let { deleteKey(it) }
    return cleared
  }

  @JvmStatic
  @Synchronized
  fun setMode(context: Context, mode: ScreenLockMode) {
    require(mode != ScreenLockMode.PIN)
    val old = ScreenLockPreferences.read(context)
    ScreenLockPreferences.write(context, ScreenLockPreferences.Record(mode, old.timeoutSeconds))
    old.alias?.let { deleteKey(it) }
    old.decoy?.alias?.let { deleteKey(it) }
  }

  @JvmStatic
  @Synchronized
  fun changeMode(context: Context, mode: ScreenLockMode, generation: Long): Boolean {
    return ScreenLockController.runIfCurrentAuthentication(generation) {
      setMode(context, mode)
      ScreenLockController.configure(context, true)
    }
  }

  private fun deleteKey(alias: String) {
    if (!alias.startsWith(PREFIX) && !alias.startsWith(DECOY_PREFIX)) return
    try {
      KeyStoreHelper.deleteKeyStoreEntry(alias)
    } catch (_: Exception) {
      // An unused key cannot unlock a record.
    } catch (_: AssertionError) {
      // Some KeyStoreHelper failures are wrapped in AssertionError.
    }
  }

}
