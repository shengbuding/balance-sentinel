package com.balancesentinel.app.ui.viewmodel

import android.app.Application
import android.content.Context
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
import com.balancesentinel.app.service.ServiceStartResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max

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
    private val startMonitoring: (Context, Boolean) -> ServiceStartResult = { context, userInitiated ->
        ForegroundServiceStarter(userInitiated = userInitiated).start(context)
    },
    private val stopMonitoring: (Context) -> Unit = { context ->
        BalanceRefreshService.stopMonitoring(context)
    }
) : AndroidViewModel(application) {
    private val mutableUiState = kotlinx.coroutines.flow.MutableStateFlow(CapabilityUiState())
    val uiState: StateFlow<CapabilityUiState> = mutableUiState.asStateFlow()

    private val eventChannel = Channel<CapabilityUiEvent>(Channel.UNLIMITED)
    val events = eventChannel.receiveAsFlow()
    private var startRequestedForCurrentDesiredState = false
    private var nextStartAttemptAt = 0L
    private var retryJob: Job? = null

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
                nextStartAttemptAt = 0L
                retryJob?.cancel()
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
                    else -> {
                        requestExactAlarmResolutionIfNeeded(capabilities)
                        startIfAllowed(capabilities, userInitiated = true, force = true)
                    }
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
            if (granted && loadMonitoringState().desired) {
                requestExactAlarmResolutionIfNeeded(capabilities)
                startIfAllowed(capabilities, userInitiated = true, force = true)
            }
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

    fun requestExactAlarmResolution() {
        eventChannel.trySend(CapabilityUiEvent.OpenExactAlarmSettings)
    }

    fun requestBatteryOptimizationResolution() {
        eventChannel.trySend(CapabilityUiEvent.OpenBatteryOptimizationSettings)
    }

    private suspend fun requestExactAlarmResolutionIfNeeded(
        capabilities: com.balancesentinel.app.platform.permission.CapabilitySnapshot
    ) {
        if (capabilities[AppCapability.EXACT_ALARM] == CapabilityAvailability.NOT_GRANTED) {
            eventChannel.send(CapabilityUiEvent.OpenExactAlarmSettings)
        }
    }

    private fun startIfAllowed(
        capabilities: com.balancesentinel.app.platform.permission.CapabilitySnapshot,
        userInitiated: Boolean,
        force: Boolean = false
    ) {
        if (!capabilities.monitoringAllowed || startRequestedForCurrentDesiredState) return
        val now = System.currentTimeMillis()
        if (!force && now < nextStartAttemptAt) return

        when (val result = runCatching {
            startMonitoring(getApplication(), userInitiated)
        }.getOrElse {
            ServiceStartResult.Failed(it.message ?: "foreground_service_start_failed")
        }) {
            ServiceStartResult.Started -> {
                startRequestedForCurrentDesiredState = true
                nextStartAttemptAt = Long.MAX_VALUE
                retryJob?.cancel()
            }
            is ServiceStartResult.Deferred -> {
                startRequestedForCurrentDesiredState = false
                nextStartAttemptAt = max(now + 1_000L, result.retryAt)
                scheduleRetry(nextStartAttemptAt - now)
            }
            is ServiceStartResult.Failed -> {
                // A failed launch must not permanently wedge this ViewModel.
                // Explicitly toggling monitoring or recreating the activity can
                // try again, while avoiding a tight retry loop in the UI.
                startRequestedForCurrentDesiredState = false
                nextStartAttemptAt = now + FAILED_START_RETRY_DELAY_MS
            }
        }
    }

    private fun scheduleRetry(delayMillis: Long) {
        retryJob?.cancel()
        retryJob = viewModelScope.launch {
            delay(delayMillis.coerceAtLeast(1_000L))
            refreshInternal()
        }
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
        // A process restart destroys the foreground service, but not the
        // user's persisted monitoring intent. Reconcile it while the activity
        // is visible so Android permits the foreground-service start again.
        // Platform-limited/paused sessions are deliberately left to the
        // bounded-session controller instead of bypassing its budget guard.
        if (
            monitoring.desired &&
            monitoring.observedState !in setOf(
                com.balancesentinel.app.data.local.monitoring.MonitoringObservedState.PLATFORM_LIMITED,
                com.balancesentinel.app.data.local.monitoring.MonitoringObservedState.PAUSED
            )
        ) {
            startIfAllowed(capabilities, userInitiated = false)
        }
        mutableUiState.value = CapabilityUiState(
            loading = false,
            capabilities = capabilities,
            monitoringDesired = monitoring.desired,
            monitoringObservedState = monitoring.observedState
        )
    }

    private companion object {
        const val FAILED_START_RETRY_DELAY_MS = 30_000L
    }
}
