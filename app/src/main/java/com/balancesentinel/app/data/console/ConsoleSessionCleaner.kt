package com.balancesentinel.app.data.console

import android.webkit.CookieManager
import android.webkit.WebStorage
import com.balancesentinel.app.data.console.store.ConsoleStore
import com.balancesentinel.app.ui.console.ConsolePlatform

fun interface ConsoleWebStorage {
    fun deleteOrigin(origin: String)
}

interface ConsoleCookieManager {
    fun removeAllCookies(completion: (Boolean) -> Unit)
    fun flush()
}

class ConsoleSessionCleaner(
    private val store: ConsoleStore,
    private val webStorage: ConsoleWebStorage = AndroidConsoleWebStorage,
    private val cookies: ConsoleCookieManager = AndroidConsoleCookieManager
) {
    fun logout(platform: ConsolePlatform, completion: () -> Unit = {}) {
        store.removeSession(platform.id)
        ConsoleOriginPolicy.createOrNull(platform)
            ?.webStorageOrigins()
            .orEmpty()
            .forEach(webStorage::deleteOrigin)
        cookies.removeAllCookies {
            cookies.flush()
            completion()
        }
    }
}

private object AndroidConsoleWebStorage : ConsoleWebStorage {
    override fun deleteOrigin(origin: String) {
        WebStorage.getInstance().deleteOrigin(origin)
    }
}

private object AndroidConsoleCookieManager : ConsoleCookieManager {
    override fun removeAllCookies(completion: (Boolean) -> Unit) {
        CookieManager.getInstance().removeAllCookies(completion)
    }

    override fun flush() {
        CookieManager.getInstance().flush()
    }
}
