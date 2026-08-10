package com.balancesentinel.app.data.network

import okhttp3.CertificatePinner
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

/**
 * The transport trust policy for the DeepSeek API.
 *
 * Pins are SPKI SHA-256 values collected from the live api.deepseek.com chain
 * on 2026-08-10 (see the source comments in network_security_config.xml).
 */
object DeepSeekTlsPolicy {
    const val HOST = "api.deepseek.com"
    const val CURRENT_PIN = "sha256/IS95653JtE1/bNto9qa5E/NHBmBbRDmfaLM+btVVTCk="
    const val BACKUP_PIN = "sha256/eLVG2Nq6lNlY482AlhlwwHqvL3TsvXMFJx2ycA8gZpQ="

    val pins: List<String> = listOf(CURRENT_PIN, BACKUP_PIN)

    val certificatePinner: CertificatePinner = CertificatePinner.Builder()
        .add(HOST, *pins.toTypedArray())
        .build()

    fun apply(builder: OkHttpClient.Builder): OkHttpClient.Builder =
        builder.certificatePinner(certificatePinner)

    fun isDeepSeek(url: HttpUrl): Boolean = url.host == HOST
}

/**
 * Construction seam used by the API clients. It is intentionally a no-op
 * until the pinning behavior is enabled in the implementation commit.
 */
internal object DeepSeekTlsPolicyAdapter {
    fun configure(builder: OkHttpClient.Builder): OkHttpClient.Builder =
        DeepSeekTlsPolicy.apply(builder)
}
