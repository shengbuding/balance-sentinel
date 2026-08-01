package com.balancesentinel.app.data.api.providers

import com.balancesentinel.app.data.api.ConfigFieldStorage
import com.balancesentinel.app.data.api.ProviderType
import org.junit.Assert.assertEquals
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
}
