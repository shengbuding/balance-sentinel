package com.balancesentinel.app.platform.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.balancesentinel.app.data.repository.WidgetPrefs

class AndroidCapabilityChecker(
    context: Context
) : CapabilityChecker {
    private val appContext = context.applicationContext

    override fun read(notificationPermanentlyDenied: Boolean): CapabilitySnapshot {
        val notification = when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> {
                CapabilityAvailability.NOT_REQUIRED
            }
            hasPermission(Manifest.permission.POST_NOTIFICATIONS) &&
                NotificationManagerCompat.from(appContext).areNotificationsEnabled() -> {
                CapabilityAvailability.AVAILABLE
            }
            notificationPermanentlyDenied -> CapabilityAvailability.PERMANENTLY_DENIED
            else -> CapabilityAvailability.NOT_GRANTED
        }

        val foregroundService = if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.P ||
            hasPermission(Manifest.permission.FOREGROUND_SERVICE)
        ) {
            CapabilityAvailability.AVAILABLE
        } else {
            CapabilityAvailability.NOT_GRANTED
        }

        val dataSync = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            CapabilityAvailability.NOT_REQUIRED
        } else if (hasPermission(Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC)) {
            CapabilityAvailability.AVAILABLE
        } else {
            CapabilityAvailability.PLATFORM_LIMITED
        }

        return CapabilitySnapshot(
            mapOf(
                AppCapability.NOTIFICATIONS to notification,
                AppCapability.FOREGROUND_SERVICE to foregroundService,
                AppCapability.DATA_SYNC_SESSION to dataSync,
                // Exact alarms were retired in Task 17; they must never be requested again.
                AppCapability.EXACT_ALARM to CapabilityAvailability.NOT_REQUIRED
            )
        )
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
}

class WidgetCapabilityPermissionHistory(
    private val prefs: WidgetPrefs
) : CapabilityPermissionHistory {
    override var notificationRequested: Boolean
        get() = prefs.notificationPermissionRequested
        set(value) {
            prefs.notificationPermissionRequested = value
        }

    override var notificationPermanentlyDenied: Boolean
        get() = prefs.notificationPermissionPermanentlyDenied
        set(value) {
            prefs.notificationPermissionPermanentlyDenied = value
        }
}
