package com.balancesentinel.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

class DeepSeekApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)
        createNotificationChannel()
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
