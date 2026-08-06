package com.balancesentinel.app.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.model.AccountDraft
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AccountRepositoryTest {
    @Test
    fun apiKeyChangeKeepsStableAccountUuidThroughExistingManagerEntryPoint() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("task3-red", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val manager = ApiKeyManager(context, prefs)
        val created = manager.addAccount("Legacy", "key-before")

        manager.saveAccount(
            created.id,
            AccountDraft(
                label = "Legacy",
                apiKey = "key-after",
                providerType = ProviderType.DEEPSEEK
            )
        )

        assertEquals("Account identity must not be derived from API key", created.id, manager.getAccounts().single().id)
    }
}
