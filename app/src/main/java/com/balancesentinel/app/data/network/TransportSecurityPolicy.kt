package com.balancesentinel.app.data.network

import java.util.concurrent.atomic.AtomicBoolean

/**
 * 传输层安全策略的用户可调开关。
 *
 * 应用默认要求所有供应商走 HTTPS，并对 api.deepseek.com 做证书固定（pinning）。
 * 部分用户需要通过 VPN 或抓包代理访问网络，代理会替换证书链，pinning 必然失败，
 * 表现为「开了代理就刷不出余额」。这里提供唯一的进程级开关，由设置页在
 * 3 秒阅读确认后写入。
 *
 * 关闭后同时放宽三件事：
 *  1. 证书固定不再安装；
 *  2. 系统信任锚之外再纳入用户自己安装的 CA（见 [RelaxedTrustInstaller]），
 *     否则 MITM 代理换上的证书仍会被系统 CA 校验拒绝 ——
 *     这一步是「关了开关还是 Network request failed」的另一半原因；
 *  3. 供应商 baseUrl 校验重新接受 http://（见 ProviderConfigs.validateFieldValue）。
 *
 * 平台层的 pin-set 已经整体移除：它是编译期常量，App 里没有任何 API 能在运行时
 * 关掉它，留在 XML 里就等于这个开关永远无效。固定策略现在只由代码层承担。
 *
 * 关闭 **不会** 影响 Android 平台层的明文禁令：network_security_config.xml 的
 * base-config 仍然 cleartextTrafficPermitted="false"。也就是说即便开关关闭，
 * 真正向明文 endpoint 发起请求仍会被系统拦截 —— 这里是「允许配置」而非
 * 「保证可用」，避免给用户错误的安全预期。
 *
 * 该对象不依赖 Context，与 DebugCapturePolicy 采用同样的进程级范式，
 * 便于在没有 Application 的单元测试里直接驱动。
 */
object TransportSecurityPolicy {

    private val relaxed = AtomicBoolean(false)

    /** true = 强制 HTTPS 且启用证书固定（默认）；false = 用户已确认放宽。 */
    val httpsRequired: Boolean
        get() = !relaxed.get()

    /** 证书固定是否生效。与 [httpsRequired] 同源，单独暴露是为了让调用点语义清晰。 */
    val certificatePinningEnabled: Boolean
        get() = httpsRequired

    /** 由 Application 启动与设置页共同调用。 */
    fun update(httpsRequired: Boolean) {
        relaxed.set(!httpsRequired)
    }

    /** 仅供测试复位到默认的「强制」状态。 */
    internal fun reset() {
        relaxed.set(false)
    }
}
