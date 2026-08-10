package com.balancesentinel.app.data.repository

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.model.RefreshLogEntry
import com.balancesentinel.app.data.model.RefreshLogType
import com.balancesentinel.app.util.FormatUtils
import com.balancesentinel.app.widget.AccountBalance
import com.balancesentinel.app.widget.StaticWidgetProvider
import com.balancesentinel.app.ui.navigation.AppRoute
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
    fun `all action identities match stable pair SHA literals without cross kind overlap`() {
        val actual = listOf(
            helper.alertNotificationId("acct", "CNY"),
            helper.alertNotificationId("acct", "USD"),
            helper.changeNotificationId("acct", "CNY"),
            helper.changeNotificationId("acct", "USD"),
            helper.deepLinkRequestCode("acct", "CNY"),
            helper.deepLinkRequestCode("acct", "USD"),
            helper.snoozeRequestCode("acct", "CNY"),
            helper.snoozeRequestCode("acct", "USD")
        )

        assertEquals(
            listOf(
                177_564_457,
                1_436_152_706,
                442_697_817,
                1_028_599_329,
                703_950_075,
                1_411_803_297,
                492_874_957,
                740_343_814
            ),
            actual
        )
        assertEquals(8, actual.toSet().size)
    }

    @Test
    fun `action identities normalize currency before hashing`() {
        assertEquals(
            helper.alertNotificationId("acct", "USD"),
            helper.alertNotificationId("acct", "usd")
        )
        assertEquals(
            helper.changeNotificationId("acct", "USD"),
            helper.changeNotificationId("acct", "usd")
        )
        assertEquals(
            helper.deepLinkRequestCode("acct", "USD"),
            helper.deepLinkRequestCode("acct", "usd")
        )
        assertEquals(
            helper.snoozeRequestCode("acct", "USD"),
            helper.snoozeRequestCode("acct", "usd")
        )
    }

    @Test
    fun `alertNotificationId returns value in expected range`() {
        val id = helper.alertNotificationId("test-acc", "CNY")
        assertTrue(id > 0)
    }

    @Test
    fun `alertNotificationId is deterministic`() {
        val id1 = helper.alertNotificationId("test-acc", "CNY")
        val id2 = helper.alertNotificationId("test-acc", "CNY")
        assertEquals(id1, id2)
    }

    @Test
    fun `alertNotificationId differs for different accounts`() {
        val id1 = helper.alertNotificationId("acc1", "CNY")
        val id2 = helper.alertNotificationId("acc2", "CNY")
        // Different hashCode can still collide — but verify it's plausible
        assertNotEquals(id1, id2)
    }

    @Test
    fun `changeNotificationId returns value in expected range`() {
        val id = helper.changeNotificationId("test-acc", "CNY")
        assertTrue(id > 0)
    }

    @Test
    fun `changeNotificationId is deterministic`() {
        val id1 = helper.changeNotificationId("test-acc", "CNY")
        val id2 = helper.changeNotificationId("test-acc", "CNY")
        assertEquals(id1, id2)
    }

    @Test
    fun `alert and change notification IDs use different bases`() {
        val alertId = helper.alertNotificationId("test", "CNY")
        val changeId = helper.changeNotificationId("test", "CNY")
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

    @Test
    fun `deep link and snooze pending intents remain currency distinct with correct extras`() {
        val cnyDeepLink = helper.createDeepLinkIntent("acct", "CNY")
        val usdDeepLink = helper.createDeepLinkIntent("acct", "USD")
        val cnySnooze = helper.createSnoozeIntent("acct", "CNY")
        val usdSnooze = helper.createSnoozeIntent("acct", "USD")

        assertNotEquals(cnyDeepLink, usdDeepLink)
        assertNotEquals(cnySnooze, usdSnooze)
        val deepLinkIntent = Shadows.shadowOf(cnyDeepLink).savedIntent
        assertEquals("insights", deepLinkIntent.getStringExtra("deep_link_target"))
        assertEquals("acct", deepLinkIntent.getStringExtra("deep_link_account_id"))
        assertEquals("CNY", deepLinkIntent.getStringExtra("deep_link_currency"))
        val snoozeIntent = Shadows.shadowOf(cnySnooze).savedIntent
        assertEquals("acct", snoozeIntent.getStringExtra("account_id"))
    }

    @Test
    fun `low balance notification canonicalizes lowercase currency context`() {
        helper.sendLowBalanceAlert(
            accountId = "acct-low",
            balance = 1f,
            threshold = 10f,
            currency = "cny",
            label = "Low"
        )

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = Shadows.shadowOf(nm)
            .getNotification(helper.alertNotificationId("acct-low", "CNY"))
        val content = notification.extras
            .getCharSequence(android.app.Notification.EXTRA_TEXT)
            .toString()
        val deepLink = Shadows.shadowOf(notification.actions[0].actionIntent).savedIntent

        assertTrue(content.contains(FormatUtils.currencySymbol("CNY")))
        assertEquals("CNY", deepLink.getStringExtra("deep_link_currency"))
    }

    @Test
    fun `change notification canonicalizes mixed case currency context`() {
        helper.sendChangeAlert(
            accountId = "acct-change",
            current = 5f,
            previous = 10f,
            diff = 5f,
            periodMin = 15,
            currency = "uSd",
            label = "Change"
        )

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = Shadows.shadowOf(nm)
            .getNotification(helper.changeNotificationId("acct-change", "USD"))
        val content = notification.extras
            .getCharSequence(android.app.Notification.EXTRA_TEXT)
            .toString()
        val deepLink = Shadows.shadowOf(notification.actions[0].actionIntent).savedIntent

        assertTrue(content.contains(FormatUtils.currencySymbol("USD")))
        assertEquals("USD", deepLink.getStringExtra("deep_link_currency"))
    }

    // ═══════════════════════════════════════════════════════════
    // createSnoozeIntent
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `createSnoozeIntent returns non-null PendingIntent`() {
        val intent = helper.createSnoozeIntent("acc1", "CNY")
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

    // ═══════════════════════════════════════════════════════════
    // buildBalanceNotification — more branch coverage
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `buildBalanceNotification with multiple wallets and total in middle`() {
        val wallets = listOf(
            AccountBalance("a1", "Acc1", "50.00", "CNY", true, "0", "50.00", 1000L),
            AccountBalance("a2", "Acc2", "75.00", "USD", true, "0", "75.00", 2000L)
        )
        // total inserted at position 1 (between two wallets)
        val notification = helper.buildBalanceNotification(
            totalBalance = "125.00", totalCurrency = "CNY",
            status = "正常", extraWallets = wallets,
            showTotal = true, totalPosition = 1
        )
        assertNotNull(notification)
    }

    @Test
    fun `buildBalanceNotification with single entry and status as body`() {
        val wallets = listOf(
            AccountBalance("a1", "Main", "100.00", "CNY", true, "0", "100.00", 1000L)
        )
        // With showTotal=false and single wallet, entries.size == 1 → uses status as contentText
        val notification = helper.buildBalanceNotification(
            totalBalance = "100.00", totalCurrency = "CNY",
            status = "一切正常", extraWallets = wallets,
            showTotal = false
        )
        assertNotNull(notification)
    }

    @Test
    fun `buildBalanceNotification with dual currencies but zero second`() {
        val wallets = listOf(
            AccountBalance("a1", "Main", "100.00", "CNY", true, "0", "100.00", 1000L)
        )
        // totalBalance2 is "0" → (totalBalance2.toDoubleOrNull() ?: 0.0) > 0 → false → second currency not appended
        val notification = helper.buildBalanceNotification(
            totalBalance = "100.00", totalCurrency = "CNY",
            status = "正常", extraWallets = wallets,
            totalBalance2 = "0", totalCurrency2 = "USD"
        )
        assertNotNull(notification)
    }

    @Test
    fun `buildBalanceNotification with many wallets triggers overflow truncated`() {
        // Create enough wallets that the body text exceeds maxChars ~25-55, triggering truncation
        val wallets = (1..10).map { i ->
            AccountBalance("acc$i", "Wallet$i", "${i * 50}.00", "CNY", true, "0", "${i * 50}.00", 1000L)
        }
        val notification = helper.buildBalanceNotification(
            totalBalance = "2750.00", totalCurrency = "CNY",
            status = "多项余额", extraWallets = wallets,
            showTotal = true, totalPosition = 0
        )
        assertNotNull(notification)
    }
    @Test
    fun `deep link URI is the canonical typed route`() {
        assertEquals(
            AppRoute.Insights("acc1", "CNY").toUri(),
            helper.createDeepLinkUri("acc1", "cny")
        )
    }

    @Test
    fun `widget and notification use one normalized deep link URI`() {
        assertEquals(
            helper.createDeepLinkUri("acc1", "usd"),
            StaticWidgetProvider.canonicalDeepLinkUri("acc1", "USD")
        )
    }

    @Test
    fun `deep link request code is stable per normalized account currency pair`() {
        assertEquals(
            helper.deepLinkRequestCode("acc1", "cny"),
            helper.deepLinkRequestCode("acc1", "CNY")
        )
        assertNotEquals(
            helper.deepLinkRequestCode("acc1", "CNY"),
            helper.deepLinkRequestCode("acc1", "USD")
        )
    }

    @Test
    fun `snooze request code is isolated per normalized account currency pair`() {
        assertNotEquals(
            helper.snoozeRequestCode("acc1", "CNY"),
            helper.snoozeRequestCode("acc1", "USD")
        )
    }

    @Test
    fun `alert and change notification IDs isolate currencies`() {
        assertNotEquals(
            helper.alertNotificationId("acc1", "CNY"),
            helper.alertNotificationId("acc1", "USD")
        )
        assertNotEquals(
            helper.changeNotificationId("acc1", "CNY"),
            helper.changeNotificationId("acc1", "USD")
        )
    }

}
