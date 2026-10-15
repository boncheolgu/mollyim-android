package org.thoughtcrime.securesms.screenlock

import android.app.Application
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.signal.core.util.crypto.KeyStoreHelper
import org.thoughtcrime.securesms.ScreenLockController
import org.thoughtcrime.securesms.service.KeyCachingService
import java.io.File
import java.security.UnrecoverableEntryException
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [28])
class ScreenLockDecoyPinStoreTest {
  private lateinit var application: Application
  private val keys = mutableMapOf<String, SecretKey>()
  private val target = "example.notes/example.notes.MainActivity"

  @Before fun setUp() {
    clearCache()
    application = RuntimeEnvironment.getApplication()
    File(application.noBackupFilesDir, "screen-lock-mode").writeText("OFF")
    KeyCachingService.clearMasterSecret()
    mockkStatic(KeyStoreHelper::class)
    every { KeyStoreHelper.createKeyStoreEntryHmac(any(), false) } answers {
      SecretKeySpec(ByteArray(32) { (keys.size + 1).toByte() }, "HmacSHA256").also { keys[firstArg()] = it }
    }
    every { KeyStoreHelper.getKeyStoreEntryHmac(any()) } answers { keys[firstArg()] ?: throw UnrecoverableEntryException() }
    every { KeyStoreHelper.deleteKeyStoreEntry(any()) } answers { keys.remove(firstArg<String>()); Unit }
    mockkObject(ScreenLockDecoyLauncher)
    every { ScreenLockDecoyLauncher.isValidTarget(any(), target) } returns true
    ScreenLockPreferences.write(application, ScreenLockPreferences.Record(ScreenLockMode.OFF))
    ScreenLockController.configure(application, true)
    assertTrue(ScreenLockPinStore.install(application, "123456".toCharArray(), ScreenLockController.authenticationGeneration()) { true })
  }

  @After fun tearDown() {
    ScreenLockPinStore.setMode(application, ScreenLockMode.OFF)
    ScreenLockController.configure(application, false)
    unmockkObject(ScreenLockDecoyLauncher)
    unmockkStatic(KeyStoreHelper::class)
    clearCache()
  }

  private fun clearCache() {
    for (name in listOf("cached", "file")) {
      ScreenLockPreferences::class.java.getDeclaredField(name).apply { isAccessible = true }.set(null, null)
    }
  }

  private fun install(pin: String = "654321", generation: Long = ScreenLockController.authenticationGeneration()): ScreenLockPinStore.Result =
    ScreenLockPinStore.installDecoy(application, pin.toCharArray(), target, generation) { true }

  @Test fun alternatePinNeverAuthenticatesAndIsRejectedForSettings() {
    assertEquals(ScreenLockPinStore.Result.Success, install())
    ScreenLockController.forceLock()
    val generation = ScreenLockController.authenticationGeneration()
    assertEquals(ScreenLockPinStore.Result.Incorrect, ScreenLockPinStore.verify(application, "654321".toCharArray()))
    val result = ScreenLockPinStore.verify(application, "654321".toCharArray(), true)
    assertTrue(result is ScreenLockPinStore.Result.Decoy)
    assertFalse(ScreenLockController.isAccessAllowed())
    assertEquals(generation, ScreenLockController.authenticationGeneration())
    assertEquals(0, ScreenLockPreferences.read(application).failures)
    assertEquals(ScreenLockPinStore.Result.Success, ScreenLockPinStore.verify(application, "123456".toCharArray(), true))
  }

  @Test fun normalPinCannotAlsoBeRegisteredAsDecoy() {
    val pin = "123456".toCharArray()
    assertEquals(ScreenLockPinStore.Result.Duplicate, ScreenLockPinStore.installDecoy(application, pin, target, ScreenLockController.authenticationGeneration()) { true })
    assertTrue(pin.all { it == '\u0000' })
    assertNull(ScreenLockPreferences.getDecoyTarget(application))
    assertEquals(1, keys.size)
  }

  @Test fun oneBadInputCountsOnceAndBothPinsShareTheRetryLimit() {
    assertEquals(ScreenLockPinStore.Result.Success, install())
    repeat(5) { ScreenLockPinStore.verify(application, "999999".toCharArray(), true) }
    assertEquals(5, ScreenLockPreferences.read(application).failures)
    assertTrue(ScreenLockPinStore.verify(application, "654321".toCharArray(), true) is ScreenLockPinStore.Result.Blocked)
    assertTrue(ScreenLockPinStore.verify(application, "123456".toCharArray(), true) is ScreenLockPinStore.Result.Blocked)
  }

  @Test fun missingAlternateKeyDoesNotBreakTheNormalPinOrUnlockOnAlternateInput() {
    assertEquals(ScreenLockPinStore.Result.Success, install())
    keys.remove(ScreenLockPreferences.read(application).decoy!!.alias)
    assertEquals(ScreenLockPinStore.Result.Success, ScreenLockPinStore.verify(application, "123456".toCharArray(), true))
    assertEquals(ScreenLockPinStore.Result.Incorrect, ScreenLockPinStore.verify(application, "654321".toCharArray(), true))
  }

  @Test fun missingNormalKeyCannotBeBypassedUsingTheAlternatePin() {
    assertEquals(ScreenLockPinStore.Result.Success, install())
    keys.remove(checkNotNull(ScreenLockPreferences.read(application).alias))
    assertEquals(ScreenLockPinStore.Result.Unavailable, ScreenLockPinStore.verify(application, "654321".toCharArray(), true))
  }

  @Test fun replacingTheAlternatePinDeletesItsPreviousKeyButPreservesNormalPin() {
    assertEquals(ScreenLockPinStore.Result.Success, install())
    val old = ScreenLockPreferences.read(application)
    assertEquals(ScreenLockPinStore.Result.Success, install("777777"))
    assertFalse(keys.containsKey(old.decoy!!.alias))
    assertTrue(keys.containsKey(checkNotNull(old.alias)))
    assertEquals(ScreenLockPinStore.Result.Incorrect, ScreenLockPinStore.verify(application, "654321".toCharArray(), true))
    assertTrue(ScreenLockPinStore.verify(application, "777777".toCharArray(), true) is ScreenLockPinStore.Result.Decoy)
  }

  @Test fun removingTheFeatureDeletesOnlyItsKeyAndKeepsNormalAuthentication() {
    assertEquals(ScreenLockPinStore.Result.Success, install())
    val old = ScreenLockPreferences.read(application)
    assertTrue(ScreenLockPinStore.clearDecoy(application, ScreenLockController.authenticationGeneration()))
    assertFalse(keys.containsKey(old.decoy!!.alias))
    assertTrue(keys.containsKey(checkNotNull(old.alias)))
    assertNull(ScreenLockPreferences.getDecoyTarget(application))
    assertEquals(ScreenLockPinStore.Result.Success, ScreenLockPinStore.verify(application, "123456".toCharArray()))
  }

  @Test fun replacingTheNormalPinAlsoDeletesTheAlternateCredential() {
    assertEquals(ScreenLockPinStore.Result.Success, install())
    val aliases = keys.keys.toSet()
    assertTrue(ScreenLockPinStore.install(application, "246810".toCharArray(), ScreenLockController.authenticationGeneration()) { true })
    assertTrue(aliases.none { keys.containsKey(it) })
    assertNull(ScreenLockPreferences.getDecoyTarget(application))
  }

  @Test fun changingToBiometricsRemovesBothPinKeys() {
    assertEquals(ScreenLockPinStore.Result.Success, install())
    assertTrue(ScreenLockPinStore.changeMode(application, ScreenLockMode.BIOMETRIC, ScreenLockController.authenticationGeneration()))
    assertTrue(keys.isEmpty())
    assertNull(ScreenLockPreferences.getDecoyTarget(application))
  }

  @Test fun obsoleteOrCancelledSetupCannotReplaceTheConfiguredAlternatePin() {
    assertEquals(ScreenLockPinStore.Result.Success, install())
    val record = ScreenLockPreferences.read(application)
    val obsolete = ScreenLockController.authenticationGeneration()
    ScreenLockController.configure(application, true)
    assertEquals(ScreenLockPinStore.Result.Unavailable, install("777777", obsolete))
    assertEquals(record, ScreenLockPreferences.read(application))
    assertEquals(2, keys.size)
    try {
      ScreenLockPinStore.installDecoy(application, "777777".toCharArray(), target, ScreenLockController.authenticationGeneration()) { false }
      fail("Cancelled installation must not commit")
    } catch (_: IllegalStateException) { }
    assertEquals(record, ScreenLockPreferences.read(application))
    assertEquals(2, keys.size)
  }

  @Test fun lockedSettingsCannotInstallOrClearAnAlternateCredential() {
    assertEquals(ScreenLockPinStore.Result.Success, install())
    val record = ScreenLockPreferences.read(application)
    ScreenLockController.forceLock()
    assertEquals(ScreenLockPinStore.Result.Unavailable, install("777777"))
    assertFalse(ScreenLockPinStore.clearDecoy(application, ScreenLockController.authenticationGeneration()))
    assertEquals(record, ScreenLockPreferences.read(application))
  }
}
