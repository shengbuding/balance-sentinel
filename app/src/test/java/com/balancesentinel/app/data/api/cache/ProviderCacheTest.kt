package com.balancesentinel.app.data.api.cache

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.api.UnifiedBalance
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProviderCacheTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("provider_cache", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("provider_cache", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `clear is immediately visible to every cache instance`() {
        val writer = ProviderCache(context)
        val reader = ProviderCache(context)
        val balance = UnifiedBalance(ProviderType.DEEPSEEK, "account-1", true, emptyList())

        writer.put(ProviderType.DEEPSEEK, "account-1", balance)
        reader.get(ProviderType.DEEPSEEK, "account-1")
        writer.clear(ProviderType.DEEPSEEK, "account-1")

        assertNull(reader.get(ProviderType.DEEPSEEK, "account-1"))
    }
}
