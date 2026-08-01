package com.balancesentinel.app.data.model

import com.balancesentinel.app.data.api.ProviderType
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AccountInfoTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
    }

    @Test
    fun `serialization with usageScript`() {
        val account = AccountInfo(
            id = "test1",
            label = "Test Account",
            apiKey = "sk-test",
            providerType = ProviderType.CUSTOM,
            usageScript = "({request:{url:\"test\"}})"
        )
        val jsonStr = json.encodeToString(AccountInfo.serializer(), account)
        println("JSON: $jsonStr")
        
        val decoded = json.decodeFromString(AccountInfo.serializer(), jsonStr)
        assertNotNull("usageScript should not be null", decoded.usageScript)
        assertEquals("({request:{url:\"test\"}})", decoded.usageScript)
    }

    @Test
    fun `serialization without usageScript`() {
        val account = AccountInfo(
            id = "test2",
            label = "Test Account 2",
            apiKey = "sk-test2",
            providerType = ProviderType.CUSTOM
        )
        val jsonStr = json.encodeToString(AccountInfo.serializer(), account)
        println("JSON: $jsonStr")
        
        val decoded = json.decodeFromString(AccountInfo.serializer(), jsonStr)
        assertNull("usageScript should be null", decoded.usageScript)
    }

    @Test
    fun `deserialization from old JSON without usageScript field`() {
        val oldJson = """{"id":"test3","label":"Old Account","apiKey":"sk-old","providerType":"CUSTOM"}"""
        val decoded = json.decodeFromString(AccountInfo.serializer(), oldJson)
        assertNull("usageScript should be null for old JSON", decoded.usageScript)
    }

    @Test
    fun `old account json defaults revision and script policy`() {
        val old = """{"id":"a","label":"A","apiKey":"sk-12345678901"}"""

        val account = Json { ignoreUnknownKeys = true }.decodeFromString<AccountInfo>(old)

        assertEquals(0L, account.revision)
        assertTrue(account.usageScriptEnabled)
        assertTrue(account.authorizedScriptOrigins.isEmpty())
    }

    @Test
    fun `toConfig carries account metadata credentials and script policy`() {
        val account = AccountInfo(
            id = "account-1",
            label = "Account",
            apiKey = "sk-key",
            extraCredentials = mapOf("secretKey" to "secret"),
            extraSettings = mapOf("baseUrl" to "https://api.example.com"),
            usageScript = "script",
            usageScriptEnabled = false,
            authorizedScriptOrigins = setOf("https://b.example.com/", "https://a.example.com")
        )

        val config = account.toConfig()

        assertEquals("Account", config.credentials["accountLabel"])
        assertEquals("secret", config.credentials["secretKey"])
        assertEquals("script", config.settings["usageScript"])
        assertEquals("false", config.settings["usageScriptEnabled"])
        assertEquals("https://a.example.com,https://b.example.com", config.settings["authorizedScriptOrigins"])
    }
}
