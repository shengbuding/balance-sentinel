package com.balancesentinel.app.data.debug

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

data class NetworkDiagnosticsSnapshot(
    val activeNetwork: Boolean,
    val transports: List<String> = emptyList(),
    val internetCapable: Boolean? = null,
    val validated: Boolean? = null,
    val captivePortal: Boolean? = null,
    val metered: Boolean? = null,
    val roaming: Boolean? = null,
    val vpn: Boolean? = null,
    val privateDnsActive: Boolean? = null,
    val privateDnsServerConfigured: Boolean? = null,
    val dnsServerCount: Int? = null,
    val proxyConfigured: Boolean? = null,
    val errorType: String? = null
) {
    fun toReportText(): String = buildString {
        appendLine("  activeNetwork=$activeNetwork")
        if (transports.isNotEmpty()) appendLine("  transports=${transports.joinToString(",")}")
        internetCapable?.let { appendLine("  internetCapable=$it") }
        validated?.let { appendLine("  validated=$it") }
        captivePortal?.let { appendLine("  captivePortal=$it") }
        metered?.let { appendLine("  metered=$it") }
        roaming?.let { appendLine("  roaming=$it") }
        vpn?.let { appendLine("  vpn=$it") }
        privateDnsActive?.let { appendLine("  privateDnsActive=$it") }
        privateDnsServerConfigured?.let { appendLine("  privateDnsServerConfigured=$it") }
        dnsServerCount?.let { appendLine("  dnsServerCount=$it") }
        proxyConfigured?.let { appendLine("  proxyConfigured=$it") }
        errorType?.let { appendLine("  unavailable=$it") }
    }
}

object NetworkDiagnostics {
    fun capture(context: Context): NetworkDiagnosticsSnapshot = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java)
            ?: return NetworkDiagnosticsSnapshot(
                activeNetwork = false,
                errorType = "ConnectivityManagerUnavailable"
            )
        val network = manager.activeNetwork
            ?: return NetworkDiagnosticsSnapshot(activeNetwork = false)
        val capabilities = manager.getNetworkCapabilities(network)
        val linkProperties = manager.getLinkProperties(network)
        val transports = buildList {
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) add("VPN")
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) add("WIFI")
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) add("CELLULAR")
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) add("ETHERNET")
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) == true) add("BLUETOOTH")
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_USB) == true) add("USB")
        }
        NetworkDiagnosticsSnapshot(
            activeNetwork = true,
            transports = transports,
            internetCapable = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            captivePortal = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
            metered = manager.isActiveNetworkMetered,
            roaming = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)?.not(),
            vpn = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
            privateDnsActive = linkProperties?.isPrivateDnsActive,
            privateDnsServerConfigured = linkProperties?.privateDnsServerName != null,
            dnsServerCount = linkProperties?.dnsServers?.size,
            proxyConfigured = linkProperties?.httpProxy != null
        )
    }.getOrElse { error ->
        NetworkDiagnosticsSnapshot(
            activeNetwork = false,
            errorType = error.javaClass.simpleName
        )
    }
}
