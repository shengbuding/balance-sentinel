package com.balancesentinel.app.data.network

import java.security.KeyStore
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient

/**
 * 放宽态下额外纳入的信任锚。
 *
 * 只拆掉 OkHttp 的 CertificatePinner 并不足以让 VPN / 抓包代理的通路恢复：
 * 代理换上它自己的证书后，**系统 CA 校验**同样会拒绝 —— 用户装进「用户证书」
 * 的那张代理 CA 对 targetSdk ≥ 24 且声明了 networkSecurityConfig 的应用默认不生效
 * （base-config 只列了 system 信任锚）。
 *
 * 这里显式用 AndroidCAStore 建一个 X509TrustManager：它同时包含系统根与用户证书，
 * 从而绕过上面的默认限制。**不做「信任一切」** —— 证书链仍须回到真实 CA 并照常验证
 * 有效期、主机名与签名。
 */
internal object RelaxedTrustInstaller {

    fun install(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        val trustManager = trustManagerOrNull() ?: return builder
        val context = runCatching {
            SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
        }.getOrNull() ?: return builder
        return builder.sslSocketFactory(context.socketFactory, trustManager)
    }

    /** 设备 CA 库不可用时返回 null，调用方保持平台默认的行为。 */
    internal fun trustManagerOrNull(): X509TrustManager? = runCatching { androidTrustManager() }.getOrNull()

    private fun androidTrustManager(): X509TrustManager {
        val store = KeyStore.getInstance("AndroidCAStore")
        store.load(null, null)
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(store)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }
}
