package com.balancesentinel.app

import android.app.Application
import android.app.LocaleManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.LocaleList
import com.balancesentinel.app.data.repository.ApiKeyManager
import com.balancesentinel.app.data.repository.DailySummaryStore
import com.balancesentinel.app.data.repository.RawRecordStore
import com.balancesentinel.app.data.repository.WidgetPrefs
import com.balancesentinel.app.widget.BalanceWidgetDataStore

class DeepSeekApp : Application() {
    override fun onCreate() {
        super.onCreate()
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

        createNotificationChannel()

        // 恢复用户语言偏好（未设置则跟随系统）
        val savedLanguage = WidgetPrefs(this).language
        if (savedLanguage != null) {
            val localeManager = getSystemService(LocaleManager::class.java)
            localeManager.applicationLocales = LocaleList.forLanguageTags(savedLanguage)
            CrashLogger.breadcrumb("App", "Locale restored: $savedLanguage")
        }

        CrashLogger.breadcrumb("App", "onCreate complete")
    }

    /**
     * 执行数据迁移
     * 1. 迁移旧版单Key
     * 2. 迁移账户ID（4字节 -> 8字节）
     */
    private fun migrateDataIfNeeded() {
        try {
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
                BalanceWidgetDataStore.migrateAccountIds(this, migrationMap)

                CrashLogger.breadcrumb("App", "Account ID migration complete")
            }
        } catch (e: Exception) {
            CrashLogger.logNonFatal("App", e)
        }
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // 前台 Service 常驻通知（低优先级，不弹横幅）
        val svcChannel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_service_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_service_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(svcChannel)

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
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID_ALERT = "balance_alert_channel"
        const val CHANNEL_ID_USAGE = "balance_usage_channel"
        const val NOTIFICATION_ID_GROUP_SUMMARY = 3002
    }
}
