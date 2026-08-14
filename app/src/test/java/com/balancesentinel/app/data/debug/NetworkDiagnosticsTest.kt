package com.balancesentinel.app.data.debug

import android.content.Context
import android.net.ConnectivityManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkDiagnosticsTest {

    @Test
    fun `report text contains only populated network fields`() {
        val report = NetworkDiagnosticsSnapshot(
            activeNetwork = true,
            transports = listOf("VPN", "WIFI"),
            internetCapable = true,
            validated = false,
            vpn = true,
            dnsServerCount = 2
        ).toReportText()

        assertTrue(report.contains("activeNetwork=true"))
        assertTrue(report.contains("transports=VPN,WIFI"))
        assertTrue(report.contains("internetCapable=true"))
        assertTrue(report.contains("validated=false"))
        assertTrue(report.contains("vpn=true"))
        assertTrue(report.contains("dnsServerCount=2"))
        assertFalse(report.contains("metered="))
        assertFalse(report.contains("proxyConfigured="))
    }

    @Test
    fun `missing connectivity manager is represented as a diagnostic failure`() {
        val context = mockk<Context>()
        every { context.getSystemService(ConnectivityManager::class.java) } returns null

        val snapshot = NetworkDiagnostics.capture(context)

        assertFalse(snapshot.activeNetwork)
        assertEquals("ConnectivityManagerUnavailable", snapshot.errorType)
    }

    @Test
    fun `missing active network is not treated as a collector failure`() {
        val manager = mockk<ConnectivityManager>()
        every { manager.activeNetwork } returns null
        val context = mockk<Context>()
        every { context.getSystemService(ConnectivityManager::class.java) } returns manager

        val snapshot = NetworkDiagnostics.capture(context)

        assertFalse(snapshot.activeNetwork)
        assertEquals(null, snapshot.errorType)
    }
}
