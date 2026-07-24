package com.balancesentinel.app.ui.viewmodel

import android.app.Application
import com.balancesentinel.app.data.console.auth.MimoConsoleAuth

/**
 * Mimo 控制台 ViewModel
 * 继承 BaseConsoleViewModel，仅注入 Mimo 的 AuthProvider
 */
class MimoViewModel(application: Application) : BaseConsoleViewModel(
    application = application,
    authProvider = MimoConsoleAuth(application),
    tag = "MimoVM"
) {
    override fun getMaskedEmail(): String? {
        return (authProvider as MimoConsoleAuth).getMaskedEmail()
    }
}
