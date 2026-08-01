package com.balancesentinel.app.data.api.providers

import com.balancesentinel.app.data.api.ConfigField
import com.balancesentinel.app.data.api.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderConfigsTest {

    @Test
    fun `provider fields identify their persistence location`() {
        val apiKey = ProviderConfigs.getConfigFields(ProviderType.DEEPSEEK).single { it.key == "apiKey" }
        val secretKey = ProviderConfigs.getConfigFields(ProviderType.ZHIPU).single { it.key == "secretKey" }
        val baseUrl = ProviderConfigs.getConfigFields(ProviderType.CUSTOM).single { it.key == "baseUrl" }

        assertEquals("PRIMARY_CREDENTIAL", apiKey.storageName())
        assertEquals("EXTRA_CREDENTIAL", secretKey.storageName())
        assertEquals("SETTING", baseUrl.storageName())
    }

    private fun ConfigField.storageName(): String? =
        javaClass.methods.firstOrNull { it.name == "getStorage" }?.invoke(this)?.toString()
}
