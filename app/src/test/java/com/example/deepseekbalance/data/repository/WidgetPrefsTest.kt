package com.example.deepseekbalance.data.repository

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
}
