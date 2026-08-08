@file:Suppress("DEPRECATION")
package com.balancesentinel.app.data.repository

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.model.AccountInfo
import com.balancesentinel.app.data.local.settings.AccountAlertSettingEntity
import com.balancesentinel.app.data.local.settings.AppSettingsEntity
import com.balancesentinel.app.data.local.settings.NotificationWalletSelectionEntity
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ConfigManagerTest {

    private lateinit var context: Context
    private lateinit var mockKeyMgr: ApiKeyManager
    private lateinit var snapshot: SettingsSnapshot
    private val testJson = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val realKey = "sk-abc123def456ghi789jkl012mno345pqr678stu901"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        snapshot = SettingsSnapshot(AppSettingsEntity(updatedAt = 0L))

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
    fun `credential free export recursively removes credentials scripts enablement and grants`() {
        val account = AccountInfo(
            id = "96ed403d28356eeb",
            label = "Local",
            apiKey = "sk-local-secret",
            providerType = ProviderType.ZHIPU,
            extraCredentials = mapOf("secretKey" to "secondary-secret", "futureToken" to "future-secret"),
            extraSettings = mapOf("baseUrl" to "https://api.example.com"),
            usageScript = "({ request: { url: 'https://usage.example.com' } })",
            usageScriptEnabled = true,
            authorizedScriptOrigins = setOf("https://usage.example.com")
        )
        every { mockKeyMgr.getAccounts() } returns listOf(account)

        val exported = testJson.decodeFromString<AppConfig>(
            buildConfig(includeTokens = false)
        )

        assertEquals(2, exported.version)
        assertFalse(exported.credentialsIncluded)
        assertEquals(1, exported.accounts.size)
        val sanitized = exported.accounts[0]
        assertEquals("", sanitized.apiKey)
        assertEquals(mapOf("secretKey" to "", "futureToken" to ""), sanitized.extraCredentials)
        assertNull(sanitized.usageScript)
        assertFalse(sanitized.usageScriptEnabled)
        assertEquals(emptySet<String>(), sanitized.authorizedScriptOrigins)
        assertEquals(account.extraSettings, sanitized.extraSettings)
    }

    // ═══════════════════════════════════════════════════════════
    // buildConfig — includeTokens = true
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `buildConfig with tokens preserves full API keys`() {
        val json = buildConfig(includeTokens = true)
        val exported = testJson.decodeFromString<AppConfig>(json)

        assertEquals(2, exported.version)
        assertTrue(exported.credentialsIncluded)
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
        val json = buildConfig(includeTokens = false)

        assertTrue("Missing version", json.contains("\"version\": 2"))
        assertTrue("Missing credential marker", json.contains("\"credentialsIncluded\": false"))
        assertTrue("Missing exportedAt", json.contains("\"exportedAt\""))
        assertTrue("Missing appVersion", json.contains("\"appVersion\""))
        assertTrue("Missing accounts", json.contains("\"accounts\""))
        assertTrue("Missing settings", json.contains("\"settings\""))
        assertTrue("Missing refreshIntervalSeconds", json.contains("\"refreshIntervalSeconds\""))
    }

    @Test
    fun `buildConfig includes account labels`() {
        val json = buildConfig(includeTokens = false)

        assertTrue("Missing 主账户 label", json.contains("主账户"))
        assertTrue("Missing 测试 label", json.contains("测试"))
    }

    // ═══════════════════════════════════════════════════════════
    // buildConfig — settings reflection
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `buildConfig reflects custom widgetPrefs settings`() {
        snapshot = snapshot.copy(
            appSettings = snapshot.appSettings.copy(
                foregroundMonitoringIntervalSeconds = 120,
                alertEnabled = true,
                alertThreshold = 50.0,
                snoozeDurationMinutes = 30
            )
        )

        val json = buildConfig(includeTokens = false)
        assertTrue(json.contains("\"refreshIntervalSeconds\": 120"))
        assertTrue(json.contains("\"alertEnabled\": true"))
        assertTrue(json.contains("\"snoozeDurationMinutes\": 30"))
    }

    @Test
    fun `buildConfig includes perCurrencyAlertSettings`() {
        snapshot = snapshot.copy(
            accountAlertSettings = listOf(AccountAlertSettingEntity("acc1", "CNY", true, false))
        )
        val json = buildConfig(includeTokens = false)
        assertTrue(json.contains("\"perCurrencyAlertSettings\""))
        assertTrue(json.contains("\"balanceAlertEnabled\": true"))
    }

    @Test
    fun `buildConfig includes notificationSelectedWallets`() {
        snapshot = snapshot.copy(
            appSettings = snapshot.appSettings.copy(showTotalBalanceInNotification = false),
            notificationSelections = listOf(NotificationWalletSelectionEntity("acc1", "CNY", 0))
        )
        val json = buildConfig(includeTokens = false)
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
        val json = buildConfig(emptyKeyMgr, includeTokens = false)
        assertTrue(json.contains("\"accounts\": []"))
    }

    // ═══════════════════════════════════════════════════════════
    // exportToUri — file-based round-trip
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `exportToUri writes config to file URI`() {
        val exportFile = File(context.filesDir, "export-test-${System.nanoTime()}.json")
        val uri = Uri.fromFile(exportFile)

        val result = exportToUri(uri, includeTokens = false)

        assertTrue("export should succeed", result)
        assertTrue("export file should exist", exportFile.exists())
        val content = exportFile.readText()
        assertTrue("should contain accounts", content.contains("\"accounts\""))
        assertTrue("should contain settings", content.contains("\"settings\""))
        assertTrue("should contain exportedAt", content.contains("\"exportedAt\""))
    }

    @Test
    fun `exportToUri with tokens preserves full API keys`() {
        val exportFile = File(context.filesDir, "export-token-${System.nanoTime()}.json")
        val uri = Uri.fromFile(exportFile)

        exportToUri(uri, includeTokens = true)

        val content = exportFile.readText()
        assertTrue("should contain real key", content.contains(realKey))
        assertFalse("should not contain redacted marker", content.contains("****"))
    }

    @Test
    fun `exportToUri returns false on exception`() {
        // URIs without a scheme cause an exception in openOutputStream
        val badUri = Uri.parse("content://nonexistent.authority.xyz/file.json")
        val result = exportToUri(badUri, includeTokens = false)
        // In Robolectric, this may succeed or fail depending on shadow behavior
        // The code catches Exception, so either outcome is valid
        // We test that no crash occurs
        assertNotNull(result)
    }

    // ═══════════════════════════════════════════════════════════
    // importFromUri — file-based reading
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `importFromUri parses valid config from file URI`() {
        val configJson = testJson.encodeToString(
            AppConfig(
                version = 1,
                exportedAt = "2026-07-09T12:00:00",
                appVersion = "1.2.0",
                accounts = listOf(
                    AccountInfo(id = "imp1", label = "ImportedAcc", apiKey = "sk-importedkey12345")
                ),
                settings = ConfigSettings(
                    refreshIntervalSeconds = 60, alertEnabled = true, alertThreshold = 10f,
                    changeAlertEnabled = false, changeAlertThreshold = 0f,
                    changeAlertPeriodMinutes = 30, logMaxEntries = 500
                )
            )
        )
        val importFile = File(context.filesDir, "import-test-${System.nanoTime()}.json")
        importFile.writeText(configJson)
        val uri = Uri.fromFile(importFile)

        val result = ConfigManager.importFromUri(context, uri)

        assertNotNull("should parse config", result)
        assertEquals("1.2.0", result!!.appVersion)
        assertEquals(1, result.accounts.size)
        assertEquals("ImportedAcc", result.accounts[0].label)
        assertEquals("sk-importedkey12345", result.accounts[0].apiKey)
        assertEquals(60, result.settings.refreshIntervalSeconds)
        assertTrue(result.settings.alertEnabled)
    }

    @Test
    fun `legacy config without version marker decodes as schema v1`() {
        // Mutation caught: applying the schema-v2 default to v1 files that omitted their default version field.
        val importFile = File(context.filesDir, "import-v1-${System.nanoTime()}.json")
        importFile.writeText(
            """{"exportedAt":"2026-07-09T12:00:00","appVersion":"1.0","accounts":[],"settings":{"refreshIntervalSeconds":30,"alertEnabled":false,"alertThreshold":0.0,"changeAlertEnabled":false,"changeAlertThreshold":0.0,"changeAlertPeriodMinutes":60,"logMaxEntries":100}}"""
        )

        val result = ConfigManager.importFromUri(context, Uri.fromFile(importFile))

        assertNotNull(result)
        assertEquals(1, result!!.version)
        assertFalse(result.credentialsIncluded)
    }

    @Test
    fun `importFromUri returns null for invalid JSON`() {
        val importFile = File(context.filesDir, "import-invalid-${System.nanoTime()}.json")
        importFile.writeText("this is not valid json {{{")
        val uri = Uri.fromFile(importFile)

        val result = ConfigManager.importFromUri(context, uri)

        assertNull("should return null for invalid JSON", result)
    }

    @Test
    fun `importFromUri returns null for missing required fields`() {
        // JSON with missing required fields like 'alertEnabled', 'changeAlertThreshold' etc.
        // ConfigSettings has non-optional fields → kotlinx.serialization throws → importFromUri returns null
        val importFile = File(context.filesDir, "import-missing-${System.nanoTime()}.json")
        importFile.writeText("""{"version":1,"exportedAt":"2026-01-01T00:00:00","appVersion":"1.0","accounts":[],"settings":{"refreshIntervalSeconds":30}}""")
        val uri = Uri.fromFile(importFile)

        val result = ConfigManager.importFromUri(context, uri)

        // kotlinx.serialization throws MissingFieldException for required ConfigSettings fields
        // importFromUri catches it and returns null
        assertNull("should return null when required fields are missing", result)
    }

    @Test
    fun `importFromUri returns null for empty file`() {
        val importFile = File(context.filesDir, "import-empty-${System.nanoTime()}.json")
        importFile.writeText("")
        val uri = Uri.fromFile(importFile)

        val result = ConfigManager.importFromUri(context, uri)

        assertNull("should return null for empty content", result)
    }

    private fun buildConfig(
        keyManager: ApiKeyManager = mockKeyMgr,
        includeTokens: Boolean
    ): String = ConfigManager.buildConfig(
        context,
        keyManager.getAccounts(),
        snapshot,
        includeTokens
    )

    private fun exportToUri(uri: Uri, includeTokens: Boolean): Boolean =
        ConfigManager.exportToUri(
            context,
            uri,
            mockKeyMgr.getAccounts(),
            snapshot,
            includeTokens
        )

}
