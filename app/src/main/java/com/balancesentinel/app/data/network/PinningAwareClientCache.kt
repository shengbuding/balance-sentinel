package com.balancesentinel.app.data.network

import okhttp3.OkHttpClient
import java.util.concurrent.atomic.AtomicReference

/**
 * 按当前传输策略缓存 [OkHttpClient]。

 * 证书固定只能在 client 层配置，而「强制 HTTPS」开关可以在运行中改变；
 * 如果 client 在对象构造时就固定下来，用户关掉开关后仍会拿着带 pin 的旧 client
 * 继续失败，必须重启应用才行。这里按「强制 / 放宽」两种状态各缓存一份，
 * 策略一变就重建，既不牺牲复用，也让开关当场生效。
 *
 * 内部只保存最近一次构建的结果，因为策略来回切换是低频操作。
 */
internal class PinningAwareClientCache(
    private val build: () -> OkHttpClient
) {
    private val cached = AtomicReference<Entry?>(null)

    private class Entry(val pinned: Boolean, val client: OkHttpClient)

    fun current(): OkHttpClient {
        val pinned = TransportSecurityPolicy.certificatePinningEnabled
        cached.get()?.let { entry ->
            if (entry.pinned == pinned) return entry.client
        }
        val client = build()
        cached.set(Entry(pinned, client))
        return client
    }
}
