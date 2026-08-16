package com.balancesentinel.app.service

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.DeepSeekApp
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.local.settings.AppSettingsEntity
import com.balancesentinel.app.data.local.settings.NotificationWalletSelectionEntity
import com.balancesentinel.app.data.model.AccountInfo
import com.balancesentinel.app.data.refresh.AccountRefreshResult
import com.balancesentinel.app.data.refresh.AccountStoreRead
import com.balancesentinel.app.data.refresh.RefreshBatchResult
import com.balancesentinel.app.data.refresh.RefreshGateway
import com.balancesentinel.app.data.refresh.RefreshTrigger
import com.balancesentinel.app.data.refresh.deriveRefreshBatchAggregate
import com.balancesentinel.app.data.repository.NotificationHelper
import com.balancesentinel.app.data.repository.SettingsSnapshot
import com.balancesentinel.app.testing.MutableSettingsRepository
import com.balancesentinel.app.widget.AccountBalance
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class PersistentBalanceNotificationPublisherTest {
    private lateinit var context: Context
    private lateinit var notificationHelper: NotificationHelper

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        notificationHelper = NotificationHelper(context)
    }

    @After
    fun tearDown() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancelAll()
    }

    @Test
    fun `cached publication filters deleted accounts and posts retained balance`() = runTest {
        val repository = MutableSettingsRepository(
            SettingsSnapshot(
                appSettings = AppSettingsEntity(
                    showTotalBalanceInNotification = true,
                    updatedAt = 1L,
                    notificationTotalDisplayOrder = 1
                ),
                notificationSelections = listOf(
                    NotificationWalletSelectionEntity("active", "USD", 0)
                )
            )
        )
        val balances = listOf(
            balance("active", "Active", "10.00"),
            balance("deleted", "Deleted", "1000.00")
        )
        val publisher = PersistentBalanceNotificationPublisher(
            context,
            repository,
            notificationHelper,
            committedBalanceReader = { balances }
        )

        val published = publisher.publishCached(gateway(AccountStoreRead.Ready(listOf(account("active")))))

        assertTrue(published)
        val notification = postedNotification()
        assertNotNull(notification)
        val title = notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
        assertTrue(title.contains("Active"))
        assertFalse(title.contains("Deleted"))
    }

    @Test
    fun `missing account snapshot leaves the retained notification unchanged`() = runTest {
        notificationHelper.sendForegroundNotification("Retained", "Balance")
        val publisher = PersistentBalanceNotificationPublisher(
            context,
            MutableSettingsRepository(),
            notificationHelper,
            committedBalanceReader = { listOf(balance("active", "Active", "10.00")) }
        )

        val published = publisher.publishCached(gateway(AccountStoreRead.Missing))

        assertFalse(published)
        assertEquals(
            "Retained",
            postedNotification().extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        )
    }

    private fun postedNotification(): Notification {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return requireNotNull(Shadows.shadowOf(nm).getNotification(DeepSeekApp.NOTIFICATION_ID))
    }

    private fun account(id: String) =
        AccountInfo(id, id, "key-$id", ProviderType.DEEPSEEK, revision = 1)

    private fun balance(accountId: String, label: String, total: String) = AccountBalance(
        accountId = accountId,
        label = label,
        totalBalance = total,
        currency = "USD",
        isAvailable = true,
        grantedBalance = "0.00",
        toppedUpBalance = total,
        lastUpdated = 1L
    )

    private fun gateway(snapshot: AccountStoreRead): RefreshGateway = object : RefreshGateway {
        override suspend fun refreshAccount(accountId: String, trigger: RefreshTrigger) =
            AccountRefreshResult.Skipped(accountId, "unused")

        override suspend fun refreshAll(trigger: RefreshTrigger): RefreshBatchResult =
            RefreshBatchResult("unused", emptyList(), deriveRefreshBatchAggregate(emptyList()))

        override fun invalidate(accountId: String) = Unit

        override suspend fun readAccountSnapshot(): AccountStoreRead = snapshot
    }
}
