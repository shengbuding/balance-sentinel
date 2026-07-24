package com.balancesentinel.app.ui.viewmodel

import android.app.Application
import com.balancesentinel.app.data.console.auth.DeepSeekConsoleAuth

/**
 * DeepSeek 控制台 ViewModel
 * 继承 BaseConsoleViewModel，仅注入 DeepSeek 的 AuthProvider
 */
class DeepSeekConsoleViewModel(application: Application) : BaseConsoleViewModel(
    application = application,
    authProvider = DeepSeekConsoleAuth(application),
    tag = "DeepSeekVM"
) {
    override fun getMaskedEmail(): String? {
        return (authProvider as DeepSeekConsoleAuth).getMaskedEmail()
    }
}
