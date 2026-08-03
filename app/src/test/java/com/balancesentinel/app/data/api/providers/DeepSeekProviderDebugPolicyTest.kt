package com.balancesentinel.app.data.api.providers

import com.balancesentinel.app.data.debug.DebugInterceptor
import org.junit.Assert.assertEquals
import org.junit.Test

class DeepSeekProviderDebugPolicyTest {
    // Mutation caught: ignoring the explicit policy on the provider-owned usage client.
    @Test
    fun `provider usage client installs one debug interceptor and zero in release`() {
        val debug = DeepSeekProvider(debuggable = true).getClientWithDebug("acct")
        val release = DeepSeekProvider(debuggable = false).getClientWithDebug("acct")

        assertEquals(1, debug.interceptors.count { it is DebugInterceptor })
        assertEquals(0, release.interceptors.count { it is DebugInterceptor })
    }
}
