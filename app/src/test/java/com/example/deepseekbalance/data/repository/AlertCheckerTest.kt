package com.example.deepseekbalance.data.repository

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class AlertCheckerTest {

    private lateinit var context: Context
    private lateinit var prefs: WidgetPrefs

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = WidgetPrefs(context)

        // Reset all prefs to clean state
        prefs.alertEnabled = false
        prefs.alertThreshold = 0f
        prefs.changeAlertEnabled = false
        prefs.changeAlertThreshold = 0f
        prefs.changeAlertPeriodMinutes = 0
        // Clear any lingering per-account state
        prefs.setLastAlertedBalance("acc1", -1f)
        prefs.setLastAlertedBalance("acc2", -1f)
        prefs.setPreviousBalance("acc1", -1f)
        prefs.setPreviousBalance("acc2", -1f)
        prefs.setPreviousBalanceTime("acc1", 0L)
        prefs.setPreviousBalanceTime("acc2", 0L)
        prefs.setLastChangeAlertedBalance("acc1", -1f)
        prefs.setLastChangeAlertedBalance("acc2", -1f)
        prefs.setLastChangeAlertedTime("acc1", 0L)
        prefs.setLastChangeAlertedTime("acc2", 0L)
    }

    @After
    fun tearDown() {
        // Reset prefs
        prefs.alertEnabled = false
        prefs.alertThreshold = 0f
        prefs.changeAlertEnabled = false
        prefs.changeAlertThreshold = 0f
        prefs.changeAlertPeriodMinutes = 0
    }

    private fun notificationCount(): Int {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadow = Shadows.shadowOf(nm)
        return shadow.allNotifications.size
    }

    private fun clearNotifications() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancelAll()
    }

    // ── Threshold Alert Tests ──

    @Test
    fun `check does nothing when alert is disabled`() {
        prefs.alertEnabled = false
        prefs.alertThreshold = 100f

        AlertChecker.check(context, "acc1", "50", "CNY", "Test")
        assertEquals(0, notificationCount())
    }

    @Test
    fun `check does nothing when threshold is zero`() {
        prefs.alertEnabled = true
        prefs.alertThreshold = 0f

        AlertChecker.check(context, "acc1", "50", "CNY", "Test")
        assertEquals(0, notificationCount())
    }

    @Test
    fun `check does nothing when balance is above threshold`() {
        prefs.alertEnabled = true
        prefs.alertThreshold = 50f

        AlertChecker.check(context, "acc1", "100", "CNY", "Test")
        assertEquals(0, notificationCount())
    }

    @Test
    fun `check sends notification when balance drops below threshold`() {
        prefs.alertEnabled = true
        prefs.alertThreshold = 50f

        AlertChecker.check(context, "acc1", "30", "CNY", "Test")
        assertEquals(1, notificationCount())
    }

    @Test
    fun `check does not resend for same balance`() {
        prefs.alertEnabled = true
        prefs.alertThreshold = 50f

        AlertChecker.check(context, "acc1", "30", "CNY", "Test")
        assertEquals(1, notificationCount())

        // Same balance again — should be deduplicated
        AlertChecker.check(context, "acc1", "30", "CNY", "Test")
        assertEquals(1, notificationCount())
    }

    @Test
    fun `check resets after balance recovers above threshold`() {
        prefs.alertEnabled = true
        prefs.alertThreshold = 50f

        // First drop triggers alert
        AlertChecker.check(context, "acc1", "30", "CNY", "Test")
        assertEquals(1, notificationCount())

        // Clear notifications to track the next one separately
        clearNotifications()

        // Recovery above threshold — should NOT trigger an alert
        AlertChecker.check(context, "acc1", "100", "CNY", "Test")
        assertEquals(0, notificationCount())

        // Drop again — should re-alert since it recovered and dropped again
        AlertChecker.check(context, "acc1", "25", "CNY", "Test")
        assertEquals(1, notificationCount())
    }

    @Test
    fun `check isolates dedup state per account`() {
        prefs.alertEnabled = true
        prefs.alertThreshold = 50f

        AlertChecker.check(context, "acc1", "30", "CNY", "A1")
        assertEquals(1, notificationCount())

        // Clear to track the second account's notification independently
        clearNotifications()

        // Different account — should also alert (not deduplicated against acc1)
        AlertChecker.check(context, "acc2", "30", "CNY", "A2")
        assertEquals(1, notificationCount())
    }

    @Test
    fun `check handles invalid balance string gracefully`() {
        prefs.alertEnabled = true
        prefs.alertThreshold = 50f

        // Should not crash
        AlertChecker.check(context, "acc1", "not_a_number", "CNY", "Test")
        assertEquals(0, notificationCount())
    }

    // ── Change Alert Tests ──

    @Test
    fun `checkChange does nothing when disabled`() {
        prefs.changeAlertEnabled = false
        prefs.changeAlertThreshold = 10f
        prefs.changeAlertPeriodMinutes = 60
        prefs.setPreviousBalance("acc1", 100f)
        prefs.setPreviousBalanceTime("acc1", System.currentTimeMillis() - 60_000)

        AlertChecker.checkChange(context, "acc1", "80", "CNY", "Test")
        assertEquals(0, notificationCount())
    }

    @Test
    fun `checkChange stores initial balance without alerting`() {
        prefs.changeAlertEnabled = true
        prefs.changeAlertThreshold = 10f
        prefs.changeAlertPeriodMinutes = 60

        AlertChecker.checkChange(context, "acc1", "100", "CNY", "Test")
        assertEquals(0, notificationCount())
        assertEquals(100f, prefs.getPreviousBalance("acc1"))
    }

    @Test
    fun `checkChange sends notification when change exceeds threshold within period`() {
        prefs.changeAlertEnabled = true
        prefs.changeAlertThreshold = 10f
        prefs.changeAlertPeriodMinutes = 60

        // First call stores initial value
        AlertChecker.checkChange(context, "acc1", "100", "CNY", "Test")
        assertEquals(0, notificationCount())

        // Set previous time to simulate elapsed time within period
        prefs.setPreviousBalanceTime("acc1", System.currentTimeMillis() - 30_000)

        // Second call with different balance
        AlertChecker.checkChange(context, "acc1", "80", "CNY", "Test")
        assertEquals(1, notificationCount())
    }

    @Test
    fun `checkChange does not fire when change is below threshold`() {
        prefs.changeAlertEnabled = true
        prefs.changeAlertThreshold = 50f
        prefs.changeAlertPeriodMinutes = 60

        AlertChecker.checkChange(context, "acc1", "100", "CNY", "Test")
        assertEquals(0, notificationCount())

        prefs.setPreviousBalanceTime("acc1", System.currentTimeMillis() - 30_000)
        AlertChecker.checkChange(context, "acc1", "95", "CNY", "Test")
        assertEquals(0, notificationCount())
    }

    @Test
    fun `checkChange deduplicates within same period`() {
        prefs.changeAlertEnabled = true
        prefs.changeAlertThreshold = 10f
        prefs.changeAlertPeriodMinutes = 60

        // Store initial
        AlertChecker.checkChange(context, "acc1", "100", "CNY", "Test")

        // First change triggers alert
        prefs.setPreviousBalanceTime("acc1", System.currentTimeMillis() - 30_000)
        AlertChecker.checkChange(context, "acc1", "80", "CNY", "Test")
        assertEquals(1, notificationCount())

        // Same balance again within period — dedup
        prefs.setPreviousBalanceTime("acc1", System.currentTimeMillis() - 15_000)
        AlertChecker.checkChange(context, "acc1", "80", "CNY", "Test")
        assertEquals(1, notificationCount())
    }

    @Test
    fun `checkChange handles invalid balance`() {
        prefs.changeAlertEnabled = true
        prefs.changeAlertThreshold = 10f
        prefs.changeAlertPeriodMinutes = 60

        // Should not crash
        AlertChecker.checkChange(context, "acc1", "invalid", "CNY", "Test")
        assertEquals(0, notificationCount())
    }

    @Test
    fun `checkChange ignores change when period exceeded`() {
        prefs.changeAlertEnabled = true
        prefs.changeAlertThreshold = 10f
        prefs.changeAlertPeriodMinutes = 1

        // Store initial
        AlertChecker.checkChange(context, "acc1", "100", "CNY", "Test")
        assertEquals(0, notificationCount())

        // 5 minutes later — outside the 1-minute period
        prefs.setPreviousBalanceTime("acc1", System.currentTimeMillis() - 300_000)
        AlertChecker.checkChange(context, "acc1", "80", "CNY", "Test")

        // Should not alert because elapsed time exceeds period (anchor resets)
        assertEquals(0, notificationCount())
    }

    @Test
    fun `checkChange detects cumulative gradual change within window`() {
        prefs.changeAlertEnabled = true
        prefs.changeAlertThreshold = 10f
        prefs.changeAlertPeriodMinutes = 60

        // Step 1: First call stores anchor at 100
        AlertChecker.checkChange(context, "acc1", "100", "CNY", "Test")
        assertEquals(0, notificationCount())
        assertEquals(100f, prefs.getPreviousBalance("acc1"))

        // Step 2: Small drop — diff=3, below threshold
        prefs.setPreviousBalanceTime("acc1", System.currentTimeMillis() - 10_000)
        AlertChecker.checkChange(context, "acc1", "97", "CNY", "Test")
        assertEquals(0, notificationCount())
        // Anchor stays at original value (100), not updated to 97
        assertEquals(100f, prefs.getPreviousBalance("acc1"))

        // Step 3: Another small drop — cumulative diff=7, still below threshold
        prefs.setPreviousBalanceTime("acc1", System.currentTimeMillis() - 20_000)
        AlertChecker.checkChange(context, "acc1", "93", "CNY", "Test")
        assertEquals(0, notificationCount())
        // Anchor STILL at 100
        assertEquals(100f, prefs.getPreviousBalance("acc1"))

        // Step 4: Third drop — cumulative diff=12 >= 10 → ALERT
        prefs.setPreviousBalanceTime("acc1", System.currentTimeMillis() - 30_000)
        AlertChecker.checkChange(context, "acc1", "88", "CNY", "Test")
        assertEquals(1, notificationCount())
    }

    @Test
    fun `checkChange anchor resets after alert fires`() {
        prefs.changeAlertEnabled = true
        prefs.changeAlertThreshold = 10f
        prefs.changeAlertPeriodMinutes = 60

        // Store initial anchor
        AlertChecker.checkChange(context, "acc1", "100", "CNY", "Test")

        // Trigger alert: diff=20 >= 10
        prefs.setPreviousBalanceTime("acc1", System.currentTimeMillis() - 30_000)
        AlertChecker.checkChange(context, "acc1", "80", "CNY", "Test")
        assertEquals(1, notificationCount())

        // After alert, anchor should be reset to 80
        assertEquals(80f, prefs.getPreviousBalance("acc1"))

        // Small further drop from new anchor (80→77, diff=3 < 10): no alert
        prefs.setPreviousBalanceTime("acc1", System.currentTimeMillis() - 10_000)
        AlertChecker.checkChange(context, "acc1", "77", "CNY", "Test")
        assertEquals(1, notificationCount())  // still just 1, no new alert
    }
}
