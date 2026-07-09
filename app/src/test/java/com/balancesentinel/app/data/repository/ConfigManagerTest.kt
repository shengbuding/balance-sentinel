package com.balancesentinel.app.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.model.AccountInfo
import io.mockk.every
import io.mockk.mockk
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
}
