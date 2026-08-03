package com.balancesentinel.app.data.debug

import org.junit.Assert.assertTrue
import org.junit.Test

class DebugCapturePolicyDebugTest {
    @Test
    fun `debug variant enables capture by default`() {
        assertTrue(DebugCapturePolicy.enabled())
    }
}
