package com.balancesentinel.app.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.api.UnifiedBalance
import com.balancesentinel.app.data.api.cache.ProviderCache
import com.balancesentinel.app.data.debug.ApiDebugEntry
import com.balancesentinel.app.data.debug.ApiDebugStore
import com.balancesentinel.app.data.model.AccountDraft
import com.balancesentinel.app.data.model.AccountInfo
import com.balancesentinel.app.data.model.AccountSaveResult
import com.balancesentinel.app.data.model.DailySummary
import com.balancesentinel.app.data.model.RawRecord
import com.balancesentinel.app.data.model.UsageSnapshot
import com.balancesentinel.app.widget.BalanceWidgetDataStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    private lateinit var providerCache: ProviderCache
    private lateinit var widgetPrefs: WidgetPrefs
    private lateinit var accountPrefsName: String

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        accountPrefsName = "account_lifecycle_${System.nanoTime()}"
        accountManager = ApiKeyManager(
            context,
            context.getSharedPreferences(accountPrefsName, Context.MODE_PRIVATE)
        )
        lifecycleManager = AccountLifecycleManager(context, accountManager)
        providerCache = ProviderCache(context)
        widgetPrefs = WidgetPrefs(context)
        clearOwnedStores()
    }

    @After
    fun tearDown() {
        clearOwnedStores()
        context.getSharedPreferences(accountPrefsName, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `key replacement migrates owned state and clears stale caches`() {
        val before = accountManager.addAccount("Before", "sk-before-key")
        seedOwnedState(before)

        val result = lifecycleManager.save(
            before.id,
            AccountDraft(
                label = "After",
                apiKey = "sk-after-key",
                providerType = before.providerType
            )
        )

        val replacement = result as AccountSaveResult.Replaced
        val afterId = replacement.account.id
        assertEquals(1L, replacement.account.revision)
        assertNull(accountManager.getAccount(before.id))
        assertEquals(afterId, accountManager.getAccounts().single().id)
        assertEquals(listOf(afterId), RawRecordStore.getAllRecords(context).map { it.accountId })
        assertEquals(listOf(afterId), DailySummaryStore.getSummaries(context).map { it.accountId })
        assertEquals(listOf(afterId), UsageDataStore.getAllSnapshots(context).map { it.accountId })
        assertEquals(2f, widgetPrefs.getLastAlertedBalance(afterId))
        assertEquals(3f, widgetPrefs.getPreviousBalance(afterId))
        assertEquals(4L, widgetPrefs.getPreviousBalanceTime(afterId))
        assertEquals(5f, widgetPrefs.getLastChangeAlertedBalance(afterId))
        assertEquals(6L, widgetPrefs.getLastChangeAlertedTime(afterId))
        assertTrue(widgetPrefs.isBalanceAlertEnabled(afterId, CURRENCY))
        assertTrue(widgetPrefs.isChangeAlertEnabled(afterId, CURRENCY))
        assertTrue(widgetPrefs.isNotificationWalletSelected(afterId, CURRENCY))
        assertFalse(widgetPrefs.getNotificationWalletOrder().contains("${before.id}_$CURRENCY"))
        assertTrue(BalanceWidgetDataStore.getAllBalances(context).isEmpty())
        assertNull(providerCache.get(before.providerType, before.id))
    }

    @Test
    fun `delete removes only the target account owned state`() {
        val target = accountManager.addAccount("Target", "sk-target-key")
        val survivor = accountManager.addAccount("Survivor", "sk-survivor-key")
        seedOwnedState(target)
        seedOwnedState(survivor)
        ApiDebugStore.addEntry(debugEntry(target.id))
        ApiDebugStore.addEntry(debugEntry(survivor.id))

        lifecycleManager.delete(target.id)

        assertEquals(listOf(survivor.id), accountManager.getAccounts().map { it.id })
        assertEquals(listOf(survivor.id), RawRecordStore.getAllRecords(context).map { it.accountId })
        assertEquals(listOf(survivor.id), DailySummaryStore.getSummaries(context).map { it.accountId })
        assertEquals(listOf(survivor.id), UsageDataStore.getAllSnapshots(context).map { it.accountId })
        assertEquals(-1f, widgetPrefs.getLastAlertedBalance(target.id))
        assertFalse(widgetPrefs.isBalanceAlertEnabled(target.id, CURRENCY))
        assertFalse(widgetPrefs.isChangeAlertEnabled(target.id, CURRENCY))
        assertFalse(widgetPrefs.isNotificationWalletSelected(target.id, CURRENCY))
        assertTrue(widgetPrefs.getNotificationWalletOrder().contains("${survivor.id}_$CURRENCY"))
        assertEquals(listOf(survivor.id), BalanceWidgetDataStore.getAllBalances(context).map { it.accountId })
        assertNull(providerCache.get(target.providerType, target.id))
        assertTrue(providerCache.get(survivor.providerType, survivor.id) != null)
        assertTrue(ApiDebugStore.getEntries(target.id).isEmpty())
        assertEquals(1, ApiDebugStore.getEntries(survivor.id).size)
    }

    @Test
    fun `key collision preserves both accounts and their owned state`() {
        val accountA = accountManager.addAccount("Account A", "sk-account-key-aaaaa")
        val accountB = accountManager.addAccount("Account B", "sk-account-key-bbbbb")
        seedOwnedState(accountA)
        seedOwnedState(accountB)
        val accountsBefore = accountManager.getAccounts()

        val result = lifecycleManager.save(
            accountA.id,
            AccountDraft(
                label = "Account A edited",
                apiKey = accountB.apiKey,
                providerType = accountA.providerType
            )
        )

        assertFalse(result is AccountSaveResult.Replaced)
        assertEquals(accountsBefore, accountManager.getAccounts())
        assertEquals(
            setOf(accountA.id, accountB.id),
            RawRecordStore.getAllRecords(context).map { it.accountId }.toSet()
        )
        assertEquals(
            setOf(accountA.id, accountB.id),
            DailySummaryStore.getSummaries(context).map { it.accountId }.toSet()
        )
        assertEquals(
            setOf(accountA.id, accountB.id),
            UsageDataStore.getAllSnapshots(context).map { it.accountId }.toSet()
        )
        assertTrue(providerCache.get(accountA.providerType, accountA.id) != null)
        assertTrue(providerCache.get(accountB.providerType, accountB.id) != null)
    }

    private fun seedOwnedState(account: AccountInfo) {
        RawRecordStore.addRecord(
            context,
            RawRecord(account.id, 1L, CURRENCY, 1f, 0f, 0f)
        )
        DailySummaryStore.addSummary(context, summary(account.id))
        UsageDataStore.saveSnapshot(context, UsageSnapshot(account.id, 1L))
        widgetPrefs.setLastAlertedBalance(account.id, 2f)
        widgetPrefs.setPreviousBalance(account.id, 3f)
        widgetPrefs.setPreviousBalanceTime(account.id, 4L)
        widgetPrefs.setLastChangeAlertedBalance(account.id, 5f)
        widgetPrefs.setLastChangeAlertedTime(account.id, 6L)
        widgetPrefs.setBalanceAlertEnabled(account.id, CURRENCY, true)
        widgetPrefs.setChangeAlertEnabled(account.id, CURRENCY, true)
        widgetPrefs.setNotificationWalletSelected(account.id, CURRENCY, true)
        BalanceWidgetDataStore.saveAccountBalance(
            context,
            account.id,
            account.label,
            "2",
            CURRENCY,
            true,
            "0",
            "0"
        )
        providerCache.put(account.providerType, account.id, cachedBalance(account))
    }

    private fun summary(accountId: String) = DailySummary(
        accountId = accountId,
        date = "2026-08-01",
        currency = CURRENCY,
        open = 1f,
        close = 1f,
        consumed = 0f,
        toppedUp = 0f,
        avgBalance = 1f,
        sampleCount = 1
    )

    private fun cachedBalance(account: AccountInfo) = UnifiedBalance(
        provider = account.providerType,
        accountId = account.id,
        isAvailable = true,
        balances = emptyList()
    )

    private fun debugEntry(accountId: String) = ApiDebugEntry(
        accountId = accountId,
        url = "https://api.example.com",
        method = "GET",
        requestHeaders = emptyMap(),
        requestBody = null,
        statusCode = 200,
        responseHeaders = emptyMap(),
        responseBody = "{}",
        timestamp = 1L,
        duration = 1L
    )

    private fun clearOwnedStores() {
        RawRecordStore.clear(context)
        DailySummaryStore.clear(context)
        UsageDataStore.clear(context)
        BalanceWidgetDataStore.clearAll(context)
        widgetPrefs.resetAll()
        providerCache.clearAll()
        ApiDebugStore.clearAll()
    }

    private companion object {
        const val CURRENCY = "USD"
    }
}
