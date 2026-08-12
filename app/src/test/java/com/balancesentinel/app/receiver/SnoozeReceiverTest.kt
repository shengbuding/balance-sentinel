package com.balancesentinel.app.receiver

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import android.app.NotificationManager
import com.balancesentinel.app.data.repository.NotificationHelper
import com.balancesentinel.app.data.local.settings.AppSettingsEntity
import com.balancesentinel.app.data.repository.SettingsRepositoryProvider
import com.balancesentinel.app.data.repository.SettingsSnapshot
import com.balancesentinel.app.testing.MutableSettingsRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.robolectric.Shadows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SnoozeReceiverTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var repository: MutableSettingsRepository

    @Before
    fun setUp() {
        repository = MutableSettingsRepository()
        SettingsRepositoryProvider.factory = { repository }
    }

    @After
    fun tearDown() {
        SettingsRepositoryProvider.resetForTests()
    }

    @Test
    fun `onReceive with accountId does not throw`() {
        val receiver = SnoozeReceiver()
        val intent = Intent().apply {
            putExtra("account_id", "test-account-123")
        }
        receiver.onReceive(context, intent)
    }

    @Test
    fun `onReceive without accountId is no-op`() {
        val receiver = SnoozeReceiver()
        val intent = Intent() // no account_id
        receiver.onReceive(context, intent)
    }
    @Test
    fun `snooze persists an account level state through settings repository`() = runBlocking {
        repository = MutableSettingsRepository(
            SettingsSnapshot(AppSettingsEntity(snoozeDurationMinutes = 10, updatedAt = 0L))
        )
        SettingsRepositoryProvider.factory = { repository }
        val before = System.currentTimeMillis()
        SnoozeReceiver().onReceive(context, Intent().putExtra("account_id", "persisted-account"))

        val deadline = System.currentTimeMillis() + 2_000L
        while (repository.readSnapshot().snoozeUntil("persisted-account") <= before &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(10)
        }

        assertTrue(repository.readSnapshot().snoozeUntil("persisted-account") >= before + 600_000L)
    }
    @Test
    fun `snoozing one currency does not cancel another currency notification`() {
        val helper = NotificationHelper(context)
        helper.sendLowBalanceAlert("test-account-123", 10f, 20f, "CNY", "Main")
        helper.sendLowBalanceAlert("test-account-123", 10f, 20f, "USD", "Main")
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadow = Shadows.shadowOf(manager)
        val cnyId = helper.alertNotificationId("test-account-123", "CNY")
        val usdId = helper.alertNotificationId("test-account-123", "USD")
        assertNotNull(shadow.getNotification(cnyId))
        assertNotNull(shadow.getNotification(usdId))

        SnoozeReceiver().onReceive(context, Intent().apply {
            putExtra("account_id", "test-account-123")
            putExtra("deep_link_currency", "CNY")
        })

        assertNull(shadow.getNotification(cnyId))
        assertNotNull(shadow.getNotification(usdId))
        manager.cancelAll()
    }

}
