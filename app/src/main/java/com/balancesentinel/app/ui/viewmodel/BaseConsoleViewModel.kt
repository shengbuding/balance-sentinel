package com.balancesentinel.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.balancesentinel.app.data.console.DebugLogger
import com.balancesentinel.app.data.console.auth.ConsoleAuthProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 控制台通用 UI 状态
 */
data class ConsoleUiState(
    /** 是否显示登录页 */
    val showLogin: Boolean = true,
    /** 是否已登录 */
    val isLoggedIn: Boolean = false,
    /** 用户邮箱（脱敏） */
    val userEmail: String? = null,
    /** 会话信息（调试用） */
    val sessionInfo: String = ""
)

/**
 * 控制台 ViewModel 基类
 * 抽取 DeepSeek 和 Mimo 的公共逻辑
 */
abstract class BaseConsoleViewModel(
    application: Application,
    protected val authProvider: ConsoleAuthProvider,
    private val tag: String
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ConsoleUiState())
    val uiState: StateFlow<ConsoleUiState> = _uiState.asStateFlow()

    init {
        checkLoginStatus()
    }

    /**
     * 检查登录状态
     */
    private fun checkLoginStatus() {
        viewModelScope.launch {
            try {
                val isLoggedIn = authProvider.isLoggedIn()
                val maskedEmail = getMaskedEmail()

                _uiState.value = _uiState.value.copy(
                    showLogin = !isLoggedIn,
                    isLoggedIn = isLoggedIn,
                    userEmail = maskedEmail,
                    sessionInfo = buildSessionInfo()
                )

                if (isLoggedIn) {
                    DebugLogger.log("[$tag] 已登录，用户: $maskedEmail")
                } else {
                    DebugLogger.log("[$tag] 未登录")
                }
            } catch (e: Exception) {
                DebugLogger.log("[$tag] 检查登录状态失败: ${e.message}")
                _uiState.value = _uiState.value.copy(showLogin = true)
            }
        }
    }

    /**
     * 登录成功回调
     */
    fun onLoginSuccess(cookies: Map<String, String>, email: String?) {
        viewModelScope.launch {
            try {
                DebugLogger.log("[$tag] 登录成功，Cookie数量: ${cookies.size}")
                authProvider.saveSession(cookies, email)

                _uiState.value = _uiState.value.copy(
                    showLogin = false,
                    isLoggedIn = true,
                    userEmail = email ?: getMaskedEmail(),
                    sessionInfo = buildSessionInfo()
                )
            } catch (e: Exception) {
                DebugLogger.log("[$tag] 保存会话失败: ${e.message}")
            }
        }
    }

    /**
     * 退出登录
     */
    fun logout() {
        viewModelScope.launch {
            try {
                authProvider.logout()
                _uiState.value = ConsoleUiState()
                DebugLogger.log("[$tag] 已退出登录")
            } catch (e: Exception) {
                DebugLogger.log("[$tag] 退出登录失败: ${e.message}")
            }
        }
    }

    /**
     * 获取脱敏邮箱（子类可覆写）
     */
    protected open fun getMaskedEmail(): String? {
        return null
    }

    /**
     * 构建会话信息
     */
    private suspend fun buildSessionInfo(): String {
        return try {
            val session = authProvider.getSession()
            if (session == null) {
                "会话: null"
            } else {
                buildString {
                    appendLine("Cookie数量: ${session.cookies.size}")
                    appendLine("Token: ${session.token?.take(20)}...")
                    appendLine("邮箱: ${session.email?.let { maskEmail(it) } ?: "未知"}")
                    appendLine("过期: ${if (session.isExpired()) "是" else "否"}")
                }
            }
        } catch (e: Exception) {
            "错误: ${e.message}"
        }
    }

    /**
     * 邮箱脱敏
     */
    protected fun maskEmail(email: String?): String? {
        if (email.isNullOrBlank()) return null
        val atIndex = email.indexOf('@')
        if (atIndex <= 0) return "***"
        val prefix = email.first() + "***"
        val domain = email.substring(atIndex)
        return prefix + domain
    }
}
