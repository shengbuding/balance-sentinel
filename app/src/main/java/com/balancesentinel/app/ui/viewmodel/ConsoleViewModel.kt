package com.balancesentinel.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.balancesentinel.app.data.console.DebugLogger
import com.balancesentinel.app.data.console.ConsoleSessionCleaner
import com.balancesentinel.app.data.console.store.ConsoleSession
import com.balancesentinel.app.data.console.store.ConsoleStore
import com.balancesentinel.app.ui.console.ConsolePlatform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 控制台 UI 状态
 */
data class ConsoleUiState(
    val isLoading: Boolean = false,
    val isLoggedIn: Boolean = false,
    val session: ConsoleSession? = null,
    val error: String? = null
)

/**
 * 控制台 ViewModel
 * 处理单个平台的登录和会话管理
 */
class ConsoleViewModel(
    application: Application,
    val platform: ConsolePlatform,
    private val store: ConsoleStore = ConsoleStore(application),
    private val sessionCleaner: ConsoleSessionCleaner = ConsoleSessionCleaner(store)
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ConsoleUiState())
    val uiState: StateFlow<ConsoleUiState> = _uiState.asStateFlow()

    companion object {
        private const val TAG = "ConsoleVM"
    }

    init {
        checkLoginStatus()
    }

    /**
     * 检查登录状态
     */
    private fun checkLoginStatus() {
        viewModelScope.launch {
            try {
                val session = store.getValidSession(platform.id)
                val isLoggedIn = session != null

                _uiState.value = _uiState.value.copy(
                    isLoggedIn = isLoggedIn,
                    session = session
                )

                DebugLogger.log("[$TAG] Check login for ${platform.name} (id=${platform.id}): isLoggedIn=$isLoggedIn, cookies=${session?.cookies?.size ?: 0}")
            } catch (e: Exception) {
                DebugLogger.log("[$TAG] Check login failed: ${e.message}")
            }
        }
    }

    /**
     * 登录成功回调
     */
    fun onLoginSuccess(cookies: Map<String, String>, localStorage: Map<String, String> = emptyMap(), email: String?) {
        viewModelScope.launch {
            try {
                val session = ConsoleSession(
                    cookies = cookies,
                    localStorage = localStorage,
                    email = email
                )
                store.saveSession(platform.id, session)

                _uiState.value = _uiState.value.copy(
                    isLoggedIn = true,
                    session = session
                )

                DebugLogger.log("[$TAG] Login success for ${platform.name} (id=${platform.id}), cookies: ${cookies.size}, localStorage: ${localStorage.size}")
            } catch (e: Exception) {
                DebugLogger.log("[$TAG] Save session failed: ${e.message}")
            }
        }
    }

    /**
     * 退出登录（清除 session）
     */
    fun logout(completion: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                DebugLogger.log("[$TAG] Logout started for ${platform.name} (id=${platform.id})")

                sessionCleaner.logout(platform) {
                    _uiState.value = ConsoleUiState()
                    DebugLogger.log("[$TAG] Logout completed")
                    completion()
                }
            } catch (e: Exception) {
                DebugLogger.log("[$TAG] Logout failed: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    /**
     * 刷新状态
     */
    fun refresh() {
        checkLoginStatus()
    }

    class Factory(
        private val application: Application,
        private val platform: ConsolePlatform
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(ConsoleViewModel::class.java)) {
                return ConsoleViewModel(application, platform) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
