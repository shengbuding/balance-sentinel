package com.balancesentinel.app.data.debug

import org.junit.Assert.assertFalse
import org.junit.Test

class DebugCapturePolicyReleaseTest {
    @Test
    fun `release variant disables capture by default`() {
        assertFalse(DebugCapturePolicy.enabled())
    }
}
