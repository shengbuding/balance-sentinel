package com.balancesentinel.app.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.debug.ApiDebugEntry
import com.balancesentinel.app.data.debug.ApiDebugStore
import com.balancesentinel.app.data.model.AccountDraft
import com.balancesentinel.app.data.model.DailySummary
import com.balancesentinel.app.data.model.RawRecord
import com.balancesentinel.app.data.model.UsageSnapshot
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.api.UnifiedBalance
import com.balancesentinel.app.data.api.cache.ProviderCache
import com.balancesentinel.app.widget.BalanceWidgetDataStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AccountLifecycleManagerTest {

    private lateinit var context: Context
    private lateinit var accountManager: ApiKeyManager
    private lateinit var lifecycleManager: AccountLifecycleManager
    private lateinit var widgetPrefs: WidgetPrefs
    private lateinit var providerCache: ProviderCache
    private lateinit var prefsName: String

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefsName = "account_lifecycle_${System.nanoTime()}"
        accountManager = ApiKeyManager(context, context.getSharedPreferences(prefsName, Context.MODE_PRIVATE))
        widgetPrefs = WidgetPrefs(context).also { it.resetAll() }
        providerCache = ProviderCache(context).also { it.clearAll() }
        lifecycleManager = AccountLifecycleManager(context, accountManager, widgetPrefs, providerCache)
    }

    @After
    fun tearDown() {
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().commit()
        RawRecordStore.clear(context)
        DailySummaryStore.clear(context)
        UsageDataStore.clear(context)
        BalanceWidgetDataStore.clearAll(context)
        widgetPrefs.resetAll()
        providerCache.clearAll()
        ApiDebugStore.clearAll()
    }

    @Test
    fun `save migrates history and usage when replacing account key`() {
        val before = accountManager.addAccount("Before", "sk-before-key")
        RawRecordStore.addRecord(context, RawRecord(before.id, 1L, "USD", 1f, 0f, 0f))
        DailySummaryStore.addSummary(context, summary(before.id))
        UsageDataStore.saveSnapshot(context, UsageSnapshot(before.id, 1L))
        widgetPrefs.setLastAlertedBalance(before.id, 2f)
        widgetPrefs.setBalanceAlertEnabled(before.id, "USD", true)
        widgetPrefs.setChangeAlertEnabled(before.id, "USD", true)
        widgetPrefs.setNotificationWalletSelected(before.id, "USD", true)
        BalanceWidgetDataStore.saveAccountBalance(context, before.id, "Before", "2", "USD", true, "0", "0")
        providerCache.put(ProviderType.DEEPSEEK, before.id, cachedBalance(before.id))

        val result = lifecycleManager.save(
            before.id,
            AccountDraft(
                label = "After",
                apiKey = "sk-after-key",
                providerType = before.providerType,
                extraCredentials = emptyMap(),
                extraSettings = emptyMap(),
                usageScript = null,
                usageScriptEnabled = true,
                authorizedScriptOrigins = emptySet()
            )
        )

        val afterId = (result as AccountSaveResult.Replaced).account.id
        assertEquals(listOf(afterId), RawRecordStore.getAllRecords(context).map { it.accountId })
        assertEquals(listOf(afterId), DailySummaryStore.getSummaries(context).map { it.accountId })
        assertEquals(listOf(afterId), UsageDataStore.getAllSnapshots(context).map { it.accountId })
        assertEquals(2f, widgetPrefs.getLastAlertedBalance(afterId))
        assertTrue(widgetPrefs.isBalanceAlertEnabled(afterId, "USD"))
        assertTrue(widgetPrefs.isChangeAlertEnabled(afterId, "USD"))
        assertTrue(widgetPrefs.isNotificationWalletSelected(afterId, "USD"))
        assertTrue(BalanceWidgetDataStore.getAllBalances(context).isEmpty())
        assertEquals(null, providerCache.get(ProviderType.DEEPSEEK, before.id))
    }

    @Test
    fun `delete removes account history usage and debug state`() {
        val account = accountManager.addAccount("Account", "sk-delete-key")
        RawRecordStore.addRecord(context, RawRecord(account.id, 1L, "USD", 1f, 0f, 0f))
        DailySummaryStore.addSummary(context, summary(account.id))
        UsageDataStore.saveSnapshot(context, UsageSnapshot(account.id, 1L))
        widgetPrefs.setLastAlertedBalance(account.id, 2f)
        widgetPrefs.setBalanceAlertEnabled(account.id, "USD", true)
        widgetPrefs.setNotificationWalletSelected(account.id, "USD", true)
        BalanceWidgetDataStore.saveAccountBalance(context, account.id, "Account", "2", "USD", true, "0", "0")
        providerCache.put(ProviderType.DEEPSEEK, account.id, cachedBalance(account.id))
        ApiDebugStore.addEntry(
            ApiDebugEntry(account.id, "url", "GET", emptyMap(), null, 200, emptyMap(), "", 1L, 1L)
        )

        lifecycleManager.delete(account.id)

        assertTrue(accountManager.getAccounts().isEmpty())
        assertTrue(RawRecordStore.getAllRecords(context).isEmpty())
        assertTrue(DailySummaryStore.getSummaries(context).isEmpty())
        assertTrue(UsageDataStore.getAllSnapshots(context).isEmpty())
        assertEquals(-1f, widgetPrefs.getLastAlertedBalance(account.id))
        assertFalse(widgetPrefs.isBalanceAlertEnabled(account.id, "USD"))
        assertFalse(widgetPrefs.isNotificationWalletSelected(account.id, "USD"))
        assertTrue(BalanceWidgetDataStore.getAllBalances(context).isEmpty())
        assertEquals(null, providerCache.get(ProviderType.DEEPSEEK, account.id))
        assertTrue(ApiDebugStore.getEntries(account.id).isEmpty())
    }

    private fun summary(accountId: String) = DailySummary(
        accountId = accountId,
        date = "2026-08-01",
        currency = "USD",
        open = 1f,
        close = 1f,
        consumed = 0f,
        toppedUp = 0f,
        avgBalance = 1f,
        sampleCount = 1
    )

    private fun cachedBalance(accountId: String) = UnifiedBalance(
        provider = ProviderType.DEEPSEEK,
        accountId = accountId,
        isAvailable = true,
        balances = emptyList()
    )
}
