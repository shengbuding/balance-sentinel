package com.balancesentinel.app

import android.app.Application
import android.app.LocaleManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Notification
import android.content.Context
import android.os.LocaleList
import com.balancesentinel.app.data.refresh.RefreshGateway
import com.balancesentinel.app.data.refresh.RefreshRuntime
import com.balancesentinel.app.data.repository.ApiKeyManager
import com.balancesentinel.app.data.repository.AppResetCheckpointStore
import com.balancesentinel.app.data.repository.AppResetCoordinator
import com.balancesentinel.app.data.repository.AccountMutationRecovery
import com.balancesentinel.app.data.repository.RoomAccountMutationCoordinator
import com.balancesentinel.app.data.repository.RoomAccountMutationRecovery
import com.balancesentinel.app.data.repository.RoomConfigImportCoordinator
import com.balancesentinel.app.data.migration.LegacyAccountMigration
import com.balancesentinel.app.data.migration.LegacyDataMigration
import com.balancesentinel.app.data.migration.LegacyDataVerifier
import com.balancesentinel.app.data.migration.LegacyStoresDataSource
import com.balancesentinel.app.data.local.WalletDatabaseProvider
import com.balancesentinel.app.data.credentials.EncryptedPreferencesCredentialStore
import com.balancesentinel.app.data.credentials.DataCorruptionException
import com.balancesentinel.app.data.util.Logger
import com.balancesentinel.app.data.repository.DailySummaryStore
import com.balancesentinel.app.data.repository.RawRecordStore
import com.balancesentinel.app.data.repository.UsageDataStore
import com.balancesentinel.app.data.repository.WidgetPrefs
import com.balancesentinel.app.data.repository.LegacySettingsMigration
import com.balancesentinel.app.data.repository.SettingsRepository
import com.balancesentinel.app.data.repository.SettingsRepositoryProvider
import com.balancesentinel.app.data.repository.WidgetPrefsLegacySettingsSource
import com.balancesentinel.app.widget.BalanceWidgetDataStore
import com.balancesentinel.app.service.MonitoringStateStore
import com.balancesentinel.app.receiver.KeepAliveReceiver
import com.balancesentinel.app.work.MidnightMaintenanceDependencies
import com.balancesentinel.app.work.MidnightWorkPolicy
import com.balancesentinel.app.work.MidnightWorkSchedulingGate
import com.balancesentinel.app.work.MidnightWorkScheduler
import com.balancesentinel.app.work.RefreshWorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.UUID
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.Configuration
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import com.balancesentinel.app.work.MonitoringHealthWorker

open class DeepSeekApp : Application(), Configuration.Provider {

    /** Stable for one process and intentionally changes after process death. */
    val processSessionId: String = UUID.randomUUID().toString()

    internal var legacyMigrationRunner: suspend () -> Unit = {
        legacyAccountMigration().run()
    }

    internal var legacyDataMigrationRunner: suspend () -> Unit = {
        legacyDataMigration().run()
    }

    internal var accountMutationRecoveryRunner: suspend () -> Unit = {
        roomAccountMutationRecovery().recover()
    }

    internal var configImportRecoveryRunner: suspend () -> Unit = {
        roomConfigImportCoordinator().recover()
    }

    internal var appResetRecoveryRunner: suspend () -> Unit = {
        appResetCoordinator().recoverIfNeeded()
    }

    internal var settingsMigrationRunner: suspend () -> Unit = {
        legacySettingsMigration().migrate()
    }

    private var startupMigrationJob = SupervisorJob()
    private var startupMigrationScope = CoroutineScope(startupMigrationJob + Dispatchers.IO)
    private var backgroundWorkReconcileJob: kotlinx.coroutines.Job? = null

    internal suspend fun cancelStartupMigrationsForTests() {
        startupMigrationJob.cancelAndJoin()
        startupMigrationJob = SupervisorJob()
        startupMigrationScope = CoroutineScope(startupMigrationJob + Dispatchers.IO)
    }

    lateinit var refreshGateway: RefreshGateway
        private set

    lateinit var settingsRepository: SettingsRepository
        private set

    internal var refreshWorkSchedulerFactory: (Context) -> RefreshWorkScheduler = { RefreshWorkScheduler() }

    var credentialCorruption: DataCorruptionException? = null
        private set

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepositoryProvider.get(this)
        if (AppResetCheckpointStore.hasPending(this)) {
            runBlocking(Dispatchers.IO) { appResetRecoveryRunner() }
        }
        refreshGateway = RefreshRuntime.create(this)
        val monitoringDesiredAtStartup = runCatching {
            // Materialize the monitoring projection before any worker/receiver
            // can create its default row. This preserves the old app's
            // automatic foreground-monitoring intent for upgrades, while a
            // fresh install (with no legacy service heartbeat) remains opt-in.
            runBlocking(Dispatchers.IO) {
                MonitoringStateStore.from(this@DeepSeekApp).get().desired
            }
        }.onFailure { error ->
            CrashLogger.logNonFatal("MonitoringState", error)
        }.getOrNull()
        runCatching {
            MidnightWorkSchedulingGate.withLock {
                MidnightWorkScheduler().reconcile(
                    this,
                    zoneId = MidnightMaintenanceDependencies.zoneIdProvider(),
                    policy = MidnightWorkPolicy.KEEP
                )
            }
        }
            .onFailure { error ->
                Logger.w("MidnightWorkScheduler", "startup_reconcile_failed", error)
            }
        launchBackgroundWorkReconcile()
        ensureWorkManager().enqueueUniquePeriodicWork(
            "monitoring-health",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<MonitoringHealthWorker>(15, TimeUnit.MINUTES).build()
        )
        CrashLogger.install(this)

        // Clean up stale downloaded APKs from previous sessions
        try {
            val apkDir = java.io.File(cacheDir, "apk")
            if (apkDir.exists()) {
                apkDir.listFiles()?.forEach { file ->
                    if (file.name.startsWith("update-") && file.name.endsWith(".apk")) {
                        file.delete()
                    }
                }
            }
        } catch (_: Exception) {
            // Non-critical — don't block app startup
        }

        // 执行数据迁移
        migrateDataIfNeeded()
        launchLegacyAccountMigration()

        createNotificationChannel()
        when (monitoringDesiredAtStartup) {
            true -> KeepAliveReceiver.schedule(this)
            false -> KeepAliveReceiver.cancel(this)
            null -> Unit
        }

        // 恢复用户语言偏好（未设置则跟随系统）
        val savedLanguage = WidgetPrefs(this).language
        if (savedLanguage != null) {
            val localeManager = getSystemService(LocaleManager::class.java)
            localeManager.applicationLocales = LocaleList.forLanguageTags(savedLanguage)
            CrashLogger.breadcrumb("App", "Locale restored: $savedLanguage")
        }

        CrashLogger.breadcrumb("App", "onCreate complete")
    }

    private fun ensureWorkManager(): WorkManager {
        try {
            return WorkManager.getInstance(this)
        } catch (_: IllegalStateException) {
            // Robolectric and other hosts may omit WorkManager's manifest initializer.
            WorkManager.initialize(this, workManagerConfiguration)
        }
        return WorkManager.getInstance(this)
    }

    /**
     * 执行数据迁移
     * 1. 迁移旧版单Key
     * 2. 迁移账户ID（4字节 -> 8字节）
     * 3. 清理旧版数据
     */
    internal fun launchBackgroundWorkReconcile() {
        backgroundWorkReconcileJob?.cancel()
        backgroundWorkReconcileJob = startupMigrationScope.launch {
            settingsRepository.snapshot
                .filterIsInstance<com.balancesentinel.app.data.repository.SettingsSnapshotState.Ready>()
                .map { it.value.effectiveBackgroundCadenceSeconds?.toLong() }
                .distinctUntilChanged()
                .collect { interval -> reconcileBackgroundWork(interval) }
        }
    }

    internal fun reconcileBackgroundWork(
        state: com.balancesentinel.app.data.repository.SettingsSnapshotState.Ready
    ) {
        reconcileBackgroundWork(state.value.effectiveBackgroundCadenceSeconds?.toLong())
    }

    private fun reconcileBackgroundWork(interval: Long?) {
        runCatching { refreshWorkSchedulerFactory(this).reconcile(this, interval) }
            .onFailure { CrashLogger.logNonFatal("RefreshWorkScheduler", it) }
    }

    internal fun migrateDataIfNeeded() = migrateDataIfNeeded(::performDataMigration)

    internal fun migrateDataIfNeeded(migration: () -> Unit) {
        try {
            credentialCorruption = null
            migration()
        } catch (error: DataCorruptionException) {
            credentialCorruption = error
            CrashLogger.breadcrumb("App", "Credential corruption blocked startup migration")
            CrashLogger.logNonFatal("App", error)
        } catch (error: Exception) {
            CrashLogger.logNonFatal("App", error)
        }
    }

    /** Construction seam for the resumable Room account migration. Not invoked yet. */
    internal fun legacyAccountMigration(): LegacyAccountMigration = LegacyAccountMigration(
        WalletDatabaseProvider.get(this),
        ApiKeyManager(this).legacyAccountReader(),
        EncryptedPreferencesCredentialStore(this)
    )

    internal fun legacyDataMigration(): LegacyDataMigration {
        val database = WalletDatabaseProvider.get(this)
        return LegacyDataMigration(database, LegacyStoresDataSource(this), LegacyDataVerifier(database))
    }

    internal fun launchLegacyAccountMigration() {
        if (!startupMigrationJob.isActive) {
            startupMigrationJob = SupervisorJob()
            startupMigrationScope = CoroutineScope(startupMigrationJob + Dispatchers.IO)
        }
        startupMigrationScope.launch {
            try {
                legacyMigrationRunner()
                configImportRecoveryRunner()
                accountMutationRecoveryRunner()
                legacyDataMigrationRunner()
                settingsMigrationRunner()
                WidgetPrefs(this@DeepSeekApp).apply {
                    cleanupInvalidEntries()
                    cleanupLegacyIdData()
                }
            } catch (error: DataCorruptionException) {
                credentialCorruption = error
                CrashLogger.breadcrumb("App", "Credential corruption blocked Room migration")
                CrashLogger.logNonFatal("App", error)
            } catch (error: Exception) {
                CrashLogger.logNonFatal("App", error)
            }
        }
    }

    internal fun roomAccountMutationRecovery(): AccountMutationRecovery =
        RoomAccountMutationRecovery(
            RoomAccountMutationCoordinator(
                WalletDatabaseProvider.get(this),
                EncryptedPreferencesCredentialStore(this)
            )
        )

    internal fun roomConfigImportCoordinator() = RoomConfigImportCoordinator(
        WalletDatabaseProvider.get(this),
        EncryptedPreferencesCredentialStore(this),
        settingsRepository
    )

    internal fun appResetCoordinator() = AppResetCoordinator(
        this,
        WalletDatabaseProvider.get(this),
        EncryptedPreferencesCredentialStore(this),
        settingsRepository
    )

    internal fun legacySettingsMigration(): LegacySettingsMigration = LegacySettingsMigration(
        WidgetPrefsLegacySettingsSource(WidgetPrefs(this)),
        settingsRepository,
        resolveAccountId = { legacyId ->
            WalletDatabaseProvider.get(this).accountDao().getAllForMigration()
                .firstOrNull { it.id == legacyId || it.legacyStorageId == legacyId }
                ?.id
        }
    )

    private fun performDataMigration() {
        val apiKeyManager = ApiKeyManager(this)

        // 1. 迁移旧版单Key
        apiKeyManager.migrateLegacyKeyIfNeeded()

        // 2. 迁移账户ID
        val migrationMap = apiKeyManager.migrateAccountIds()
        if (migrationMap.isNotEmpty()) {
            CrashLogger.breadcrumb("App", "Migrating ${migrationMap.size} account IDs")

            // 迁移关联数据
            RawRecordStore.migrateAccountIds(this, migrationMap)
            DailySummaryStore.migrateAccountIds(this, migrationMap)
            UsageDataStore.migrateAccountIds(this, migrationMap)
            BalanceWidgetDataStore.migrateAccountIds(this, migrationMap)

            CrashLogger.breadcrumb("App", "Account ID migration complete")
        }

        // 3. 清理旧版数据
    }

    internal fun createNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // 旧版服务渠道：保留其 ID 以兼容升级后的用户设置。
        val svcChannel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_service_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_service_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(svcChannel)

        // Android 不允许在渠道创建后提升其重要性。使用独立的高重要性
        // 渠道承接升级安装，确保常驻监控通知不会继续受旧渠道的低优先级限制。
        val pinnedChannel = NotificationChannel(
            CHANNEL_ID_PINNED,
            getString(R.string.channel_service_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.channel_service_desc)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(pinnedChannel)
        // 余额预警通知（高优先级，弹横幅）
        val alertChannel = NotificationChannel(
            CHANNEL_ID_ALERT,
            getString(R.string.channel_alert_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.channel_alert_desc)
            setShowBadge(true)
        }
        nm.createNotificationChannel(alertChannel)

        // 用量统计通知（默认优先级，不弹横幅）
        val usageChannel = NotificationChannel(
            CHANNEL_ID_USAGE,
            getString(R.string.channel_usage_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.channel_usage_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(usageChannel)
    }

    companion object {
        const val CHANNEL_ID = "balance_refresh_channel"
        const val CHANNEL_ID_PINNED = "balance_refresh_channel_pinned"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID_ALERT = "balance_alert_channel"
        const val CHANNEL_ID_USAGE = "balance_usage_channel"
        const val NOTIFICATION_ID_GROUP_SUMMARY = 3002
    }
}
