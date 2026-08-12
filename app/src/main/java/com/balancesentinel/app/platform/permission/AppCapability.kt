package com.balancesentinel.app.platform.permission

enum class AppCapability {
    NOTIFICATIONS,
    FOREGROUND_SERVICE,
    DATA_SYNC_SESSION,
    EXACT_ALARM
}

enum class CapabilityAvailability {
    AVAILABLE,
    NOT_GRANTED,
    PERMANENTLY_DENIED,
    PLATFORM_LIMITED,
    NOT_REQUIRED;

    val allowsMonitoring: Boolean
        get() = this == AVAILABLE || this == NOT_REQUIRED
}

data class CapabilitySnapshot(
    private val values: Map<AppCapability, CapabilityAvailability>
) {
    operator fun get(capability: AppCapability): CapabilityAvailability =
        values[capability] ?: CapabilityAvailability.PLATFORM_LIMITED

    val monitoringAllowed: Boolean
        get() = listOf(
            AppCapability.NOTIFICATIONS,
            AppCapability.FOREGROUND_SERVICE,
            AppCapability.DATA_SYNC_SESSION
        ).all { this[it].allowsMonitoring }

    companion object {
        val Loading = CapabilitySnapshot(
            AppCapability.entries.associateWith { CapabilityAvailability.PLATFORM_LIMITED }
        )
    }
}

fun interface CapabilityChecker {
    fun read(notificationPermanentlyDenied: Boolean): CapabilitySnapshot
}

interface CapabilityPermissionHistory {
    var notificationRequested: Boolean
    var notificationPermanentlyDenied: Boolean
}
