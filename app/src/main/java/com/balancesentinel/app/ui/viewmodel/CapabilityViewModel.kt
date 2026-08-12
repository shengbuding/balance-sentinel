package com.balancesentinel.app.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.balancesentinel.app.data.local.monitoring.MonitoringStateEntity
import com.balancesentinel.app.data.repository.WidgetPrefs
import com.balancesentinel.app.platform.permission.AndroidCapabilityChecker
import com.balancesentinel.app.platform.permission.AppCapability
import com.balancesentinel.app.platform.permission.CapabilityAvailability
import com.balancesentinel.app.platform.permission.CapabilityChecker
import com.balancesentinel.app.platform.permission.CapabilityPermissionHistory
import com.balancesentinel.app.platform.permission.WidgetCapabilityPermissionHistory
import com.balancesentinel.app.service.BalanceRefreshService
import com.balancesentinel.app.service.ForegroundServiceStarter
import com.balancesentinel.app.service.MonitoringStateStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class CapabilityViewModel @JvmOverloads constructor(
    application: Application,
    private val checker: CapabilityChecker = AndroidCapabilityChecker(application),
    private val permissionHistory: CapabilityPermissionHistory =
        WidgetCapabilityPermissionHistory(WidgetPrefs(application)),
    private val loadMonitoringState: suspend () -> MonitoringStateEntity = {
        MonitoringStateStore.from(application).get()
    },
    private val setMonitoringDesired: suspend (Boolean) -> Unit = { desired ->
        MonitoringStateStore.from(application).setDesired(desired)
    },
    private val startMonitoring: (Context) -> Unit = { context ->
        ForegroundServiceStarter(userInitiated = true).start(context)
    },
    private val stopMonitoring: (Context) -> Unit = { context ->
        context.stopService(Intent(context, BalanceRefreshService::class.java))
    }
) : AndroidViewModel(application) {
    private val mutableUiState = kotlinx.coroutines.flow.MutableStateFlow(CapabilityUiState())
    val uiState: StateFlow<CapabilityUiState> = mutableUiState.asStateFlow()

    private val eventChannel = Channel<CapabilityUiEvent>(Channel.UNLIMITED)
    val events = eventChannel.receiveAsFlow()
    private var startRequestedForCurrentDesiredState = false

    init {
        refresh()
    }

    fun refresh() {
        launchWithErrorProjection {
            refreshInternal()
        }
    }

    fun setMonitoringEnabled(enabled: Boolean) {
        launchWithErrorProjection {
            setMonitoringDesired(enabled)
            if (!enabled) {
                startRequestedForCurrentDesiredState = false
                stopMonitoring(getApplication())
            } else {
                val capabilities = checker.read(permissionHistory.notificationPermanentlyDenied)
                when (capabilities[AppCapability.NOTIFICATIONS]) {
                    CapabilityAvailability.NOT_GRANTED -> {
                        permissionHistory.notificationRequested = true
                        eventChannel.send(CapabilityUiEvent.RequestNotificationPermission)
                    }
                    CapabilityAvailability.PERMANENTLY_DENIED -> {
                        eventChannel.send(CapabilityUiEvent.OpenAppSettings)
                    }
                    else -> startIfAllowed(capabilities)
                }
            }
            refreshInternal()
        }
    }

    fun onNotificationPermissionResult(granted: Boolean, canAskAgain: Boolean) {
        permissionHistory.notificationRequested = true
        permissionHistory.notificationPermanentlyDenied = !granted && !canAskAgain
        launchWithErrorProjection {
            val capabilities = checker.read(permissionHistory.notificationPermanentlyDenied)
            if (granted && loadMonitoringState().desired) startIfAllowed(capabilities)
            refreshInternal()
        }
    }

    fun requestPermissionResolution() {
        val event = if (permissionHistory.notificationPermanentlyDenied) {
            CapabilityUiEvent.OpenAppSettings
        } else {
            permissionHistory.notificationRequested = true
            CapabilityUiEvent.RequestNotificationPermission
        }
        eventChannel.trySend(event)
    }

    private fun startIfAllowed(capabilities: com.balancesentinel.app.platform.permission.CapabilitySnapshot) {
        if (!capabilities.monitoringAllowed || startRequestedForCurrentDesiredState) return
        startRequestedForCurrentDesiredState = true
        startMonitoring(getApplication())
    }

    private fun launchWithErrorProjection(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableUiState.value = mutableUiState.value.copy(
                    loading = false,
                    errorMessage = error.message ?: "capability_operation_failed"
                )
            }
        }
    }

    private suspend fun refreshInternal() {
        val monitoring = loadMonitoringState()
        val capabilities = checker.read(permissionHistory.notificationPermanentlyDenied)
        mutableUiState.value = CapabilityUiState(
            loading = false,
            capabilities = capabilities,
            monitoringDesired = monitoring.desired,
            monitoringObservedState = monitoring.observedState
        )
    }
}
