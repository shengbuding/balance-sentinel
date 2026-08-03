package com.balancesentinel.app.data.repository

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.api.UnifiedBalance
import com.balancesentinel.app.data.api.cache.ProviderCache
import com.balancesentinel.app.data.debug.ApiDebugEntry
import com.balancesentinel.app.data.debug.ApiDebugStore
import com.balancesentinel.app.data.model.AccountDraft
import com.balancesentinel.app.data.model.AccountInfo
import com.balancesentinel.app.data.model.AccountSaveResult
import com.balancesentinel.app.data.refresh.RefreshGateway
import com.balancesentinel.app.data.refresh.RefreshTrigger
import com.balancesentinel.app.data.model.DailySummary
import com.balancesentinel.app.data.model.RawRecord
import com.balancesentinel.app.data.model.UsageSnapshot
import com.balancesentinel.app.widget.BalanceWidgetDataStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        assertEquals(-1f, widgetPrefs.getLastAlertedBalance(afterId, CURRENCY))
        assertEquals(-1f, widgetPrefs.getPreviousBalance(afterId, CURRENCY))
        assertEquals(0L, widgetPrefs.getPreviousBalanceTime(afterId, CURRENCY))
        assertEquals(-1f, widgetPrefs.getLastChangeAlertedBalance(afterId, CURRENCY))
        assertEquals(0L, widgetPrefs.getLastChangeAlertedTime(afterId, CURRENCY))
        assertTrue(widgetPrefs.isBalanceAlertEnabled(afterId, CURRENCY))
        assertTrue(widgetPrefs.isChangeAlertEnabled(afterId, CURRENCY))
        assertTrue(widgetPrefs.isBalanceAlertEnabled(afterId, SECOND_CURRENCY))
        assertTrue(widgetPrefs.isChangeAlertEnabled(afterId, SECOND_CURRENCY))
        assertEquals(-1f, widgetPrefs.getLastAlertedBalance(afterId, SECOND_CURRENCY))
        assertEquals(-1f, widgetPrefs.getPreviousBalance(afterId, SECOND_CURRENCY))
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
        assertEquals(-1f, widgetPrefs.getLastAlertedBalance(target.id, CURRENCY))
        assertEquals(-1f, widgetPrefs.getLastAlertedBalance(target.id, SECOND_CURRENCY))
        assertFalse(widgetPrefs.isBalanceAlertEnabled(target.id, CURRENCY))
        assertFalse(widgetPrefs.isChangeAlertEnabled(target.id, CURRENCY))
        assertFalse(widgetPrefs.isBalanceAlertEnabled(target.id, SECOND_CURRENCY))
        assertFalse(widgetPrefs.isChangeAlertEnabled(target.id, SECOND_CURRENCY))
        assertTrue(widgetPrefs.isBalanceAlertEnabled(survivor.id, SECOND_CURRENCY))
        assertTrue(widgetPrefs.isChangeAlertEnabled(survivor.id, SECOND_CURRENCY))
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

    @Test
    fun `delete keeps account retryable when raw record cleanup persistence fails`() {
        assertDeleteFailureKeepsAccount("raw_records")
    }

    @Test
    fun `delete keeps account retryable when daily summary cleanup persistence fails`() {
        assertDeleteFailureKeepsAccount("daily_summaries")
    }

    @Test
    fun `delete keeps account retryable when usage cleanup persistence fails`() {
        assertDeleteFailureKeepsAccount("usage_snapshots")
    }

    @Test
    fun `key replacement keeps old account retryable when usage migration fails`() {
        val before = accountManager.addAccount("Before", "sk-before-retry-key")
        seedOwnedState(before)
        val replacementKey = "sk-after-retry-key"
        val failingContext = FailingPrefsContext(context, "usage_snapshots")
        val failingLifecycle = AccountLifecycleManager(
            failingContext,
            accountManager
        )

        val failure = runCatching {
            failingLifecycle.save(
                before.id,
                AccountDraft(
                    label = "After",
                    apiKey = replacementKey,
                    providerType = before.providerType
                )
            )
        }.exceptionOrNull()

        assertTrue(failingContext.failed)
        assertNotNull(failure)
        assertEquals(before, accountManager.getAccount(before.id))
        assertNull(accountManager.getAccount(accountManager.computeId(replacementKey)))

        lifecycleManager.save(
            before.id,
            AccountDraft(
                label = "After",
                apiKey = replacementKey,
                providerType = before.providerType
            )
        )
        val replacementId = accountManager.computeId(replacementKey)
        assertNull(accountManager.getAccount(before.id))
        assertNotNull(accountManager.getAccount(replacementId))
        assertEquals(
            listOf(replacementId),
            UsageDataStore.getAllSnapshots(context).map { it.accountId }
        )
    }

    @Test
    fun `key replacement keeps old account retryable when alert migration persistence fails`() {
        val before = accountManager.addAccount("Before", "sk-widget-retry-key")
        seedOwnedState(before)
        val replacementKey = "sk-widget-after-retry-key"
        context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("alert_pair_state_migrated_v1", true)
            .commit()
        val failingContext = FailingPrefsContext(context, "widget_prefs")
        val failingLifecycle = AccountLifecycleManager(failingContext, accountManager)

        val failure = runCatching {
            failingLifecycle.save(
                before.id,
                AccountDraft(
                    label = "After",
                    apiKey = replacementKey,
                    providerType = before.providerType
                )
            )
        }.exceptionOrNull()

        assertTrue(failingContext.failed)
        assertNotNull(failure)
        assertEquals(before, accountManager.getAccount(before.id))
        assertNull(accountManager.getAccount(accountManager.computeId(replacementKey)))

        lifecycleManager.save(
            before.id,
            AccountDraft(
                label = "After",
                apiKey = replacementKey,
                providerType = before.providerType
            )
        )
        assertNull(accountManager.getAccount(before.id))
        assertNotNull(accountManager.getAccount(accountManager.computeId(replacementKey)))
    }

    private fun assertDeleteFailureKeepsAccount(failingPrefsName: String) {
        val account = accountManager.addAccount("Retryable", "sk-retryable-$failingPrefsName")
        seedOwnedState(account)
        val failingContext = FailingPrefsContext(context, failingPrefsName)
        val failingLifecycle = AccountLifecycleManager(
            failingContext,
            accountManager
        )

        val failure = runCatching { failingLifecycle.delete(account.id) }.exceptionOrNull()

        assertTrue(failingContext.failed)
        assertNotNull(failure)
        assertEquals(account, accountManager.getAccount(account.id))

        lifecycleManager.delete(account.id)
        assertNull(accountManager.getAccount(account.id))
    }

    private fun seedOwnedState(account: AccountInfo) {
        RawRecordStore.addRecord(
            context,
            RawRecord(account.id, 1L, CURRENCY, 1f, 0f, 0f)
        )
        DailySummaryStore.addSummary(context, summary(account.id))
        UsageDataStore.saveSnapshot(context, UsageSnapshot(account.id, 1L))
        widgetPrefs.setLastAlertedBalance(account.id, CURRENCY, 2f)
        widgetPrefs.setPreviousBalance(account.id, CURRENCY, 3f)
        widgetPrefs.setPreviousBalanceTime(account.id, CURRENCY, 4L)
        widgetPrefs.setLastChangeAlertedBalance(account.id, CURRENCY, 5f)
        widgetPrefs.setLastChangeAlertedTime(account.id, CURRENCY, 6L)
        widgetPrefs.setBalanceAlertEnabled(account.id, CURRENCY, true)
        widgetPrefs.setChangeAlertEnabled(account.id, CURRENCY, true)
        widgetPrefs.setLastAlertedBalance(account.id, SECOND_CURRENCY, 12f)
        widgetPrefs.setPreviousBalance(account.id, SECOND_CURRENCY, 13f)
        widgetPrefs.setPreviousBalanceTime(account.id, SECOND_CURRENCY, 14L)
        widgetPrefs.setLastChangeAlertedBalance(account.id, SECOND_CURRENCY, 15f)
        widgetPrefs.setLastChangeAlertedTime(account.id, SECOND_CURRENCY, 16L)
        widgetPrefs.setBalanceAlertEnabled(account.id, SECOND_CURRENCY, true)
        widgetPrefs.setChangeAlertEnabled(account.id, SECOND_CURRENCY, true)
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

    private class FailingPrefsContext(
        base: Context,
        private val failingPrefsName: String
    ) : ContextWrapper(base) {
        var failed = false
            private set

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val delegate = baseContext.getSharedPreferences(name, mode)
            if (name != failingPrefsName) return delegate

            return object : SharedPreferences by delegate {
                override fun edit(): SharedPreferences.Editor {
                    val editor = delegate.edit()
                    return object : SharedPreferences.Editor by editor {
                        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                            editor.putString(key, value)
                            return this
                        }

                        override fun remove(key: String?): SharedPreferences.Editor {
                            editor.remove(key)
                            return this
                        }

                        override fun commit(): Boolean {
                            if (!failed) {
                                failed = true
                                return false
                            }
                            return editor.commit()
                        }

                        override fun apply() {
                            if (!failed) {
                                failed = true
                                throw IllegalStateException("Injected persistence failure")
                            }
                            editor.apply()
                        }
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Lifecycle invalidation ordering (Finding 4)
    // ═══════════════════════════════════════════════════════════

    // RED: gateway.invalidate(oldId) must be called while the old account
    // is still persisted — before migration/cleanup removes it. With the
    // invalidate calls temporarily removed, this test FAILS because
    // invalidations is empty.
    @Test
    fun `replacement invalidates old account while old data is still persisted`() {
        val before = accountManager.addAccount("Before", "sk-ordering-key")
        seedOwnedState(before)
        val recordingGateway = RecordingLifecycleGateway(accountManager)
        val orderingLifecycle = AccountLifecycleManager(context, accountManager, recordingGateway)

        orderingLifecycle.save(
            before.id,
            AccountDraft(
                label = "After",
                apiKey = "sk-ordering-after-key",
                providerType = before.providerType
            )
        )

        assertEquals("invalidate must be called exactly once", 1, recordingGateway.invalidations.size)
        assertEquals(before.id, recordingGateway.invalidations[0].accountId)
        assertTrue(
            "Old account must still be persisted when invalidate runs",
            recordingGateway.invalidations[0].oldAccountStillPersisted
        )
    }

    // RED: gateway.invalidate(accountId) must be called while the account
    // is still persisted — before cleanup removes it. With the invalidate
    // calls temporarily removed, this test FAILS because invalidations is empty.
    @Test
    fun `delete invalidates account while old data is still persisted`() {
        val target = accountManager.addAccount("Target", "sk-delete-ordering")
        seedOwnedState(target)
        val recordingGateway = RecordingLifecycleGateway(accountManager)
        val orderingLifecycle = AccountLifecycleManager(context, accountManager, recordingGateway)

        orderingLifecycle.delete(target.id)

        assertEquals("invalidate must be called exactly once", 1, recordingGateway.invalidations.size)
        assertEquals(target.id, recordingGateway.invalidations[0].accountId)
        assertTrue(
            "Account must still be persisted when invalidate runs",
            recordingGateway.invalidations[0].oldAccountStillPersisted
        )
    }

    data class InvalidationRecord(
        val accountId: String,
        val oldAccountStillPersisted: Boolean
    )

    /**
     * Records invalidate calls and whether the old account was still
     * persisted at the time of the call. Used to verify ordering:
     * invalidate(oldId) must run before migration/cleanup persistence.
     */
    private class RecordingLifecycleGateway(
        private val accountManager: ApiKeyManager
    ) : RefreshGateway {
        val invalidations = mutableListOf<InvalidationRecord>()

        override fun invalidate(accountId: String) {
            invalidations += InvalidationRecord(
                accountId = accountId,
                oldAccountStillPersisted = accountManager.getAccount(accountId) != null
            )
        }

        override suspend fun refreshAccount(
            accountId: String,
            trigger: RefreshTrigger
        ): com.balancesentinel.app.data.refresh.AccountRefreshResult =
            throw UnsupportedOperationException("Not used in lifecycle tests")

        override suspend fun refreshAll(
            trigger: RefreshTrigger
        ): List<com.balancesentinel.app.data.refresh.AccountRefreshResult> =
            throw UnsupportedOperationException("Not used in lifecycle tests")
    }

    private companion object {
        const val CURRENCY = "USD"
        const val SECOND_CURRENCY = "CNY"
    }
}
