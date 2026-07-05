package com.balancesentinel.app.data.util

import android.util.Log

/**
 * 安全日志封装 — 自动脱敏 API Key，绝不传递原始异常对象给 Log。
 *
 * 红线：
 * - 绝不输出 "sk-" 开头的 API Key 到 logcat
 * - 异常对象不直接传给 android.util.Log（OkHttp 异常的 toString() 可能打印完整 header）
 * - 所有消息经过 sanitize() 脱敏后再输出
 */
object Logger {

    private val API_KEY_REGEX = Regex("""sk-[a-zA-Z0-9]{10,}""")
    private const val REDACTED = "sk-***"

    fun d(tag: String, msg: String) {
        Log.d(tag, sanitize(msg))
    }

    fun i(tag: String, msg: String) {
        Log.i(tag, sanitize(msg))
    }

    fun w(tag: String, msg: String, throwable: Throwable? = null) {
        val safe = sanitize(msg)
        if (throwable != null) {
            val st = safeThrowable(throwable)
            Log.w(tag, "$safe | ${st.type}: ${st.message}")
        } else {
            Log.w(tag, safe)
        }
    }

    fun e(tag: String, msg: String, throwable: Throwable? = null) {
        val safe = sanitize(msg)
        if (throwable != null) {
            val st = safeThrowable(throwable)
            Log.e(tag, "$safe | ${st.type}: ${st.message}")
        } else {
            Log.e(tag, safe)
        }
    }

    /**
     * 脱敏：替换 API Key 为 sk-***
     */
    private fun sanitize(text: String): String {
        return API_KEY_REGEX.replace(text, REDACTED)
    }

    /**
     * 安全提取异常信息 — 不调用 throwable.toString()（OkHttp 异常可能打印完整 header）
     */
    private fun safeThrowable(t: Throwable): SafeInfo {
        val message = sanitize(t.message ?: "(no message)")
        return SafeInfo(t.javaClass.simpleName, message)
    }

    private data class SafeInfo(val type: String, val message: String)
}
