package com.balancesentinel.app.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.model.AccountInfo
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ApiKeyManagerTest {

    private lateinit var context: Context
    private lateinit var testPrefsName: String
    private lateinit var manager: ApiKeyManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // unique prefs name per test to avoid cross-test pollution
        testPrefsName = "test_api_keys_${System.nanoTime()}"
        manager = ApiKeyManager(context, context.getSharedPreferences(testPrefsName, Context.MODE_PRIVATE))
    }

    @After
    fun tearDown() {
        context.getSharedPreferences(testPrefsName, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    // ═══════════════════════════════════════════════════════════
    // computeId
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `computeId is deterministic`() {
        val id1 = manager.computeId("sk-test-key-12345")
        val id2 = manager.computeId("sk-test-key-12345")
        assertEquals(id1, id2)
    }

    @Test
    fun `computeId returns different ids for different keys`() {
        val id1 = manager.computeId("sk-key-alpha")
        val id2 = manager.computeId("sk-key-beta")
        assertNotEquals(id1, id2)
    }

    @Test
    fun `computeId is case sensitive`() {
        val id1 = manager.computeId("sk-ABC")
        val id2 = manager.computeId("sk-abc")
        assertNotEquals(id1, id2)
    }

    @Test
    fun `computeId returns 8-char hex string`() {
        val id = manager.computeId("sk-anything")
        assertEquals(8, id.length)
        assertTrue(id.all { it in "0123456789abcdef" })
    }

    @Test
    fun `computeId trims whitespace from key`() {
        val idTrimmed = manager.computeId("sk-key")
        val idWithSpace = manager.computeId("  sk-key  ")
        assertEquals(idTrimmed, idWithSpace)
    }

    // ═══════════════════════════════════════════════════════════
    // addAccount
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `addAccount creates new account`() {
        val account = manager.addAccount("主账户", "sk-main-key")
        assertEquals("主账户", account.label)
        assertEquals("sk-main-key", account.apiKey)
        assertTrue(account.id.isNotEmpty())

        val accounts = manager.getAccounts()
        assertEquals(1, accounts.size)
        assertEquals("主账户", accounts[0].label)
    }

    @Test
    fun `addAccount trims whitespace from label and key`() {
        val account = manager.addAccount("  测试账户  ", "  sk-trim-key  ")
        assertEquals("测试账户", account.label)
        assertEquals("sk-trim-key", account.apiKey)
    }

    @Test
    fun `addAccount duplicate key updates label instead of creating new`() {
        manager.addAccount("原名", "sk-dup-key")
        manager.addAccount("新名", "sk-dup-key")

        val accounts = manager.getAccounts()
        assertEquals(1, accounts.size)
        assertEquals("新名", accounts[0].label)
    }

    @Test
    fun `addAccount multiple different keys creates separate accounts`() {
        manager.addAccount("账户A", "sk-key-a")
        manager.addAccount("账户B", "sk-key-b")
        manager.addAccount("账户C", "sk-key-c")

        assertEquals(3, manager.getAccounts().size)
    }

    // ═══════════════════════════════════════════════════════════
    // removeAccount
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `removeAccount deletes matching account`() {
        val acc = manager.addAccount("待删除", "sk-delete-me")
        assertEquals(1, manager.getAccounts().size)

        manager.removeAccount(acc.id)
        assertEquals(0, manager.getAccounts().size)
    }

    @Test
    fun `removeAccount no-op for unknown id`() {
        manager.addAccount("保留", "sk-keep")
        manager.removeAccount("nonexistent")
        assertEquals(1, manager.getAccounts().size)
    }

    @Test
    fun `removeAccount deletes correct one when multiple exist`() {
        val a = manager.addAccount("A", "sk-key-a")
        val b = manager.addAccount("B", "sk-key-b")
        val c = manager.addAccount("C", "sk-key-c")

        manager.removeAccount(b.id)

        val remaining = manager.getAccounts()
        assertEquals(2, remaining.size)
        assertTrue(remaining.any { it.id == a.id })
        assertTrue(remaining.any { it.id == c.id })
        assertFalse(remaining.any { it.id == b.id })
    }

    // ═══════════════════════════════════════════════════════════
    // renameAccount
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `renameAccount updates label`() {
        val acc = manager.addAccount("旧名", "sk-rename-key")
        manager.renameAccount(acc.id, "新名")

        val updated = manager.getAccount(acc.id)
        assertEquals("新名", updated?.label)
        assertEquals("sk-rename-key", updated?.apiKey) // key unchanged
    }

    @Test
    fun `renameAccount trims whitespace from new label`() {
        val acc = manager.addAccount("原名", "sk-rename-key")
        manager.renameAccount(acc.id, "  清爽名  ")
        assertEquals("清爽名", manager.getAccount(acc.id)?.label)
    }

    @Test
    fun `renameAccount no-op for unknown id`() {
        manager.addAccount("A", "sk-a")
        manager.renameAccount("nonexistent", "new")
        assertEquals(1, manager.getAccounts().size)
        assertEquals("A", manager.getAccounts()[0].label)
    }

    // ═══════════════════════════════════════════════════════════
    // getAccounts / getAccount / hasAccounts
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `getAccounts returns empty list when no data`() {
        assertTrue(manager.getAccounts().isEmpty())
    }

    @Test
    fun `getAccounts returns empty list for corrupt JSON`() {
        context.getSharedPreferences(testPrefsName, Context.MODE_PRIVATE)
            .edit().putString("accounts", "not valid json {{{").commit()

        val accounts = manager.getAccounts()
        assertTrue(accounts.isEmpty())
    }

    @Test
    fun `getAccounts returns empty list for JSON with wrong type`() {
        context.getSharedPreferences(testPrefsName, Context.MODE_PRIVATE)
            .edit().putString("accounts", "42").commit()

        val accounts = manager.getAccounts()
        assertTrue(accounts.isEmpty())
    }

    @Test
    fun `getAccount returns correct account by id`() {
        val acc = manager.addAccount("目标", "sk-target")
        val found = manager.getAccount(acc.id)
        assertEquals("目标", found?.label)
    }

    @Test
    fun `getAccount returns null for unknown id`() {
        manager.addAccount("A", "sk-a")
        assertNull(manager.getAccount("nonexistent"))
    }

    @Test
    fun `hasAccounts returns false when empty`() {
        assertFalse(manager.hasAccounts())
    }

    @Test
    fun `hasAccounts returns true after adding`() {
        manager.addAccount("A", "sk-a")
        assertTrue(manager.hasAccounts())
    }

    // ═══════════════════════════════════════════════════════════
    // clearAll
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `clearAll removes all accounts`() {
        manager.addAccount("A", "sk-a")
        manager.addAccount("B", "sk-b")
        assertEquals(2, manager.getAccounts().size)

        manager.clearAll()
        assertTrue(manager.getAccounts().isEmpty())
        assertFalse(manager.hasAccounts())
    }

    @Test
    fun `clearAll is safe when already empty`() {
        manager.clearAll()
        assertTrue(manager.getAccounts().isEmpty())
    }

    // ═══════════════════════════════════════════════════════════
    // migrateLegacyKeyIfNeeded
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `migrateLegacyKeyIfNeeded migrates when legacy key exists and no accounts`() {
        context.getSharedPreferences(testPrefsName, Context.MODE_PRIVATE)
            .edit().putString("deepseek_api_key", "sk-legacy-key").commit()

        manager.migrateLegacyKeyIfNeeded()

        val accounts = manager.getAccounts()
        assertEquals(1, accounts.size)
        assertEquals("sk-legacy-key", accounts[0].apiKey)
    }

    @Test
    fun `migrateLegacyKeyIfNeeded no-op when accounts already exist`() {
        manager.addAccount("已有账户", "sk-existing")

        context.getSharedPreferences(testPrefsName, Context.MODE_PRIVATE)
            .edit().putString("deepseek_api_key", "sk-legacy-key").commit()

        manager.migrateLegacyKeyIfNeeded()

        val accounts = manager.getAccounts()
        assertEquals(1, accounts.size)
        assertEquals("sk-existing", accounts[0].apiKey)
    }

    @Test
    fun `migrateLegacyKeyIfNeeded no-op when legacy key is blank`() {
        context.getSharedPreferences(testPrefsName, Context.MODE_PRIVATE)
            .edit().putString("deepseek_api_key", "   ").commit()

        manager.migrateLegacyKeyIfNeeded()

        assertTrue(manager.getAccounts().isEmpty())
    }

    @Test
    fun `migrateLegacyKeyIfNeeded no-op when no legacy key`() {
        manager.migrateLegacyKeyIfNeeded()
        assertTrue(manager.getAccounts().isEmpty())
    }

    @Test
    fun `migrateLegacyKeyIfNeeded removes legacy key after migration`() {
        context.getSharedPreferences(testPrefsName, Context.MODE_PRIVATE)
            .edit().putString("deepseek_api_key", "sk-legacy-key").commit()

        manager.migrateLegacyKeyIfNeeded()

        val legacyRemaining = context.getSharedPreferences(testPrefsName, Context.MODE_PRIVATE)
            .getString("deepseek_api_key", null)
        assertNull(legacyRemaining)
    }
}
