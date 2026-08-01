package com.balancesentinel.app.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.debug.ApiDebugEntry
import com.balancesentinel.app.data.debug.ApiDebugStore
import com.balancesentinel.app.data.model.AccountDraft
import com.balancesentinel.app.data.model.RawRecord
import com.balancesentinel.app.data.model.UsageSnapshot
import org.junit.After
import org.junit.Assert.assertEquals
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
    private lateinit var prefsName: String

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefsName = "account_lifecycle_${System.nanoTime()}"
        accountManager = ApiKeyManager(context, context.getSharedPreferences(prefsName, Context.MODE_PRIVATE))
        lifecycleManager = AccountLifecycleManager(context, accountManager)
    }

    @After
    fun tearDown() {
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().commit()
        RawRecordStore.clear(context)
        UsageDataStore.clear(context)
        ApiDebugStore.clearAll()
    }

    @Test
    fun `save migrates history and usage when replacing account key`() {
        val before = accountManager.addAccount("Before", "sk-before-key")
        RawRecordStore.addRecord(context, RawRecord(before.id, 1L, "USD", 1f, 0f, 0f))
        UsageDataStore.saveSnapshot(context, UsageSnapshot(before.id, 1L))

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
        assertEquals(listOf(afterId), UsageDataStore.getAllSnapshots(context).map { it.accountId })
    }

    @Test
    fun `delete removes account history usage and debug state`() {
        val account = accountManager.addAccount("Account", "sk-delete-key")
        RawRecordStore.addRecord(context, RawRecord(account.id, 1L, "USD", 1f, 0f, 0f))
        UsageDataStore.saveSnapshot(context, UsageSnapshot(account.id, 1L))
        ApiDebugStore.addEntry(
            ApiDebugEntry(account.id, "url", "GET", emptyMap(), null, 200, emptyMap(), "", 1L, 1L)
        )

        lifecycleManager.delete(account.id)

        assertTrue(accountManager.getAccounts().isEmpty())
        assertTrue(RawRecordStore.getAllRecords(context).isEmpty())
        assertTrue(UsageDataStore.getAllSnapshots(context).isEmpty())
        assertTrue(ApiDebugStore.getEntries(account.id).isEmpty())
    }
}
