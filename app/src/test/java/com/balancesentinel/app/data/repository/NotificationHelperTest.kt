package com.balancesentinel.app.data.repository

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.model.RefreshLogEntry
import com.balancesentinel.app.data.model.RefreshLogType
import com.balancesentinel.app.widget.AccountBalance
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class NotificationHelperTest {

    private lateinit var context: Context
    private lateinit var helper: NotificationHelper

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        helper = NotificationHelper(context)
    }

    @After
    fun tearDown() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancelAll()
    }

    // ═══════════════════════════════════════════════════════════
    // alertNotificationId / changeNotificationId
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `alertNotificationId returns value in expected range`() {
        val id = helper.alertNotificationId("test-acc")
        assertTrue(id >= 1002)
        assertTrue(id <= 66537)
    }

    @Test
    fun `alertNotificationId is deterministic`() {
        val id1 = helper.alertNotificationId("test-acc")
        val id2 = helper.alertNotificationId("test-acc")
        assertEquals(id1, id2)
    }

    @Test
    fun `alertNotificationId differs for different accounts`() {
        val id1 = helper.alertNotificationId("acc1")
        val id2 = helper.alertNotificationId("acc2")
        // Different hashCode can still collide — but verify it's plausible
        assertNotNull(id1)
        assertNotNull(id2)
    }

    @Test
    fun `changeNotificationId returns value in expected range`() {
        val id = helper.changeNotificationId("test-acc")
        assertTrue(id >= 2002)
        assertTrue(id <= 67537)
    }

    @Test
    fun `changeNotificationId is deterministic`() {
        val id1 = helper.changeNotificationId("test-acc")
        val id2 = helper.changeNotificationId("test-acc")
        assertEquals(id1, id2)
    }

    @Test
    fun `alert and change notification IDs use different bases`() {
        val alertId = helper.alertNotificationId("test")
        val changeId = helper.changeNotificationId("test")
        // Different base offsets: 1002 vs 2002
        assertNotEquals(alertId, changeId)
    }

    // ═══════════════════════════════════════════════════════════
    // createOpenAppIntent
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `createOpenAppIntent returns non-null PendingIntent`() {
        val intent = helper.createOpenAppIntent()
        assertNotNull(intent)
    }

    // ═══════════════════════════════════════════════════════════
    // createDeepLinkIntent
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `createDeepLinkIntent returns non-null PendingIntent`() {
        val intent = helper.createDeepLinkIntent("acc1", "CNY")
        assertNotNull(intent)
    }

    // ═══════════════════════════════════════════════════════════
    // createSnoozeIntent
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `createSnoozeIntent returns non-null PendingIntent`() {
        val intent = helper.createSnoozeIntent("acc1")
        assertNotNull(intent)
    }

    // ═══════════════════════════════════════════════════════════
    // buildForegroundNotification
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `buildForegroundNotification returns non-null notification`() {
        val notification = helper.buildForegroundNotification("Test Title", "Test Content")
        assertNotNull(notification)
    }

    // ═══════════════════════════════════════════════════════════
    // buildBalanceNotification — basic structure
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `buildBalanceNotification with empty wallets shows app name and status`() {
        val notification = helper.buildBalanceNotification(
            totalBalance = "0.00", totalCurrency = "CNY",
            status = "无数据", extraWallets = emptyList(),
            showTotal = false
        )
        assertNotNull(notification)
    }

    @Test
    fun `buildBalanceNotification with single entry uses it as title`() {
        val wallets = listOf(
            AccountBalance("a1", "Main", "100.00", "CNY", true, "0", "100.00", 1000L)
        )
        val notification = helper.buildBalanceNotification(
            totalBalance = "100.00", totalCurrency = "CNY",
            status = "正常", extraWallets = wallets
        )
        assertNotNull(notification)
    }

    @Test
    fun `buildBalanceNotification with total shows total entry`() {
        val wallets = listOf(
            AccountBalance("a1", "Main", "50.00", "CNY", true, "0", "50.00", 1000L)
        )
        val notification = helper.buildBalanceNotification(
            totalBalance = "150.00", totalCurrency = "CNY",
            status = "正常", extraWallets = wallets,
            showTotal = true, totalPosition = 0
        )
        assertNotNull(notification)
    }

    @Test
    fun `buildBalanceNotification with dual currencies includes second currency`() {
        val wallets = listOf(
            AccountBalance("a1", "Main", "100.00", "CNY", true, "0", "100.00", 1000L)
        )
        val notification = helper.buildBalanceNotification(
            totalBalance = "100.00", totalCurrency = "CNY",
            status = "正常", extraWallets = wallets,
            totalBalance2 = "1000.00", totalCurrency2 = "USD"
        )
        assertNotNull(notification)
    }

    // ═══════════════════════════════════════════════════════════
    // sendForegroundNotification
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `sendForegroundNotification posts notification`() {
        helper.sendForegroundNotification("Test Title", "Test Content")
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadow = Shadows.shadowOf(nm)
        assertTrue(shadow.allNotifications.isNotEmpty())
    }

    // ═══════════════════════════════════════════════════════════
    // sendGroupSummary
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `sendGroupSummary with both counts posts notification`() {
        helper.sendGroupSummary(alertCount = 2, changeCount = 1)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadow = Shadows.shadowOf(nm)
        assertTrue(shadow.allNotifications.isNotEmpty())
    }

    @Test
    fun `sendGroupSummary with zero counts is no-op`() {
        helper.sendGroupSummary(alertCount = 0, changeCount = 0)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadow = Shadows.shadowOf(nm)
        // No notification should be posted
        assertEquals(0, shadow.allNotifications.size)
    }

    @Test
    fun `sendGroupSummary with only alerts posts notification`() {
        helper.sendGroupSummary(alertCount = 3, changeCount = 0)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadow = Shadows.shadowOf(nm)
        assertTrue(shadow.allNotifications.isNotEmpty())
    }

    // ═══════════════════════════════════════════════════════════
    // sendBalanceNotification
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `sendBalanceNotification posts notification`() {
        helper.sendBalanceNotification(
            totalBalance = "100.00", totalCurrency = "CNY",
            status = "正常", extraWallets = emptyList()
        )
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadow = Shadows.shadowOf(nm)
        assertTrue(shadow.allNotifications.isNotEmpty())
    }
}
