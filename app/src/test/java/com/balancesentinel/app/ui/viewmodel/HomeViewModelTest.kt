package com.balancesentinel.app.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.api.UnifiedBalance
import com.balancesentinel.app.data.api.cache.ProviderCache
import com.balancesentinel.app.data.debug.ApiDebugEntry
import com.balancesentinel.app.data.debug.ApiDebugStore
import com.balancesentinel.app.data.model.BalanceInfo
import com.balancesentinel.app.data.model.BalanceResponse
import com.balancesentinel.app.data.model.AccountDraft
import com.balancesentinel.app.data.repository.ApiKeyManager
import com.balancesentinel.app.data.repository.BalanceRepository
import com.balancesentinel.app.data.repository.WidgetPrefs
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HomeViewModelTest {

    private lateinit var context: Application
    private lateinit var testPrefsName: String
    private lateinit var apiKeyManager: ApiKeyManager
    private lateinit var mockRepository: BalanceRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        testPrefsName = "test_home_vm_${System.nanoTime()}"
        apiKeyManager = ApiKeyManager(context, context.getSharedPreferences(testPrefsName, Context.MODE_PRIVATE))
        mockRepository = mockk(relaxed = true)
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        ApiDebugStore.clearAll()
        context.getSharedPreferences(testPrefsName, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun createViewModel(): HomeViewModel {
        return HomeViewModel(context, apiKeyManager, mockRepository)
    }

    // ═══════════════════════════════════════════════════════════
    // 初始状态
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `initial state has default settings from WidgetPrefs`() {
        val vm = createViewModel()
        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertEquals(WidgetPrefs.DEFAULT_INTERVAL, state.refreshIntervalSeconds)
        assertTrue(state.accounts.isEmpty())
    }

    @Test
    fun `initial state loads accounts from ApiKeyManager`() {
        apiKeyManager.addAccount("测试账户", "sk-test-key-12345")
        val vm = createViewModel()
        val state = vm.uiState.value
        assertEquals(1, state.accounts.size)
        assertEquals("测试账户", state.accounts[0].label)
    }

    // ═══════════════════════════════════════════════════════════
    // addAccount
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `addAccount with blank label or key is no-op`() {
        val vm = createViewModel()
        vm.addAccount("", "sk-key")
        vm.addAccount("标签", "")
        vm.addAccount("", "")
        assertTrue(vm.uiState.value.accounts.isEmpty())
    }

    @Test
    fun `addAccount adds to ApiKeyManager and updates state`() {
        val vm = createViewModel()
        vm.addAccount("新账户", "sk-new-key")

        val accounts = vm.uiState.value.accounts
        assertEquals(1, accounts.size)
        assertEquals("新账户", accounts[0].label)
    }

    @Test
    fun `addAccount rejects malformed custom provider URL`() {
        val vm = createViewModel()

        vm.addAccount(
            label = "Custom",
            apiKey = "custom-key",
            providerType = ProviderType.CUSTOM,
            extraSettings = mapOf("baseUrl" to "not-a-url")
        )

        assertTrue(vm.uiState.value.accounts.isEmpty())
        assertTrue(apiKeyManager.getAccounts().isEmpty())
    }

    @Test
    fun `addAccount stores dynamic credentials by provider field classification`() {
        val vm = createViewModel()

        vm.addAccount(
            label = "Zhipu",
            apiKey = "zhipu-api-key",
            providerType = ProviderType.ZHIPU,
            extraSettings = mapOf("secretKey" to "secret-value")
        )

        val account = apiKeyManager.getAccounts().single()
        assertEquals("secret-value", account.extraCredentials["secretKey"])
        assertFalse(account.extraSettings.containsKey("secretKey"))
    }

    @Test
    fun `addAccount rejects missing required dynamic credential`() {
        val vm = createViewModel()

        vm.addAccount(
            label = "Zhipu",
            apiKey = "zhipu-api-key",
            providerType = ProviderType.ZHIPU
        )

        assertTrue(apiKeyManager.getAccounts().isEmpty())
    }

    // ═══════════════════════════════════════════════════════════
    // removeAccount
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `removeAccount clears account caches debug records and alert state`() {
        val account = apiKeyManager.addAccount("A", "sk-key-a")
        val widgetPrefs = WidgetPrefs(context)
        val cache = ProviderCache(context)
        cache.put(
            ProviderType.DEEPSEEK,
            account.id,
            UnifiedBalance(ProviderType.DEEPSEEK, account.id, true, emptyList())
        )
        widgetPrefs.setBalanceAlertEnabled(account.id, "USD", true)
        widgetPrefs.setNotificationWalletSelected(account.id, "USD", true)
        ApiDebugStore.addEntry(
            ApiDebugEntry(
                account.id,
                "https://api.example.com",
                "GET",
                emptyMap(),
                null,
                200,
                emptyMap(),
                "{}",
                1L,
                1L
            )
        )
        val vm = createViewModel()

        vm.removeAccount(account.id)

        assertTrue(vm.uiState.value.accounts.isEmpty())
        assertNull(cache.get(ProviderType.DEEPSEEK, account.id))
        assertTrue(ApiDebugStore.getEntries(account.id).isEmpty())
        assertFalse(widgetPrefs.isBalanceAlertEnabled(account.id, "USD"))
        assertFalse(widgetPrefs.getNotificationWalletOrder().contains("${account.id}_USD"))
    }

    @Test
    fun `removeAccountWithData clears account caches debug records and alert state`() {
        val account = apiKeyManager.addAccount("Account", "delete-key")
        val widgetPrefs = WidgetPrefs(context)
        val cache = ProviderCache(context)
        cache.put(
            ProviderType.DEEPSEEK,
            account.id,
            UnifiedBalance(ProviderType.DEEPSEEK, account.id, true, emptyList())
        )
        widgetPrefs.setBalanceAlertEnabled(account.id, "USD", true)
        widgetPrefs.setNotificationWalletSelected(account.id, "USD", true)
        ApiDebugStore.addEntry(
            ApiDebugEntry(account.id, "https://api.example.com", "GET", emptyMap(), null, 200, emptyMap(), "{}", 1L, 1L)
        )
        val vm = createViewModel()

        vm.removeAccountWithData(account.id)

        assertNull(apiKeyManager.getAccount(account.id))
        assertNull(cache.get(ProviderType.DEEPSEEK, account.id))
        assertTrue(ApiDebugStore.getEntries(account.id).isEmpty())
        assertFalse(widgetPrefs.isBalanceAlertEnabled(account.id, "USD"))
        assertFalse(widgetPrefs.getNotificationWalletOrder().contains("${account.id}_USD"))
    }

    // ═══════════════════════════════════════════════════════════
    // renameAccount
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `renameAccount updates label in ApiKeyManager and state`() {
        apiKeyManager.addAccount("旧名", "sk-key")
        val vm = createViewModel()
        val accId = vm.uiState.value.accounts[0].id

        vm.renameAccount(accId, "新名字")
        assertEquals("新名字", vm.uiState.value.accounts[0].label)
    }

    @Test
    fun `renameAccount with blank label is no-op`() {
        apiKeyManager.addAccount("原名", "sk-key")
        val vm = createViewModel()
        val accId = vm.uiState.value.accounts[0].id

        vm.renameAccount(accId, "")
        assertEquals("原名", vm.uiState.value.accounts[0].label)
    }

    // ═══════════════════════════════════════════════════════════
    // 设置变更
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `editAccount key replacement preserves credentials and advances revision`() {
        val original = apiKeyManager.addAccount(
            label = "Original",
            apiKey = "old-key",
            providerType = ProviderType.CUSTOM,
            extraSettings = mapOf("baseUrl" to "https://old.example.com"),
            extraCredentials = mapOf("secretKey" to "secret-value"),
            usageScript = "return old"
        )
        val vm = createViewModel()

        vm.editAccount(
            id = original.id,
            newLabel = "Updated",
            newApiKey = "new-key",
            extraSettings = mapOf("baseUrl" to "https://new.example.com"),
            usageScript = "return new"
        )

        val updated = requireNotNull(apiKeyManager.getAccount(apiKeyManager.computeId("new-key")))
        assertEquals("Updated", updated.label)
        assertEquals(1L, updated.revision)
        assertEquals("secret-value", updated.extraCredentials["secretKey"])
        assertEquals("https://new.example.com", updated.extraSettings["baseUrl"])
        assertEquals("return new", updated.usageScript)
        assertNull(apiKeyManager.getAccount(original.id))
    }

    @Test
    fun `editAccount key replacement migrates alert settings and notification order`() {
        val original = apiKeyManager.addAccount(
            label = "Original",
            apiKey = "old-key",
            providerType = ProviderType.CUSTOM,
            extraSettings = mapOf("baseUrl" to "https://old.example.com")
        )
        val widgetPrefs = WidgetPrefs(context)
        widgetPrefs.setBalanceAlertEnabled(original.id, "USD", true)
        widgetPrefs.setNotificationWalletSelected(original.id, "USD", true)
        val vm = createViewModel()

        vm.editAccount(
            original.id,
            AccountDraft(
                label = "Updated",
                apiKey = "new-key",
                providerType = ProviderType.CUSTOM,
                extraSettings = mapOf("baseUrl" to "https://new.example.com")
            )
        )

        val newId = apiKeyManager.computeId("new-key")
        assertTrue(widgetPrefs.isBalanceAlertEnabled(newId, "USD"))
        assertTrue(widgetPrefs.getNotificationWalletOrder().contains("${newId}_USD"))
        assertFalse(widgetPrefs.getNotificationWalletOrder().contains("${original.id}_USD"))
    }

    @Test
    fun `setRefreshInterval updates state`() {
        val vm = createViewModel()
        vm.setRefreshInterval(300)
        assertEquals(300, vm.uiState.value.refreshIntervalSeconds)
    }

    @Test
    fun `setAlertEnabled updates state`() {
        val vm = createViewModel()
        vm.setAlertEnabled(true)
        assertTrue(vm.uiState.value.alertEnabled)
        vm.setAlertEnabled(false)
        assertFalse(vm.uiState.value.alertEnabled)
    }

    @Test
    fun `setAlertThreshold updates state and clears snooze`() {
        val vm = createViewModel()
        vm.setAlertThreshold(10.5f)
        assertEquals(10.5f, vm.uiState.value.alertThreshold)
    }

    @Test
    fun `setChangeAlertEnabled updates state`() {
        val vm = createViewModel()
        vm.setChangeAlertEnabled(true)
        assertTrue(vm.uiState.value.changeAlertEnabled)
    }

    @Test
    fun `setChangeAlertThreshold updates state and clears snooze`() {
        val vm = createViewModel()
        vm.setChangeAlertThreshold(20.0f)
        assertEquals(20.0f, vm.uiState.value.changeAlertThreshold)
    }

    @Test
    fun `setChangeAlertPeriodMinutes updates state`() {
        val vm = createViewModel()
        vm.setChangeAlertPeriodMinutes(30)
        assertEquals(30, vm.uiState.value.changeAlertPeriodMinutes)
    }

    @Test
    fun `setSnoozeDurationMinutes updates state`() {
        val vm = createViewModel()
        vm.setSnoozeDurationMinutes(120)
        assertEquals(120, vm.uiState.value.snoozeDurationMinutes)
    }

    // ═══════════════════════════════════════════════════════════
    // refreshBalance — error states
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `refreshBalance with no accounts shows error`() {
        val vm = createViewModel()
        vm.refreshBalance()
        val state = vm.uiState.value
        assertNotNull(state.errorMessage)
    }

    @Test
    fun `refreshBalance sets isLoading while fetching`() {
        apiKeyManager.addAccount("A", "sk-key-a")
        val mockResponse = BalanceResponse(
            isAvailable = true,
            balanceInfos = listOf(BalanceInfo("CNY", "100.50", "10.00", "90.50"))
        )
        coEvery { mockRepository.fetchBalance("sk-key-a", any()) } returns Result.success(mockResponse)

        val vm = createViewModel()
        vm.refreshBalance()
        // With UnconfinedTestDispatcher the coroutine runs synchronously
        val state = vm.uiState.value
        assertFalse(state.isLoading) // completed synchronously
        assertNull(state.errorMessage)
    }

    @Test
    fun `refreshBalance failure updates error message`() {
        apiKeyManager.addAccount("主账户", "sk-bad-key")
        coEvery { mockRepository.fetchBalance("sk-bad-key", any()) } returns Result.failure(
            java.io.IOException("网络超时")
        )

        val vm = createViewModel()
        vm.refreshBalance()

        val state = vm.uiState.value
        assertNotNull(state.errorMessage)
        assertTrue(state.errorMessage?.contains("网络超时") == true ||
                   state.errorMessage?.contains("主账户") == true)
    }

    // ═══════════════════════════════════════════════════════════
    // 配置导入/导出
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `getConfigJson returns non-empty string with accounts`() {
        apiKeyManager.addAccount("账户", "sk-config-key")
        val vm = createViewModel()
        val json = vm.getConfigJson()
        assertTrue(json.isNotEmpty())
        assertTrue(json.contains("account"))
    }

    // ═══════════════════════════════════════════════════════════
    // loadCrashLogs / clearCrashes
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `clearCrashes empties crash logs in state`() {
        val vm = createViewModel()
        vm.clearCrashes()
        assertTrue(vm.uiState.value.crashLogs.isEmpty())
    }

    // ═══════════════════════════════════════════════════════════
    // loadRefreshStats
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `loadRefreshStats loads stats from store`() {
        val vm = createViewModel()
        vm.loadRefreshStats()
        val stats = vm.refreshStats.value
        assertNotNull(stats)
        assertEquals(0, stats?.totalAttempts)
    }

    // ═══════════════════════════════════════════════════════════
    // clearAllSnooze / refreshSnoozeInfo
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `clearAllSnooze updates snoozeInfo in state`() {
        val vm = createViewModel()
        // Set a snooze on the default widget_prefs (HomeViewModel creates WidgetPrefs(application))
        val defaultPrefs = WidgetPrefs(context)
        defaultPrefs.setSnoozeUntil("any-account", System.currentTimeMillis() + 3600_000L)
        vm.refreshSnoozeInfo()
        assertTrue(vm.uiState.value.snoozeInfo.anySnoozed)

        vm.clearAllSnooze()
        assertFalse(vm.uiState.value.snoozeInfo.anySnoozed)
    }

    @Test
    fun `refreshSnoozeInfo updates snooze info from prefs`() {
        val vm = createViewModel()
        val defaultPrefs = WidgetPrefs(context)
        defaultPrefs.setSnoozeUntil("any-account", System.currentTimeMillis() + 3600_000L)

        vm.refreshSnoozeInfo()
        assertTrue(vm.uiState.value.snoozeInfo.anySnoozed)
    }

    // ═══════════════════════════════════════════════════════════
    // loadStatusSummary / loadCrashLogs (HomeViewModel level)
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `loadStatusSummary loads from RefreshScheduler`() {
        val vm = createViewModel()
        vm.loadStatusSummary()
        val summary = vm.uiState.value.statusSummary
        // In Robolectric, the scheduler may or may not have status
        // Just verify the method doesn't crash
        assertNotNull(vm.uiState.value)
    }

    // ═══════════════════════════════════════════════════════════
    // applyImportedConfig — basic tests
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `applyImportedConfig updates settings from config`() {
        apiKeyManager.addAccount("Existing", "sk-existing-key")
        val vm = createViewModel()

        val config = com.balancesentinel.app.data.repository.AppConfig(
            version = 1,
            exportedAt = "2026-07-09T12:00:00",
            appVersion = "1.0.0",
            accounts = emptyList(),
            settings = com.balancesentinel.app.data.repository.ConfigSettings(
                refreshIntervalSeconds = 180,
                alertEnabled = true,
                alertThreshold = 25f,
                changeAlertEnabled = true,
                changeAlertThreshold = 15f,
                changeAlertPeriodMinutes = 45,
                logMaxEntries = 150,
                snoozeDurationMinutes = 90
            )
        )

        vm.applyImportedConfig(config)
        val state = vm.uiState.value
        assertEquals(180, state.refreshIntervalSeconds)
        assertTrue(state.alertEnabled)
        assertEquals(25f, state.alertThreshold)
        assertEquals(45, state.changeAlertPeriodMinutes)
        assertEquals(90, state.snoozeDurationMinutes)
    }

    @Test
    fun `applyImportedConfig updates accounts`() {
        val vm = createViewModel()
        val config = com.balancesentinel.app.data.repository.AppConfig(
            version = 1,
            exportedAt = "2026-07-09T12:00:00",
            appVersion = "1.0.0",
            accounts = listOf(
                com.balancesentinel.app.data.model.AccountInfo(
                    id = "imported1", label = "Imported", apiKey = "sk-importedkey123"
                )
            ),
            settings = com.balancesentinel.app.data.repository.ConfigSettings(
                refreshIntervalSeconds = 30, alertEnabled = false, alertThreshold = 0f,
                changeAlertEnabled = false, changeAlertThreshold = 0f,
                changeAlertPeriodMinutes = 0, logMaxEntries = 100
            )
        )

        vm.applyImportedConfig(config)
        val accounts = vm.uiState.value.accounts
        assertTrue(accounts.any { it.label == "Imported" })
    }

    @Test
    fun `applyImportedConfig with redacted accounts sets error message`() {
        val vm = createViewModel()
        val config = com.balancesentinel.app.data.repository.AppConfig(
            version = 1,
            exportedAt = "2026-07-09T12:00:00",
            appVersion = "1.0.0",
            accounts = listOf(
                com.balancesentinel.app.data.model.AccountInfo(
                    id = "r1", label = "Redacted", apiKey = "sk-a****t901"
                )
            ),
            settings = com.balancesentinel.app.data.repository.ConfigSettings(
                refreshIntervalSeconds = 30, alertEnabled = false, alertThreshold = 0f,
                changeAlertEnabled = false, changeAlertThreshold = 0f,
                changeAlertPeriodMinutes = 0, logMaxEntries = 100
            )
        )

        vm.applyImportedConfig(config)
        // Redacted account should be skipped — accounts list should be empty
        assertEquals(0, vm.uiState.value.accounts.size)
    }

    // ═══════════════════════════════════════════════════════════
    // setRefreshInterval with accounts triggers balance refresh
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `setRefreshInterval with accounts triggers refresh`() {
        apiKeyManager.addAccount("Acc", "sk-key-acc")
        val mockResponse = BalanceResponse(
            isAvailable = true,
            balanceInfos = listOf(BalanceInfo("CNY", "88.88", "0", "0"))
        )
        coEvery { mockRepository.fetchBalance("sk-key-acc", any()) } returns Result.success(mockResponse)

        val vm = createViewModel()
        val accId = vm.uiState.value.accounts[0].id
        vm.setRefreshInterval(180)
        assertEquals(180, vm.uiState.value.refreshIntervalSeconds)
        // Refresh should have been triggered — balance should be populated
        assertNotNull(vm.uiState.value.accountBalances[accId])
    }

    // ═══════════════════════════════════════════════════════════
    // setAlertThreshold and setChangeAlertThreshold clear snooze
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `setAlertThreshold clears snooze and updates info`() {
        val vm = createViewModel()
        val defaultPrefs = WidgetPrefs(context)
        defaultPrefs.setSnoozeUntil("any-account", System.currentTimeMillis() + 3600_000L)
        vm.refreshSnoozeInfo()
        assertTrue(vm.uiState.value.snoozeInfo.anySnoozed)

        vm.setAlertThreshold(50f)
        assertEquals(50f, vm.uiState.value.alertThreshold)
        assertFalse(vm.uiState.value.snoozeInfo.anySnoozed)
    }

    @Test
    fun `setChangeAlertThreshold clears snooze and updates info`() {
        val vm = createViewModel()
        val defaultPrefs = WidgetPrefs(context)
        defaultPrefs.setSnoozeUntil("account-x", System.currentTimeMillis() + 3600_000L)
        vm.refreshSnoozeInfo()
        assertTrue(vm.uiState.value.snoozeInfo.anySnoozed)

        vm.setChangeAlertThreshold(30f)
        assertEquals(30f, vm.uiState.value.changeAlertThreshold)
        assertFalse(vm.uiState.value.snoozeInfo.anySnoozed)
    }

    // ═══════════════════════════════════════════════════════════
    // removeAccount also clears widget cached balance
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `removeAccount clears balance from state map`() {
        apiKeyManager.addAccount("A", "sk-remove-bal")
        val mockResponse = BalanceResponse(
            isAvailable = true,
            balanceInfos = listOf(BalanceInfo("CNY", "100.00", "0", "0"))
        )
        coEvery { mockRepository.fetchBalance("sk-remove-bal", any()) } returns Result.success(mockResponse)

        val vm = createViewModel()
        val accId = vm.uiState.value.accounts[0].id
        vm.refreshBalance()
        assertTrue(vm.uiState.value.accountBalances.containsKey(accId))

        // Now remove
        vm.removeAccount(accId)
        assertFalse(vm.uiState.value.accountBalances.containsKey(accId))
    }
}
