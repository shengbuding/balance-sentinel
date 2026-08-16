package com.balancesentinel.app.ui.viewmodel

import com.balancesentinel.app.data.local.monitoring.MonitoringObservedState
import com.balancesentinel.app.platform.permission.CapabilitySnapshot

data class CapabilityUiState(
    val loading: Boolean = true,
    val capabilities: CapabilitySnapshot = CapabilitySnapshot.Loading,
    val monitoringDesired: Boolean = false,
    val monitoringObservedState: MonitoringObservedState = MonitoringObservedState.STOPPED,
    val errorMessage: String? = null
) {
    val monitoringEffective: Boolean
        get() = monitoringDesired && capabilities.monitoringAllowed &&
            monitoringObservedState != MonitoringObservedState.STOPPED &&
            monitoringObservedState != MonitoringObservedState.PLATFORM_LIMITED &&
            monitoringObservedState != MonitoringObservedState.PAUSED
}

sealed interface CapabilityUiEvent {
    data object RequestNotificationPermission : CapabilityUiEvent
    data object OpenAppSettings : CapabilityUiEvent
    data object OpenExactAlarmSettings : CapabilityUiEvent
    data object OpenBatteryOptimizationSettings : CapabilityUiEvent
}
