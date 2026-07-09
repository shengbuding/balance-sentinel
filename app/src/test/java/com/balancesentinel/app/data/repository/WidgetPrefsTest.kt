package com.balancesentinel.app.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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
        prefs.removeAccountCurrencyAlertState("acc1", "CNY")
        assertFalse(prefs.isBalanceAlertEnabled("acc1", "CNY"))
        assertTrue(prefs.isBalanceAlertEnabled("acc1", "USD"))
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
}
