package com.balancesentinel.app.data.repository

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import com.balancesentinel.app.CrashLogger
import com.balancesentinel.app.data.console.store.ConsoleStore
import com.balancesentinel.app.data.credentials.CredentialStore
import com.balancesentinel.app.data.local.WalletDatabase
import com.balancesentinel.app.data.local.settings.AppSettingsEntity
import com.balancesentinel.app.data.refresh.RefreshMutationBarrier
import com.balancesentinel.app.widget.BalanceWidgetDataStore
import com.balancesentinel.app.widget.WidgetConfigStore
import com.balancesentinel.app.widget.WidgetErrorLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class AppResetStage {
    STARTED,
    CREDENTIALS_CLEARED,
    ROOM_RESET,
    EXTERNAL_STORES_CLEARED
}

internal class AppResetCheckpointStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun current(): AppResetStage? = preferences.getString(KEY_STAGE, null)?.let(AppResetStage::valueOf)

    fun begin() {
        if (current() == null) write(AppResetStage.STARTED)
    }

    fun write(stage: AppResetStage) {
        check(preferences.edit().putString(KEY_STAGE, stage.name).commit())
    }

    fun complete() {
        check(preferences.edit().remove(KEY_STAGE).commit())
    }

    companion object {
        private const val FILE_NAME = "balance_sentinel_reset_checkpoint"
        private const val KEY_STAGE = "stage"

        fun hasPending(context: Context): Boolean = AppResetCheckpointStore(context).current() != null
    }
}

/** Idempotent, startup-resumable whole-application reset. */
class AppResetCoordinator internal constructor(
    private val context: Context,
    private val database: WalletDatabase,
    private val credentialStore: CredentialStore,
    private val settingsRepository: SettingsRepository,
    private val checkpointStore: AppResetCheckpointStore = AppResetCheckpointStore(context),
    private val legacyCredentialClear: suspend () -> Unit = {
        ApiKeyManager(context).clearAllForReset()
    },
    private val externalStoreClear: suspend () -> Unit = {
        WidgetErrorLogger.clear(context)
        CrashLogger.clear(context.applicationContext as android.app.Application)
        BalanceWidgetDataStore.clearAll(context)
        WidgetConfigStore.clearAll(context)
        WidgetPrefs(context).resetAll()
        RefreshScheduler.resetAlarmCounters(context)
        ConsoleStore(context).clearAll()
        clearWebViewData(context)
    },
    private val afterStage: suspend (AppResetStage) -> Unit = {}
) {
    constructor(
        context: Context,
        database: WalletDatabase,
        credentialStore: CredentialStore,
        settingsRepository: SettingsRepository
    ) : this(
        context.applicationContext,
        database,
        credentialStore,
        settingsRepository,
        AppResetCheckpointStore(context.applicationContext)
    )

    suspend fun reset() = RefreshMutationBarrier.withAccountMutation(null) {
        MUTEX.withLock {
            withContext(Dispatchers.IO) {
                checkpointStore.begin()
                resumeLocked()
            }
        }
    }

    suspend fun recoverIfNeeded() = RefreshMutationBarrier.withAccountMutation(null) {
        MUTEX.withLock {
            withContext(Dispatchers.IO) {
                if (checkpointStore.current() != null) resumeLocked()
            }
        }
    }

    private suspend fun resumeLocked() {
        while (true) {
            when (checkpointStore.current() ?: return) {
                AppResetStage.STARTED -> {
                    legacyCredentialClear()
                    credentialStore.clear()
                    advance(AppResetStage.CREDENTIALS_CLEARED)
                }
                AppResetStage.CREDENTIALS_CLEARED -> {
                    database.clearAllTables()
                    val timestamp = System.currentTimeMillis()
                    settingsRepository.publishSnapshot(
                        SettingsSnapshot(AppSettingsEntity(updatedAt = timestamp)),
                        timestamp
                    )
                    advance(AppResetStage.ROOM_RESET)
                }
                AppResetStage.ROOM_RESET -> {
                    externalStoreClear()
                    advance(AppResetStage.EXTERNAL_STORES_CLEARED)
                }
                AppResetStage.EXTERNAL_STORES_CLEARED -> {
                    checkpointStore.complete()
                    return
                }
            }
        }
    }

    private suspend fun advance(stage: AppResetStage) {
        checkpointStore.write(stage)
        afterStage(stage)
    }

    private companion object {
        val MUTEX = Mutex()

        fun clearWebViewData(context: Context) {
            runCatching {
                CookieManager.getInstance().apply {
                    removeAllCookies(null)
                    flush()
                }
                WebStorage.getInstance().deleteAllData()
                listOf(context.cacheDir.resolve("WebView")) +
                    context.cacheDir.listFiles().orEmpty().filter {
                        it.name.contains("webview", ignoreCase = true)
                    }
            }.getOrNull()?.distinct()?.forEach { file -> runCatching { file.deleteRecursively() } }
        }
    }
}
