package com.balancesentinel.app.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.model.AccountInfo
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConfigManagerTest {

    private lateinit var context: Context
    private lateinit var mockKeyMgr: ApiKeyManager
    private lateinit var prefs: WidgetPrefs

    private val realKey = "sk-abc123def456ghi789jkl012mno345pqr678stu901"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = WidgetPrefs(context)

        // Mock ApiKeyManager — EncryptedSharedPreferences 在 Robolectric 中不可用
        mockKeyMgr = mockk()
        every { mockKeyMgr.getAccounts() } returns listOf(
            AccountInfo(id = "a1b2c3d4", label = "主账户", apiKey = realKey),
            AccountInfo(id = "e5f6g7h8", label = "测试", apiKey = "sk-short"),
            AccountInfo(id = "x9y0z1", label = "短Key", apiKey = "abc")
        )
    }

    // ═══════════════════════════════════════════════════════════
    // redactApiKey
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `redactApiKey preserves first 4 and last 4 chars`() {
        val result = ConfigManager.redactApiKey(realKey)
        assertEquals("sk-a****u901", result)
    }

    @Test
    fun `redactApiKey short key returns REDACTED`() {
        val result = ConfigManager.redactApiKey("abc")
        assertEquals("[REDACTED]", result)
    }

    @Test
    fun `redactApiKey exactly 8 chars preserves all`() {
        val result = ConfigManager.redactApiKey("12345678")
        assertEquals("1234****5678", result)
    }

    // ═══════════════════════════════════════════════════════════
    // isRedactedApiKey
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `isRedactedApiKey detects redacted key`() {
        assertTrue(ConfigManager.isRedactedApiKey("sk-a****t901"))
    }

    @Test
    fun `isRedactedApiKey detects REDACTED placeholder`() {
        assertTrue(ConfigManager.isRedactedApiKey("[REDACTED]"))
    }

    @Test
    fun `isRedactedApiKey returns false for real key`() {
        assertFalse(ConfigManager.isRedactedApiKey(realKey))
    }

    // ═══════════════════════════════════════════════════════════
    // buildConfig — includeTokens = false (default)
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `buildConfig without tokens redacts API keys`() {
        val json = ConfigManager.buildConfig(context, mockKeyMgr, prefs, includeTokens = false)

        // 真实 Key 应被脱敏
        assertTrue("Should contain redacted key", json.contains("sk-a****u901"))
        // 8 字符 Key 脱敏为 4+4
        assertTrue("Should contain redacted short key", json.contains("sk-s****hort"))
        // 真正短 Key（< 8 字符）应转为 [REDACTED]
        assertTrue("Should contain [REDACTED] for very short key", json.contains("[REDACTED]"))
        // 原始完整 Key 不应出现
        assertFalse("Must NOT contain real API key", json.contains(realKey))
    }

    // ═══════════════════════════════════════════════════════════
    // buildConfig — includeTokens = true
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `buildConfig with tokens preserves full API keys`() {
        val json = ConfigManager.buildConfig(context, mockKeyMgr, prefs, includeTokens = true)

        // 完整 Key 应该出现（包括短 Key "abc"）
        assertTrue("Must contain real API key", json.contains(realKey))
        assertTrue("Must contain short key as-is", json.contains("\"apiKey\": \"abc\""))
        // 脱敏标记不应出现
        assertFalse("Must NOT contain redacted marker", json.contains("****"))
        assertFalse("Must NOT contain [REDACTED]", json.contains("[REDACTED]"))
    }

    // ═══════════════════════════════════════════════════════════
    // buildConfig — 结构验证
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `buildConfig produces valid JSON with expected structure`() {
        val json = ConfigManager.buildConfig(context, mockKeyMgr, prefs, includeTokens = false)

        // version 字段有默认值，kotlinx serialization 可能不输出
        assertTrue("Missing exportedAt", json.contains("\"exportedAt\""))
        assertTrue("Missing appVersion", json.contains("\"appVersion\""))
        assertTrue("Missing accounts", json.contains("\"accounts\""))
        assertTrue("Missing settings", json.contains("\"settings\""))
        assertTrue("Missing refreshIntervalSeconds", json.contains("\"refreshIntervalSeconds\""))
    }

    @Test
    fun `buildConfig includes account labels`() {
        val json = ConfigManager.buildConfig(context, mockKeyMgr, prefs, includeTokens = false)

        assertTrue("Missing 主账户 label", json.contains("主账户"))
        assertTrue("Missing 测试 label", json.contains("测试"))
    }

    // ═══════════════════════════════════════════════════════════
    // buildConfig — settings reflection
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `buildConfig reflects custom widgetPrefs settings`() {
        prefs.refreshIntervalSeconds = 120
        prefs.alertEnabled = true
        prefs.alertThreshold = 50f
        prefs.snoozeDurationMinutes = 30

        val json = ConfigManager.buildConfig(context, mockKeyMgr, prefs, includeTokens = false)
        assertTrue(json.contains("\"refreshIntervalSeconds\": 120"))
        assertTrue(json.contains("\"alertEnabled\": true"))
        assertTrue(json.contains("\"snoozeDurationMinutes\": 30"))
    }

    @Test
    fun `buildConfig includes perCurrencyAlertSettings`() {
        prefs.setBalanceAlertEnabled("acc1", "CNY", true)
        prefs.setChangeAlertEnabled("acc1", "CNY", false)
        val json = ConfigManager.buildConfig(context, mockKeyMgr, prefs, includeTokens = false)
        assertTrue(json.contains("\"perCurrencyAlertSettings\""))
        assertTrue(json.contains("\"balanceAlertEnabled\": true"))
    }

    @Test
    fun `buildConfig includes notificationSelectedWallets`() {
        prefs.showTotalBalanceInNotification = false
        prefs.setNotificationWalletSelected("acc1", "CNY", true)
        val json = ConfigManager.buildConfig(context, mockKeyMgr, prefs, includeTokens = false)
        assertTrue(json.contains("\"notificationSelectedWallets\""))
        assertTrue(json.contains("\"showTotalBalance\": false"))
    }

    // ═══════════════════════════════════════════════════════════
    // buildConfig — empty accounts
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `buildConfig with no accounts produces empty accounts array`() {
        val emptyKeyMgr = mockk<ApiKeyManager>()
        every { emptyKeyMgr.getAccounts() } returns emptyList()
        val json = ConfigManager.buildConfig(context, emptyKeyMgr, prefs, includeTokens = false)
        assertTrue(json.contains("\"accounts\": []"))
    }

    // ═══════════════════════════════════════════════════════════
    // applyConfig — basic application (mockk for ApiKeyManager)
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `applyConfig imports accounts and settings`() {
        prefs.resetAll()
        val appliedAccounts = mutableListOf<AccountInfo>()
        val mockMgr = mockk<ApiKeyManager>(relaxed = true)
        every { mockMgr.addAccount(any(), any()) } answers {
            val info = AccountInfo(id = "", label = firstArg<String>(), apiKey = secondArg<String>())
            appliedAccounts.add(info)
            info
        }

        val config = AppConfig(
            version = 1,
            exportedAt = "2026-07-09T12:00:00",
            appVersion = "1.2.0",
            accounts = listOf(
                AccountInfo(id = "new1", label = "Imported1", apiKey = "sk-newkey12345678")
            ),
            settings = ConfigSettings(
                refreshIntervalSeconds = 90, alertEnabled = true, alertThreshold = 30f,
                changeAlertEnabled = true, changeAlertThreshold = 10f,
                changeAlertPeriodMinutes = 60, logMaxEntries = 200,
                snoozeDurationMinutes = 45
            )
        )

        val skipped = ConfigManager.applyConfig(config, mockMgr, prefs)

        assertEquals(0, skipped)
        assertEquals(1, appliedAccounts.size)
        assertEquals("Imported1", appliedAccounts[0].label)
        assertEquals(90, prefs.refreshIntervalSeconds)
        assertTrue(prefs.alertEnabled)
        assertEquals(200, prefs.logMaxEntries)
        assertEquals(45, prefs.snoozeDurationMinutes)
    }

    @Test
    fun `applyConfig skips redacted accounts`() {
        prefs.resetAll()
        val appliedAccounts = mutableListOf<String>()
        val mockMgr = mockk<ApiKeyManager>(relaxed = true)
        every { mockMgr.addAccount(any(), any()) } answers {
            appliedAccounts.add(firstArg<String>())
            AccountInfo(id = "", label = firstArg(), apiKey = secondArg())
        }

        val config = AppConfig(
            version = 1, exportedAt = "2026-07-09T12:00:00", appVersion = "1.0",
            accounts = listOf(
                AccountInfo(id = "r1", label = "Redacted1", apiKey = "sk-a****t901"),
                AccountInfo(id = "r2", label = "Redacted2", apiKey = "[REDACTED]"),
                AccountInfo(id = "v1", label = "Valid", apiKey = "sk-validkey12345")
            ),
            settings = ConfigSettings(
                refreshIntervalSeconds = 30, alertEnabled = false, alertThreshold = 0f,
                changeAlertEnabled = false, changeAlertThreshold = 0f,
                changeAlertPeriodMinutes = 0, logMaxEntries = 100
            )
        )

        val skipped = ConfigManager.applyConfig(config, mockMgr, prefs)
        assertEquals(2, skipped)
        assertEquals(1, appliedAccounts.size)
        assertEquals("Valid", appliedAccounts[0])
    }

    @Test
    fun `applyConfig calls clearAll before importing`() {
        prefs.resetAll()
        val mockMgr = mockk<ApiKeyManager>(relaxed = true)
        val config = AppConfig(
            version = 1, exportedAt = "2026-07-09T12:00:00", appVersion = "1.0",
            accounts = emptyList(),
            settings = ConfigSettings(
                refreshIntervalSeconds = 30, alertEnabled = false, alertThreshold = 0f,
                changeAlertEnabled = false, changeAlertThreshold = 0f,
                changeAlertPeriodMinutes = 0, logMaxEntries = 100
            )
        )

        ConfigManager.applyConfig(config, mockMgr, prefs)
        verify(exactly = 1) { mockMgr.clearAll() }
    }

    @Test
    fun `applyConfig applies perCurrencyAlertSettings via mock`() {
        prefs.resetAll()
        val mockMgr = mockk<ApiKeyManager>(relaxed = true)

        val config = AppConfig(
            version = 1, exportedAt = "2026-07-09T12:00:00", appVersion = "1.0",
            accounts = emptyList(),
            settings = ConfigSettings(
                refreshIntervalSeconds = 30, alertEnabled = false, alertThreshold = 0f,
                changeAlertEnabled = false, changeAlertThreshold = 0f,
                changeAlertPeriodMinutes = 0, logMaxEntries = 100,
                perCurrencyAlertSettings = listOf(
                    PerCurrencyAlertSetting("acc1", "CNY", true, false),
                    PerCurrencyAlertSetting("acc1", "USD", false, true)
                )
            )
        )

        ConfigManager.applyConfig(config, mockMgr, prefs)
        assertTrue(prefs.isBalanceAlertEnabled("acc1", "CNY"))
        assertFalse(prefs.isChangeAlertEnabled("acc1", "CNY"))
        assertFalse(prefs.isBalanceAlertEnabled("acc1", "USD"))
        assertTrue(prefs.isChangeAlertEnabled("acc1", "USD"))
    }

    @Test
    fun `applyConfig applies notification wallet selections via mock`() {
        prefs.resetAll()
        val mockMgr = mockk<ApiKeyManager>(relaxed = true)

        val config = AppConfig(
            version = 1, exportedAt = "2026-07-09T12:00:00", appVersion = "1.0",
            accounts = emptyList(),
            settings = ConfigSettings(
                refreshIntervalSeconds = 30, alertEnabled = false, alertThreshold = 0f,
                changeAlertEnabled = false, changeAlertThreshold = 0f,
                changeAlertPeriodMinutes = 0, logMaxEntries = 100,
                showTotalBalance = false,
                notificationSelectedWallets = listOf(
                    NotificationWalletSelection("acc1", "CNY"),
                    NotificationWalletSelection("acc2", "USD")
                )
            )
        )

        ConfigManager.applyConfig(config, mockMgr, prefs)
        assertFalse(prefs.showTotalBalanceInNotification)
        assertTrue(prefs.isNotificationWalletSelected("acc1", "CNY"))
        assertTrue(prefs.isNotificationWalletSelected("acc2", "USD"))
    }

    @Test
    fun `applyConfig returns zero skipped when all accounts valid`() {
        prefs.resetAll()
        val mockMgr = mockk<ApiKeyManager>(relaxed = true)
        val mockReturn = AccountInfo(id = "mock-id", label = "mock", apiKey = "sk-mock")
        val accountsAdded = mutableListOf<String>()
        every { mockMgr.addAccount(any(), any()) } answers {
            accountsAdded.add(firstArg<String>())
            mockReturn
        }

        val config = AppConfig(
            version = 1, exportedAt = "2026-07-09T12:00:00", appVersion = "1.0",
            accounts = listOf(
                AccountInfo(id = "a1", label = "Acc1", apiKey = "sk-keyone111111"),
                AccountInfo(id = "a2", label = "Acc2", apiKey = "sk-keytwo222222")
            ),
            settings = ConfigSettings(
                refreshIntervalSeconds = 30, alertEnabled = false, alertThreshold = 0f,
                changeAlertEnabled = false, changeAlertThreshold = 0f,
                changeAlertPeriodMinutes = 0, logMaxEntries = 100
            )
        )

        val skipped = ConfigManager.applyConfig(config, mockMgr, prefs)
        assertEquals(0, skipped)
        assertEquals(2, accountsAdded.size)
    }
}
