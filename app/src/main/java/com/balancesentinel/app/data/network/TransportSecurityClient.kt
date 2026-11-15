package com.balancesentinel.app.data.network

import okhttp3.OkHttpClient

/**
 * 把 [TransportSecurityPolicy] 落到每一条出站 client 上的唯一入口。
 *
 * 严格态（默认）：装了 pinner。pinner 按 host 生效，只有 api.deepseek.com 会被校验，
 * 因此同一个 client 服务任意供应商都是安全的。
 *
 * 放宽态：拆掉 pinner，并把用户自己安装的 CA 纳入信任锚（见 [RelaxedTrustInstaller]）。
 * 两步缺一不可 —— 只拆 pinner 时，MITM 代理的证书仍会被系统 CA 校验拒绝，
 * 表现为「关了开关照样 Network request failed」。
 */
internal object TransportSecurityClient {

    fun apply(builder: OkHttpClient.Builder): OkHttpClient.Builder =
        if (TransportSecurityPolicy.certificatePinningEnabled) {
            builder.certificatePinner(DeepSeekTlsPolicy.certificatePinner)
        } else {
            RelaxedTrustInstaller.install(builder)
        }
}
