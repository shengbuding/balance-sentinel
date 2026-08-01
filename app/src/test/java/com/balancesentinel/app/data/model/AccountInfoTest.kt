package com.balancesentinel.app.data.model

import com.balancesentinel.app.data.api.ProviderType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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

    // ── RED: revision and script policy backward-compatibility ──

    @Test
    fun `serialized account JSON includes revision field`() {
        val account = AccountInfo(id = "r1", label = "Rev", apiKey = "sk-rev-key")
        val jsonString = json.encodeToString(AccountInfo.serializer(), account)
        val jsonObj = json.parseToJsonElement(jsonString) as JsonObject
        assertTrue(
            "Account JSON should contain 'revision' key for backward-compat defaults",
            jsonObj.containsKey("revision")
        )
        assertEquals("0", jsonObj["revision"].toString())
    }

    @Test
    fun `serialized account JSON includes usageScriptEnabled field`() {
        val account = AccountInfo(id = "s1", label = "Script", apiKey = "sk-script-key")
        val jsonString = json.encodeToString(AccountInfo.serializer(), account)
        val jsonObj = json.parseToJsonElement(jsonString) as JsonObject
        assertTrue(
            "Account JSON should contain 'usageScriptEnabled' key",
            jsonObj.containsKey("usageScriptEnabled")
        )
        assertEquals("true", jsonObj["usageScriptEnabled"].toString())
    }

    @Test
    fun `serialized account JSON includes authorizedScriptOrigins field`() {
        val account = AccountInfo(id = "a1", label = "Auth", apiKey = "sk-auth-key")
        val jsonString = json.encodeToString(AccountInfo.serializer(), account)
        val jsonObj = json.parseToJsonElement(jsonString) as JsonObject
        assertTrue(
            "Account JSON should contain 'authorizedScriptOrigins' key",
            jsonObj.containsKey("authorizedScriptOrigins")
        )
        assertEquals("[]", jsonObj["authorizedScriptOrigins"].toString())
    }

    @Test
    fun `toConfig preserves complete account provider metadata`() {
        val account = AccountInfo(
            id = "account-1",
            label = "Primary account",
            apiKey = "sk-account-key",
            providerType = ProviderType.CUSTOM,
            extraCredentials = mapOf("secretKey" to "secret-value"),
            extraSettings = mapOf("baseUrl" to "https://api.example.com"),
            usageScript = "return balance",
            usageScriptEnabled = false,
            authorizedScriptOrigins = linkedSetOf("https://b.example.com/", "https://a.example.com")
        )

        val config = account.toConfig()

        assertEquals("Primary account", config.credentials["accountLabel"])
        assertEquals("secret-value", config.credentials["secretKey"])
        assertEquals("return balance", config.settings["usageScript"])
        assertEquals("false", config.settings["usageScriptEnabled"])
        assertEquals("https://a.example.com,https://b.example.com", config.settings["authorizedScriptOrigins"])
    }
}
