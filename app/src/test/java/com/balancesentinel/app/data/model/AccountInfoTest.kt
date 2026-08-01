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
}
