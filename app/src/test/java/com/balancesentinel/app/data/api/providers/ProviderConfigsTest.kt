package com.balancesentinel.app.data.api.providers

import com.balancesentinel.app.data.api.ConfigFieldStorage
import com.balancesentinel.app.data.api.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderConfigsTest {

    @Test
    fun `credential fields declare their persistence storage`() {
        val zhipuFields = ProviderConfigs.getConfigFields(ProviderType.ZHIPU).associateBy { it.key }
        val customFields = ProviderConfigs.getConfigFields(ProviderType.CUSTOM).associateBy { it.key }

        assertEquals(ConfigFieldStorage.PRIMARY_CREDENTIAL, zhipuFields.getValue("apiKey").storage)
        assertEquals(ConfigFieldStorage.EXTRA_CREDENTIAL, zhipuFields.getValue("secretKey").storage)
        assertEquals(ConfigFieldStorage.SETTING, customFields.getValue("baseUrl").storage)
    }

    @Test
    fun `custom URL fields accept http and https but reject malformed values`() {
        val validHttp = mapOf("apiKey" to "custom-key", "baseUrl" to "http://api.example.com/v1")
        val validHttps = mapOf("apiKey" to "custom-key", "baseUrl" to "https://api.example.com/v1")
        val malformed = mapOf("apiKey" to "custom-key", "baseUrl" to "not-a-url")

        assertTrue(ProviderConfigs.validateFieldValues(ProviderType.CUSTOM, validHttp))
        assertTrue(ProviderConfigs.validateFieldValues(ProviderType.CUSTOM, validHttps))
        assertFalse(ProviderConfigs.validateFieldValues(ProviderType.CUSTOM, malformed))
    }
}
