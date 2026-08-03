package com.balancesentinel.app.data.repository

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class WidgetPrefsTest {

    private lateinit var context: Context
    private lateinit var prefs: WidgetPrefs

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = WidgetPrefs(context)
        // Reset to defaults
        prefs.refreshIntervalSeconds = WidgetPrefs.DEFAULT_INTERVAL
        prefs.logMaxEntries = WidgetPrefs.DEFAULT_LOG_MAX
        prefs.alertEnabled = false
        prefs.alertThreshold = 0f
        prefs.changeAlertEnabled = false
        prefs.changeAlertThreshold = 0f
        prefs.changeAlertPeriodMinutes = 0
    }

    @After
    fun tearDown() {
        prefs.alertEnabled = false
        prefs.alertThreshold = 0f
        prefs.changeAlertEnabled = false
        prefs.changeAlertThreshold = 0f
        prefs.changeAlertPeriodMinutes = 0
    }

    // ── Default values ──

    @Test
    fun `default refresh interval is 30 seconds`() {
        val fresh = WidgetPrefs(context)
        assertEquals(30, fresh.refreshIntervalSeconds)
    }

    @Test
    fun `default log max is 100`() {
        val fresh = WidgetPrefs(context)
        assertEquals(100, fresh.logMaxEntries)
    }

    @Test
    fun `default alert is disabled`() {
        val fresh = WidgetPrefs(context)
        assertFalse(fresh.alertEnabled)
        assertEquals(0f, fresh.alertThreshold)
    }

    @Test
    fun `default change alert is disabled`() {
        val fresh = WidgetPrefs(context)
        assertFalse(fresh.changeAlertEnabled)
        assertEquals(0f, fresh.changeAlertThreshold)
        assertEquals(0, fresh.changeAlertPeriodMinutes)
    }

    // ── Read/Write ──

    @Test
    fun `refresh interval set and get`() {
        prefs.refreshIntervalSeconds = 60
        assertEquals(60, prefs.refreshIntervalSeconds)
    }

    @Test
    fun `log max entries set and get with coercion`() {
        prefs.logMaxEntries = 5
        assertEquals(10, prefs.logMaxEntries) // coerceIn(10, 1000)

        prefs.logMaxEntries = 2000
        assertEquals(1000, prefs.logMaxEntries) // coerceIn(10, 1000)

        prefs.logMaxEntries = 500
        assertEquals(500, prefs.logMaxEntries) // within range
    }

    @Test
    fun `alert enabled set and get`() {
        prefs.alertEnabled = true
        assertTrue(prefs.alertEnabled)

        prefs.alertEnabled = false
        assertFalse(prefs.alertEnabled)
    }

    @Test
    fun `alert threshold set and get`() {
        prefs.alertThreshold = 50f
        assertEquals(50f, prefs.alertThreshold)
    }

    @Test
    fun `change alert settings persist`() {
        prefs.changeAlertEnabled = true
        prefs.changeAlertThreshold = 25f
        prefs.changeAlertPeriodMinutes = 30

        assertTrue(prefs.changeAlertEnabled)
        assertEquals(25f, prefs.changeAlertThreshold)
        assertEquals(30, prefs.changeAlertPeriodMinutes)
    }

    // ── Per-account state ──

    @Test
    fun `per-account keys are isolated`() {
        prefs.setLastAlertedBalance("acc1", 50f)
        prefs.setLastAlertedBalance("acc2", 100f)

        assertEquals(50f, prefs.getLastAlertedBalance("acc1"))
        assertEquals(100f, prefs.getLastAlertedBalance("acc2"))
    }

    @Test
    fun `same account currencies keep independent alert anchors and dedup timestamps`() {
        prefs.resetAll()

        prefs.setLastAlertedBalance("acct", "CNY", 11f)
        prefs.setLastAlertedBalance("acct", "USD", 12f)
        prefs.setPreviousBalance("acct", "CNY", 21f)
        prefs.setPreviousBalance("acct", "USD", 22f)
        prefs.setPreviousBalanceTime("acct", "CNY", 31L)
        prefs.setPreviousBalanceTime("acct", "USD", 32L)
        prefs.setLastChangeAlertedBalance("acct", "CNY", 41f)
        prefs.setLastChangeAlertedBalance("acct", "USD", 42f)
        prefs.setLastChangeAlertedTime("acct", "CNY", 51L)
        prefs.setLastChangeAlertedTime("acct", "USD", 52L)

        assertEquals(11f, prefs.getLastAlertedBalance("acct", "CNY"))
        assertEquals(12f, prefs.getLastAlertedBalance("acct", "USD"))
        assertEquals(21f, prefs.getPreviousBalance("acct", "CNY"))
        assertEquals(22f, prefs.getPreviousBalance("acct", "USD"))
        assertEquals(31L, prefs.getPreviousBalanceTime("acct", "CNY"))
        assertEquals(32L, prefs.getPreviousBalanceTime("acct", "USD"))
        assertEquals(41f, prefs.getLastChangeAlertedBalance("acct", "CNY"))
        assertEquals(42f, prefs.getLastChangeAlertedBalance("acct", "USD"))
        assertEquals(51L, prefs.getLastChangeAlertedTime("acct", "CNY"))
        assertEquals(52L, prefs.getLastChangeAlertedTime("acct", "USD"))
    }

    @Test
    fun `currency normalization is locale independent across pair storage and enables`() {
        prefs.resetAll()
        val originalLocale = Locale.getDefault()
        Locale.setDefault(Locale("tr", "TR"))
        try {
            prefs.setLastAlertedBalance("acct", "usd", 7f)
            prefs.setBalanceAlertEnabled("acct", "usd", true)
            prefs.setChangeAlertEnabled("acct", "usd", true)

            assertEquals("USD", AlertIdentity("acct", "usd").normalizedCurrency)
            assertEquals("acct_USD", AlertIdentity("acct", "usd").storageSuffix)
            assertEquals(7f, prefs.getLastAlertedBalance("acct", "USD"))
            assertTrue(prefs.isBalanceAlertEnabled("acct", "USD"))
            assertTrue(prefs.isChangeAlertEnabled("acct", "USD"))
            val raw = context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
            assertTrue(raw.contains("last_alerted_balance_acct_USD"))
            assertTrue(raw.contains("alert_enabled_acct_USD"))
            assertTrue(raw.contains("change_alert_enabled_acct_USD"))
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun `pair migration preserves enables removes legacy anchors and runs only once`() {
        prefs.resetAll()
        val raw = context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
        assertTrue(
            raw.edit()
                .putBoolean("alert_enabled_acct_CNY", true)
                .putBoolean("change_alert_enabled_acct_CNY", false)
                .putBoolean("alert_enabled_acct_USD", false)
                .putBoolean("change_alert_enabled_acct_USD", true)
                .putFloat("last_alerted_balance_acct", 11f)
                .putFloat("previous_balance_acct", 21f)
                .putLong("previous_balance_time_acct", 31L)
                .putFloat("last_change_alerted_balance_acct", 41f)
                .putLong("last_change_alerted_time_acct", 51L)
                .commit()
        )

        val migrated = WidgetPrefs(context)

        assertTrue(migrated.isBalanceAlertEnabled("acct", "CNY"))
        assertFalse(migrated.isChangeAlertEnabled("acct", "CNY"))
        assertFalse(migrated.isBalanceAlertEnabled("acct", "USD"))
        assertTrue(migrated.isChangeAlertEnabled("acct", "USD"))
        assertFalse(raw.contains("last_alerted_balance_acct"))
        assertFalse(raw.contains("previous_balance_acct"))
        assertFalse(raw.contains("previous_balance_time_acct"))
        assertFalse(raw.contains("last_change_alerted_balance_acct"))
        assertFalse(raw.contains("last_change_alerted_time_acct"))
        assertEquals(-1f, migrated.getLastAlertedBalance("acct", "CNY"))
        assertEquals(-1f, migrated.getPreviousBalance("acct", "CNY"))
        assertTrue(raw.getBoolean("alert_pair_state_migrated_v1", false))

        assertTrue(raw.edit().putFloat("previous_balance_legacy-after-marker", 99f).commit())
        WidgetPrefs(context)
        assertTrue(raw.contains("previous_balance_legacy-after-marker"))
    }

    @Test
    fun `pair migration marker is absent when its state commit fails`() {
        prefs.resetAll()
        val raw = context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
        assertTrue(raw.edit().putFloat("previous_balance_acct", 21f).commit())
        val failingContext = FailFirstWidgetCommitContext(context)

        val failure = runCatching { WidgetPrefs(failingContext) }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(raw.contains("previous_balance_acct"))
        assertFalse(raw.getBoolean("alert_pair_state_migrated_v1", false))

        WidgetPrefs(failingContext)
        assertFalse(raw.contains("previous_balance_acct"))
        assertTrue(raw.getBoolean("alert_pair_state_migrated_v1", false))
    }

    @Test
    fun `removeAccountAlertState removes every pair selection and state for only that account`() {
        prefs.resetAll()
        prefs.showTotalBalanceInNotification = false
        for (currency in listOf("CNY", "USD")) {
            prefs.setLastAlertedBalance("target", currency, 10f)
            prefs.setPreviousBalance("target", currency, 20f)
            prefs.setPreviousBalanceTime("target", currency, 30L)
            prefs.setLastChangeAlertedBalance("target", currency, 40f)
            prefs.setLastChangeAlertedTime("target", currency, 50L)
            prefs.setBalanceAlertEnabled("target", currency, true)
            prefs.setChangeAlertEnabled("target", currency, true)
            prefs.setNotificationWalletSelected("target", currency, true)
        }
        prefs.setLastAlertedBalance("survivor", "USD", 70f)
        prefs.setBalanceAlertEnabled("survivor", "USD", true)
        prefs.setNotificationWalletSelected("survivor", "USD", true)

        prefs.removeAccountAlertState("target")

        for (currency in listOf("CNY", "USD")) {
            assertEquals(-1f, prefs.getLastAlertedBalance("target", currency))
            assertEquals(-1f, prefs.getPreviousBalance("target", currency))
            assertEquals(0L, prefs.getPreviousBalanceTime("target", currency))
            assertEquals(-1f, prefs.getLastChangeAlertedBalance("target", currency))
            assertEquals(0L, prefs.getLastChangeAlertedTime("target", currency))
            assertFalse(prefs.isBalanceAlertEnabled("target", currency))
            assertFalse(prefs.isChangeAlertEnabled("target", currency))
            assertFalse(prefs.isNotificationWalletSelected("target", currency))
        }
        assertEquals(70f, prefs.getLastAlertedBalance("survivor", "USD"))
        assertTrue(prefs.isBalanceAlertEnabled("survivor", "USD"))
        assertTrue(prefs.isNotificationWalletSelected("survivor", "USD"))
    }

    @Test
    fun `per-account initial values are defaults`() {
        assertEquals(-1f, prefs.getLastAlertedBalance("unknown"))
        assertEquals(-1f, prefs.getPreviousBalance("unknown"))
        assertEquals(0L, prefs.getPreviousBalanceTime("unknown"))
        assertEquals(-1f, prefs.getLastChangeAlertedBalance("unknown"))
        assertEquals(0L, prefs.getLastChangeAlertedTime("unknown"))
    }

    @Test
    fun `previous balance set and get per account`() {
        prefs.setPreviousBalance("acc1", 200f)
        prefs.setPreviousBalanceTime("acc1", 5000L)

        assertEquals(200f, prefs.getPreviousBalance("acc1"))
        assertEquals(5000L, prefs.getPreviousBalanceTime("acc1"))
    }

    @Test
    fun `change alerted state set and get per account`() {
        prefs.setLastChangeAlertedBalance("acc1", 75f)
        prefs.setLastChangeAlertedTime("acc1", 9999L)

        assertEquals(75f, prefs.getLastChangeAlertedBalance("acc1"))
        assertEquals(9999L, prefs.getLastChangeAlertedTime("acc1"))
    }

    @Test
    fun `removeAccountAlertState clears all per-account keys`() {
        prefs.setLastAlertedBalance("acc1", 50f)
        prefs.setPreviousBalance("acc1", 200f)
        prefs.setPreviousBalanceTime("acc1", 5000L)
        prefs.setLastChangeAlertedBalance("acc1", 75f)
        prefs.setLastChangeAlertedTime("acc1", 9999L)

        prefs.removeAccountAlertState("acc1")

        assertEquals(-1f, prefs.getLastAlertedBalance("acc1"))
        assertEquals(-1f, prefs.getPreviousBalance("acc1"))
        assertEquals(0L, prefs.getPreviousBalanceTime("acc1"))
        assertEquals(-1f, prefs.getLastChangeAlertedBalance("acc1"))
        assertEquals(0L, prefs.getLastChangeAlertedTime("acc1"))
    }

    @Test
    fun `removeAccountAlertState does not affect other accounts`() {
        prefs.setLastAlertedBalance("acc1", 50f)
        prefs.setLastAlertedBalance("acc2", 100f)

        prefs.removeAccountAlertState("acc1")

        assertEquals(-1f, prefs.getLastAlertedBalance("acc1"))
        assertEquals(100f, prefs.getLastAlertedBalance("acc2"))
    }

    // ── resetAll ──

    @Test
    fun `resetAll restores all settings to defaults`() {
        // Change everything
        prefs.refreshIntervalSeconds = 120
        prefs.logMaxEntries = 500
        prefs.alertEnabled = true
        prefs.alertThreshold = 50f
        prefs.changeAlertEnabled = true
        prefs.changeAlertThreshold = 25f
        prefs.changeAlertPeriodMinutes = 60
        prefs.setLastAlertedBalance("acc1", 50f)
        prefs.setPreviousBalance("acc1", 200f)

        prefs.resetAll()

        // Global settings back to defaults
        assertEquals(WidgetPrefs.DEFAULT_INTERVAL, prefs.refreshIntervalSeconds)
        assertEquals(WidgetPrefs.DEFAULT_LOG_MAX, prefs.logMaxEntries)
        assertFalse(prefs.alertEnabled)
        assertEquals(0f, prefs.alertThreshold)
        assertFalse(prefs.changeAlertEnabled)
        assertEquals(0f, prefs.changeAlertThreshold)
        assertEquals(0, prefs.changeAlertPeriodMinutes)

        // Per-account state cleared
        assertEquals(-1f, prefs.getLastAlertedBalance("acc1"))
        assertEquals(-1f, prefs.getPreviousBalance("acc1"))
    }

    @Test
    fun `resetAll is idempotent`() {
        prefs.resetAll()

        assertEquals(WidgetPrefs.DEFAULT_INTERVAL, prefs.refreshIntervalSeconds)

        prefs.resetAll()
        assertEquals(WidgetPrefs.DEFAULT_INTERVAL, prefs.refreshIntervalSeconds)
    }

    // ═══════════════════════════════════════════════════════════
    // snoozeDurationMinutes
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `snooze duration default is 60 minutes`() {
        prefs.resetAll()
        assertEquals(60, prefs.snoozeDurationMinutes)
    }

    @Test
    fun `snooze duration set and get within range`() {
        prefs.snoozeDurationMinutes = 120
        assertEquals(120, prefs.snoozeDurationMinutes)
    }

    @Test
    fun `snooze duration coerced to min 5`() {
        prefs.snoozeDurationMinutes = 3
        assertEquals(5, prefs.snoozeDurationMinutes)
    }

    @Test
    fun `snooze duration coerced to max 1440`() {
        prefs.snoozeDurationMinutes = 2000
        assertEquals(1440, prefs.snoozeDurationMinutes)
    }

    @Test
    fun `snooze duration coerced for negative values`() {
        prefs.snoozeDurationMinutes = -10
        assertEquals(5, prefs.snoozeDurationMinutes)
    }

    // ═══════════════════════════════════════════════════════════
    // language
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `language default is null`() {
        prefs.resetAll()
        assertNull(prefs.language)
    }

    @Test
    fun `language set and get`() {
        prefs.language = "zh"
        assertEquals("zh", prefs.language)
    }

    @Test
    fun `language set to null removes key`() {
        prefs.language = "en"
        assertEquals("en", prefs.language)
        prefs.language = null
        assertNull(prefs.language)
    }

    @Test
    fun `language supports multiple values`() {
        prefs.language = "zh"
        assertEquals("zh", prefs.language)
        prefs.language = "en"
        assertEquals("en", prefs.language)
        prefs.language = "ja"
        assertEquals("ja", prefs.language)
    }

    // ═══════════════════════════════════════════════════════════
    // Snooze — per-account
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `getSnoozeUntil default is 0`() {
        prefs.resetAll()
        assertEquals(0L, prefs.getSnoozeUntil("any-account"))
    }

    @Test
    fun `snooze until set and get per account`() {
        prefs.setSnoozeUntil("acc1", 5000L)
        assertEquals(5000L, prefs.getSnoozeUntil("acc1"))
    }

    @Test
    fun `snooze until isolated per account`() {
        prefs.setSnoozeUntil("acc1", 5000L)
        prefs.setSnoozeUntil("acc2", 9999L)
        assertEquals(5000L, prefs.getSnoozeUntil("acc1"))
        assertEquals(9999L, prefs.getSnoozeUntil("acc2"))
    }

    @Test
    fun `setSnoozeUntil to 0 clears snooze`() {
        prefs.setSnoozeUntil("acc1", 5000L)
        prefs.setSnoozeUntil("acc1", 0L)
        assertEquals(0L, prefs.getSnoozeUntil("acc1"))
    }

    @Test
    fun `clearAllSnooze removes all snooze keys`() {
        prefs.setSnoozeUntil("acc1", 5000L)
        prefs.setSnoozeUntil("acc2", 9999L)
        prefs.clearAllSnooze()
        assertEquals(0L, prefs.getSnoozeUntil("acc1"))
        assertEquals(0L, prefs.getSnoozeUntil("acc2"))
    }

    @Test
    fun `clearAllSnooze on empty state is safe`() {
        prefs.clearAllSnooze()
        // No exception thrown
        assertEquals(0L, prefs.getSnoozeUntil("nonexistent"))
    }

    @Test
    fun `getSnoozeInfo returns empty when no snoozes`() {
        prefs.resetAll()
        val info = prefs.getSnoozeInfo()
        assertFalse(info.anySnoozed)
        assertEquals(0L, info.maxRemainingMs)
        assertTrue(info.snoozedAccountIds.isEmpty())
    }

    @Test
    fun `getSnoozeInfo detects active snoozes`() {
        val future = System.currentTimeMillis() + 3600_000L // 1 hour from now
        prefs.setSnoozeUntil("acc1", future)
        val info = prefs.getSnoozeInfo()
        assertTrue(info.anySnoozed)
        assertTrue(info.snoozedAccountIds.contains("acc1"))
        assertTrue(info.maxRemainingMs > 0L)
    }

    @Test
    fun `getSnoozeInfo finds earliest remaining`() {
        val now = System.currentTimeMillis()
        val soon = now + 600_000L  // 10 min
        val later = now + 3600_000L // 1 hour
        prefs.setSnoozeUntil("acc1", soon)
        prefs.setSnoozeUntil("acc2", later)
        val info = prefs.getSnoozeInfo()
        assertTrue(info.anySnoozed)
        assertEquals(2, info.snoozedAccountIds.size)
        // maxRemainingMs should reflect later snooze
        assertTrue(info.maxRemainingMs >= later - now - 1000)
    }

    @Test
    fun `getSnoozeInfo cleans expired snoozes`() {
        val past = System.currentTimeMillis() - 3600_000L // 1 hour ago
        prefs.setSnoozeUntil("acc1", past)
        val info = prefs.getSnoozeInfo()
        assertFalse(info.anySnoozed)
        assertFalse(info.snoozedAccountIds.contains("acc1"))
        // Expired key should be cleaned up
        assertEquals(0L, prefs.getSnoozeUntil("acc1"))
    }

    @Test
    fun `getSnoozeInfo mixed expired and active`() {
        val now = System.currentTimeMillis()
        val past = now - 3600_000L
        val future = now + 3600_000L
        prefs.setSnoozeUntil("expired", past)
        prefs.setSnoozeUntil("active", future)
        val info = prefs.getSnoozeInfo()
        assertTrue(info.anySnoozed)
        assertEquals(1, info.snoozedAccountIds.size)
        assertTrue(info.snoozedAccountIds.contains("active"))
        assertFalse(info.snoozedAccountIds.contains("expired"))
    }

    // ═══════════════════════════════════════════════════════════
    // Per-account+currency alert enabled (v2.1)
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `isBalanceAlertEnabled falls back to global when not set per currency`() {
        prefs.resetAll()
        prefs.alertEnabled = true
        assertTrue(prefs.isBalanceAlertEnabled("acc1", "CNY"))
        prefs.alertEnabled = false
        assertFalse(prefs.isBalanceAlertEnabled("acc1", "CNY"))
    }

    @Test
    fun `setBalanceAlertEnabled overrides global for specific currency`() {
        prefs.alertEnabled = false
        prefs.setBalanceAlertEnabled("acc1", "CNY", true)
        assertTrue(prefs.isBalanceAlertEnabled("acc1", "CNY"))
        // Other currency still falls back to global false
        assertFalse(prefs.isBalanceAlertEnabled("acc1", "USD"))
    }

    @Test
    fun `isChangeAlertEnabled falls back to global when not set per currency`() {
        prefs.resetAll()
        prefs.changeAlertEnabled = true
        assertTrue(prefs.isChangeAlertEnabled("acc1", "CNY"))
        prefs.changeAlertEnabled = false
        assertFalse(prefs.isChangeAlertEnabled("acc1", "CNY"))
    }

    @Test
    fun `setChangeAlertEnabled overrides global for specific currency`() {
        prefs.changeAlertEnabled = false
        prefs.setChangeAlertEnabled("acc1", "USD", true)
        assertTrue(prefs.isChangeAlertEnabled("acc1", "USD"))
        assertFalse(prefs.isChangeAlertEnabled("acc1", "CNY"))
    }

    @Test
    fun `per currency alert settings isolated by account and currency`() {
        prefs.setBalanceAlertEnabled("acc1", "CNY", true)
        prefs.setBalanceAlertEnabled("acc1", "USD", false)
        prefs.setBalanceAlertEnabled("acc2", "CNY", false)
        assertTrue(prefs.isBalanceAlertEnabled("acc1", "CNY"))
        assertFalse(prefs.isBalanceAlertEnabled("acc1", "USD"))
        assertFalse(prefs.isBalanceAlertEnabled("acc2", "CNY"))
    }

    @Test
    fun `removeAccountCurrencyAlertState clears per-currency keys`() {
        prefs.setBalanceAlertEnabled("acc1", "CNY", true)
        prefs.setChangeAlertEnabled("acc1", "CNY", true)
        prefs.removeAccountCurrencyAlertState("acc1", "CNY")
        // Should fall back to global (false)
        assertFalse(prefs.isBalanceAlertEnabled("acc1", "CNY"))
        assertFalse(prefs.isChangeAlertEnabled("acc1", "CNY"))
    }

    @Test
    fun `removeAccountCurrencyAlertState only affects specified currency`() {
        prefs.setBalanceAlertEnabled("acc1", "CNY", true)
        prefs.setBalanceAlertEnabled("acc1", "USD", true)
        prefs.setLastAlertedBalance("acc1", "CNY", 10f)
        prefs.setLastAlertedBalance("acc1", "USD", 20f)
        prefs.removeAccountCurrencyAlertState("acc1", "CNY")
        assertFalse(prefs.isBalanceAlertEnabled("acc1", "CNY"))
        assertTrue(prefs.isBalanceAlertEnabled("acc1", "USD"))
        assertEquals(-1f, prefs.getLastAlertedBalance("acc1", "CNY"))
        assertEquals(20f, prefs.getLastAlertedBalance("acc1", "USD"))
    }

    // ═══════════════════════════════════════════════════════════
    // PerCurrencyAlertSetting export/import
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `getAllPerCurrencyAlertSettings returns empty when none set`() {
        prefs.resetAll()
        val settings = prefs.getAllPerCurrencyAlertSettings()
        assertTrue(settings.isEmpty())
    }

    @Test
    fun `getAllPerCurrencyAlertSettings returns configured settings`() {
        prefs.setBalanceAlertEnabled("acc1", "CNY", true)
        prefs.setChangeAlertEnabled("acc1", "CNY", false)
        val settings = prefs.getAllPerCurrencyAlertSettings()
        assertEquals(1, settings.size)
        assertEquals("acc1", settings[0].accountId)
        assertEquals("CNY", settings[0].currency)
        assertTrue(settings[0].balanceAlertEnabled)
        assertFalse(settings[0].changeAlertEnabled)
    }

    @Test
    fun `applyPerCurrencyAlertSettings imports and overrides`() {
        val imported = listOf(
            PerCurrencyAlertSetting("acc1", "CNY", true, true),
            PerCurrencyAlertSetting("acc2", "USD", false, true)
        )
        prefs.applyPerCurrencyAlertSettings(imported)
        assertTrue(prefs.isBalanceAlertEnabled("acc1", "CNY"))
        assertTrue(prefs.isChangeAlertEnabled("acc1", "CNY"))
        assertFalse(prefs.isBalanceAlertEnabled("acc2", "USD"))
        assertTrue(prefs.isChangeAlertEnabled("acc2", "USD"))
    }

    @Test
    fun `getAllPerCurrencyAlertSettings round-trips through apply`() {
        prefs.setBalanceAlertEnabled("acc1", "CNY", true)
        prefs.setChangeAlertEnabled("acc1", "CNY", true)
        prefs.setBalanceAlertEnabled("acc1", "USD", false)
        prefs.setChangeAlertEnabled("acc1", "USD", true)

        val exported = prefs.getAllPerCurrencyAlertSettings()
        assertEquals(2, exported.size)

        // Reset and re-import
        prefs.resetAll()
        prefs.applyPerCurrencyAlertSettings(exported)

        assertTrue(prefs.isBalanceAlertEnabled("acc1", "CNY"))
        assertTrue(prefs.isChangeAlertEnabled("acc1", "CNY"))
        assertFalse(prefs.isBalanceAlertEnabled("acc1", "USD"))
        assertTrue(prefs.isChangeAlertEnabled("acc1", "USD"))
    }

    // ═══════════════════════════════════════════════════════════
    // showTotalBalanceInNotification
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `showTotalBalanceInNotification default is true`() {
        prefs.resetAll()
        assertTrue(prefs.showTotalBalanceInNotification)
    }

    @Test
    fun `showTotalBalanceInNotification set false removes total from order`() {
        prefs.resetAll()
        prefs.showTotalBalanceInNotification = false
        assertFalse(prefs.showTotalBalanceInNotification)
        assertFalse(prefs.isTotalInNotification())
        // Total should not be in order
        val order = prefs.getNotificationWalletOrder()
        assertFalse(order.contains("__total__"))
    }

    @Test
    fun `showTotalBalanceInNotification set true adds total back`() {
        prefs.resetAll()
        prefs.showTotalBalanceInNotification = false
        prefs.showTotalBalanceInNotification = true
        assertTrue(prefs.showTotalBalanceInNotification)
        assertTrue(prefs.isTotalInNotification())
    }

    // ═══════════════════════════════════════════════════════════
    // Notification wallet ordering
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `getNotificationWalletOrder includes total when enabled`() {
        prefs.resetAll()
        prefs.showTotalBalanceInNotification = true
        val order = prefs.getNotificationWalletOrder()
        assertTrue(order.contains("__total__"))
    }

    @Test
    fun `isNotificationWalletSelected returns true for selected wallet`() {
        prefs.setNotificationWalletSelected("acc1", "CNY", true)
        assertTrue(prefs.isNotificationWalletSelected("acc1", "CNY"))
    }

    @Test
    fun `isNotificationWalletSelected returns false for unselected wallet`() {
        prefs.resetAll()
        assertFalse(prefs.isNotificationWalletSelected("unknown", "CNY"))
    }

    @Test
    fun `setNotificationWalletSelected false removes from order`() {
        prefs.setNotificationWalletSelected("acc1", "CNY", true)
        assertTrue(prefs.isNotificationWalletSelected("acc1", "CNY"))
        prefs.setNotificationWalletSelected("acc1", "CNY", false)
        assertFalse(prefs.isNotificationWalletSelected("acc1", "CNY"))
    }

    @Test
    fun `moveNotificationWalletUp shifts position`() {
        prefs.resetAll()
        prefs.showTotalBalanceInNotification = true
        prefs.setNotificationWalletSelected("acc1", "CNY", true)
        prefs.setNotificationWalletSelected("acc2", "USD", true)
        // Initial order: [total, acc1_CNY, acc2_USD]
        val posBefore = prefs.getNotificationWalletPosition("acc2", "USD")
        prefs.moveNotificationWalletUp("acc2", "USD")
        val posAfter = prefs.getNotificationWalletPosition("acc2", "USD")
        assertEquals(posBefore - 1, posAfter)
    }

    @Test
    fun `moveNotificationWalletDown shifts position`() {
        prefs.resetAll()
        prefs.showTotalBalanceInNotification = true
        prefs.setNotificationWalletSelected("acc1", "CNY", true)
        prefs.setNotificationWalletSelected("acc2", "USD", true)
        // Initial order: [total, acc1_CNY, acc2_USD]
        val posBefore = prefs.getNotificationWalletPosition("acc1", "CNY")
        prefs.moveNotificationWalletDown("acc1", "CNY")
        val posAfter = prefs.getNotificationWalletPosition("acc1", "CNY")
        assertEquals(posBefore + 1, posAfter)
    }

    @Test
    fun `moveNotificationWalletUp at top does nothing`() {
        prefs.resetAll()
        prefs.showTotalBalanceInNotification = true
        val posBefore = prefs.getNotificationWalletPosition("__total__", "")
        prefs.moveNotificationWalletUp("__total__", "")
        val posAfter = prefs.getNotificationWalletPosition("__total__", "")
        assertEquals(posBefore, posAfter)
    }

    @Test
    fun `moveNotificationWalletDown at bottom does nothing`() {
        prefs.resetAll()
        prefs.setNotificationWalletSelected("acc1", "CNY", true)
        val posBefore = prefs.getNotificationWalletPosition("acc1", "CNY")
        prefs.moveNotificationWalletDown("acc1", "CNY")
        val posAfter = prefs.getNotificationWalletPosition("acc1", "CNY")
        assertEquals(posBefore, posAfter)
    }

    @Test
    fun `getNotificationWalletPosition returns minus one for unknown`() {
        prefs.resetAll()
        assertEquals(-1, prefs.getNotificationWalletPosition("nonexistent", "XYZ"))
    }

    @Test
    fun `getNotificationWalletCount reflects selected wallets`() {
        prefs.resetAll()
        prefs.showTotalBalanceInNotification = true
        prefs.setNotificationWalletSelected("acc1", "CNY", true)
        prefs.setNotificationWalletSelected("acc2", "USD", true)
        assertEquals(3, prefs.getNotificationWalletCount()) // total + 2 wallets
    }

    @Test
    fun `getNotificationWalletCount zero when total disabled and no wallets`() {
        prefs.resetAll()
        prefs.showTotalBalanceInNotification = false
        assertEquals(0, prefs.getNotificationWalletCount())
    }

    // ═══════════════════════════════════════════════════════════
    // NotificationWalletSelection export/import
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `getAllNotificationWalletSelections returns current order`() {
        prefs.resetAll()
        prefs.showTotalBalanceInNotification = false
        prefs.setNotificationWalletSelected("acc1", "CNY", true)
        prefs.setNotificationWalletSelected("acc2", "USD", true)
        val selections = prefs.getAllNotificationWalletSelections()
        assertEquals(2, selections.size)
        assertTrue(selections.any { it.accountId == "acc1" && it.currency == "CNY" })
        assertTrue(selections.any { it.accountId == "acc2" && it.currency == "USD" })
    }

    @Test
    fun `applyNotificationWalletSelections restores order`() {
        prefs.resetAll()
        prefs.showTotalBalanceInNotification = false
        val imported = listOf(
            NotificationWalletSelection("acc1", "CNY"),
            NotificationWalletSelection("acc2", "USD")
        )
        prefs.applyNotificationWalletSelections(imported)
        assertTrue(prefs.isNotificationWalletSelected("acc1", "CNY"))
        assertTrue(prefs.isNotificationWalletSelected("acc2", "USD"))
        assertEquals(2, prefs.getNotificationWalletCount())
    }

    @Test
    fun `applyNotificationWalletSelections empty list clears order`() {
        prefs.resetAll()
        prefs.showTotalBalanceInNotification = false
        prefs.setNotificationWalletSelected("acc1", "CNY", true)
        prefs.applyNotificationWalletSelections(emptyList())
        assertEquals(0, prefs.getNotificationWalletCount())
    }

    @Test
    fun `account removal cannot lose a concurrent notification selection`() {
        assertLifecycleOrderMutationRetainsConcurrentSelection(
            lifecycleThreadName = "widget-remove",
            lifecycleMutation = { it.removeAccountData("old-account") },
            expectedMigratedAccountId = null
        )
    }

    @Test
    fun `account migration cannot lose a concurrent notification selection`() {
        assertLifecycleOrderMutationRetainsConcurrentSelection(
            lifecycleThreadName = "widget-migrate",
            lifecycleMutation = { it.migrateAccountData("old-account", "new-account") },
            expectedMigratedAccountId = "new-account"
        )
    }

    @Test
    fun `account migration cannot resurrect concurrently removed per currency alert state`() {
        prefs.resetAll()
        prefs.setBalanceAlertEnabled("old-account", "USD", true)
        prefs.setChangeAlertEnabled("old-account", "USD", true)
        val migrationReady = CountDownLatch(1)
        val removalObserved = CountDownLatch(1)
        val resumeMigration = CountDownLatch(1)
        val blockingContext = BlockingAlertMigrationContext(
            context,
            migrationReady,
            removalObserved,
            resumeMigration
        )
        val migrationThread = Thread(
            { WidgetPrefs(blockingContext).migrateAccountData("old-account", "new-account") },
            "widget-alert-migrate"
        )
        val removalThread = Thread(
            {
                WidgetPrefs(blockingContext)
                    .removeAccountCurrencyAlertState("new-account", "USD")
            },
            "widget-alert-remove"
        )

        migrationThread.start()
        assertTrue(migrationReady.await(5, TimeUnit.SECONDS))
        removalThread.start()
        assertTrue(awaitConcurrentWriteOrSharedLock(removalThread, removalObserved))
        resumeMigration.countDown()
        migrationThread.join(5_000)
        removalThread.join(5_000)

        assertFalse(migrationThread.isAlive)
        assertFalse(removalThread.isAlive)
        assertFalse(prefs.isBalanceAlertEnabled("old-account", "USD"))
        assertFalse(prefs.isChangeAlertEnabled("old-account", "USD"))
        assertFalse(prefs.isBalanceAlertEnabled("new-account", "USD"))
        assertFalse(prefs.isChangeAlertEnabled("new-account", "USD"))
    }

    // ═══════════════════════════════════════════════════════════
    // isTotalInNotification
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `isTotalInNotification mirrors showTotalBalanceInNotification`() {
        prefs.resetAll()
        prefs.showTotalBalanceInNotification = true
        assertTrue(prefs.isTotalInNotification())
        prefs.showTotalBalanceInNotification = false
        assertFalse(prefs.isTotalInNotification())
    }

    private fun assertLifecycleOrderMutationRetainsConcurrentSelection(
        lifecycleThreadName: String,
        lifecycleMutation: (WidgetPrefs) -> Unit,
        expectedMigratedAccountId: String?
    ) {
        prefs.resetAll()
        prefs.showTotalBalanceInNotification = false
        prefs.setNotificationWalletSelected("old-account", "USD", true)
        prefs.setNotificationWalletSelected("survivor", "USD", true)
        val lifecycleWriteReady = CountDownLatch(1)
        val concurrentWriteObserved = CountDownLatch(1)
        val resumeLifecycleWrite = CountDownLatch(1)
        val blockingContext = BlockingNotificationOrderContext(
            context,
            lifecycleThreadName,
            lifecycleWriteReady,
            concurrentWriteObserved,
            resumeLifecycleWrite
        )
        val lifecycleThread = Thread(
            { lifecycleMutation(WidgetPrefs(blockingContext)) },
            lifecycleThreadName
        )
        val selectionThread = Thread(
            {
                WidgetPrefs(blockingContext)
                    .setNotificationWalletSelected("fresh-account", "CNY", true)
            },
            "widget-select"
        )

        lifecycleThread.start()
        assertTrue(lifecycleWriteReady.await(5, TimeUnit.SECONDS))
        selectionThread.start()
        assertTrue(awaitConcurrentWriteOrSharedLock(selectionThread, concurrentWriteObserved))
        resumeLifecycleWrite.countDown()
        lifecycleThread.join(5_000)
        selectionThread.join(5_000)

        assertFalse(lifecycleThread.isAlive)
        assertFalse(selectionThread.isAlive)
        assertFalse(prefs.isNotificationWalletSelected("old-account", "USD"))
        assertTrue(prefs.isNotificationWalletSelected("survivor", "USD"))
        assertTrue(prefs.isNotificationWalletSelected("fresh-account", "CNY"))
        if (expectedMigratedAccountId != null) {
            assertTrue(prefs.isNotificationWalletSelected(expectedMigratedAccountId, "USD"))
        }
    }

    private fun awaitConcurrentWriteOrSharedLock(
        selectionThread: Thread,
        concurrentWriteObserved: CountDownLatch
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (concurrentWriteObserved.count == 0L ||
                selectionThread.state == Thread.State.BLOCKED
            ) {
                return true
            }
            Thread.yield()
        }
        return false
    }

    private class FailFirstWidgetCommitContext(base: Context) : ContextWrapper(base) {
        private var failed = false

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val delegate = baseContext.getSharedPreferences(name, mode)
            if (name != "widget_prefs") return delegate
            return object : SharedPreferences by delegate {
                override fun edit(): SharedPreferences.Editor {
                    val editor = delegate.edit()
                    return object : SharedPreferences.Editor by editor {
                        override fun remove(key: String?): SharedPreferences.Editor {
                            editor.remove(key)
                            return this
                        }

                        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                            editor.putBoolean(key, value)
                            return this
                        }

                        override fun commit(): Boolean {
                            if (!failed) {
                                failed = true
                                return false
                            }
                            return editor.commit()
                        }
                    }
                }
            }
        }
    }

    private class BlockingNotificationOrderContext(
        base: Context,
        private val lifecycleThreadName: String,
        private val lifecycleWriteReady: CountDownLatch,
        private val concurrentWriteObserved: CountDownLatch,
        private val resumeLifecycleWrite: CountDownLatch
    ) : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val delegate = super.getSharedPreferences(name, mode)
            if (name != "widget_prefs") return delegate

            return object : SharedPreferences by delegate {
                override fun edit(): SharedPreferences.Editor {
                    val editor = delegate.edit()
                    return object : SharedPreferences.Editor by editor {
                        override fun putString(
                            key: String?,
                            value: String?
                        ): SharedPreferences.Editor {
                            if (key == WidgetPrefs.KEY_NOTIFICATION_WALLET_ORDER) {
                                when (Thread.currentThread().name) {
                                    lifecycleThreadName -> {
                                        lifecycleWriteReady.countDown()
                                        check(resumeLifecycleWrite.await(5, TimeUnit.SECONDS))
                                    }

                                    "widget-select" -> concurrentWriteObserved.countDown()
                                }
                            }
                            editor.putString(key, value)
                            return this
                        }
                    }
                }
            }
        }
    }

    private class BlockingAlertMigrationContext(
        base: Context,
        private val migrationReady: CountDownLatch,
        private val removalObserved: CountDownLatch,
        private val resumeMigration: CountDownLatch
    ) : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val delegate = super.getSharedPreferences(name, mode)
            if (name != "widget_prefs") return delegate

            return object : SharedPreferences by delegate {
                override fun edit(): SharedPreferences.Editor {
                    val editor = delegate.edit()
                    return object : SharedPreferences.Editor by editor {
                        override fun putBoolean(
                            key: String?,
                            value: Boolean
                        ): SharedPreferences.Editor {
                            if (Thread.currentThread().name == "widget-alert-migrate" &&
                                key == "${WidgetPrefs.KEY_ALERT_ENABLED}_new-account_USD"
                            ) {
                                migrationReady.countDown()
                                check(resumeMigration.await(5, TimeUnit.SECONDS))
                            }
                            editor.putBoolean(key, value)
                            return this
                        }

                        override fun remove(key: String?): SharedPreferences.Editor {
                            if (Thread.currentThread().name == "widget-alert-remove" &&
                                key == "${WidgetPrefs.KEY_ALERT_ENABLED}_new-account_USD"
                            ) {
                                removalObserved.countDown()
                            }
                            editor.remove(key)
                            return this
                        }
                    }
                }
            }
        }
    }
}
