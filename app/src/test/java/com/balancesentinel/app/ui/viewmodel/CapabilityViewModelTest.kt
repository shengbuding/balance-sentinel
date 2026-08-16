package com.balancesentinel.app.ui.viewmodel

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.local.monitoring.MonitoringObservedState
import com.balancesentinel.app.data.local.monitoring.MonitoringStateEntity
import com.balancesentinel.app.platform.permission.AppCapability
import com.balancesentinel.app.platform.permission.CapabilityAvailability
import com.balancesentinel.app.platform.permission.CapabilityChecker
import com.balancesentinel.app.platform.permission.CapabilityPermissionHistory
import com.balancesentinel.app.platform.permission.CapabilitySnapshot
import com.balancesentinel.app.service.ServiceStartResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CapabilityViewModelTest {
    private lateinit var application: Application

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `opening settings reads state without requesting permission or starting service`() = runTest {
        var starts = 0
        val history = FakePermissionHistory()
        val viewModel = createViewModel(
            checker = snapshotChecker(notification = CapabilityAvailability.NOT_GRANTED),
            history = history,
            onStart = { starts++ }
        )

        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.monitoringDesired)
        assertFalse(history.notificationRequested)
        assertEquals(0, starts)
    }

    @Test
    fun `enabling monitoring requests notification permission and does not schedule while denied`() = runTest {
        var desired = false
        var starts = 0
        val history = FakePermissionHistory()
        val viewModel = createViewModel(
            checker = snapshotChecker(notification = CapabilityAvailability.NOT_GRANTED),
            history = history,
            loadState = { monitoring(desired) },
            setDesired = { desired = it },
            onStart = { starts++ }
        )
        advanceUntilIdle()
        val event = async { viewModel.events.first() }

        viewModel.setMonitoringEnabled(true)
        advanceUntilIdle()

        assertEquals(CapabilityUiEvent.RequestNotificationPermission, event.await())
        assertTrue(desired)
        assertTrue(history.notificationRequested)
        assertEquals(0, starts)
    }

    @Test
    fun `permanent denial retains desired state and offers app settings`() = runTest {
        var desired = false
        val history = FakePermissionHistory()
        val viewModel = createViewModel(
            checker = CapabilityChecker { permanentlyDenied ->
                capabilitySnapshot(
                    notification = if (permanentlyDenied) {
                        CapabilityAvailability.PERMANENTLY_DENIED
                    } else {
                        CapabilityAvailability.NOT_GRANTED
                    }
                )
            },
            history = history,
            loadState = { monitoring(desired) },
            setDesired = { desired = it }
        )
        advanceUntilIdle()
        val requestEvent = async { viewModel.events.first() }
        viewModel.setMonitoringEnabled(true)
        advanceUntilIdle()
        assertEquals(CapabilityUiEvent.RequestNotificationPermission, requestEvent.await())

        viewModel.onNotificationPermissionResult(granted = false, canAskAgain = false)
        advanceUntilIdle()
        val event = async { viewModel.requestPermissionResolution(); viewModel.events.first() }

        assertTrue(desired)
        assertTrue(history.notificationPermanentlyDenied)
        assertEquals(CapabilityUiEvent.OpenAppSettings, event.await())
    }

    @Test
    fun `grant after request starts desired monitoring exactly once`() = runTest {
        var desired = false
        var starts = 0
        var granted = false
        val history = FakePermissionHistory()
        val viewModel = createViewModel(
            checker = CapabilityChecker {
                capabilitySnapshot(
                    notification = if (granted) CapabilityAvailability.AVAILABLE
                    else CapabilityAvailability.NOT_GRANTED
                )
            },
            history = history,
            loadState = { monitoring(desired) },
            setDesired = { desired = it },
            onStart = { starts++ }
        )
        advanceUntilIdle()

        viewModel.setMonitoringEnabled(true)
        advanceUntilIdle()
        granted = true
        viewModel.onNotificationPermissionResult(granted = true, canAskAgain = true)
        advanceUntilIdle()
        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(1, starts)
        assertTrue(viewModel.uiState.value.monitoringEffective)
    }

    @Test
    fun `disabling monitoring persists intent before stopping the service`() = runTest {
        var desired = true
        val events = mutableListOf<String>()
        val viewModel = createViewModel(
            loadState = { monitoring(desired) },
            setDesired = {
                desired = it
                events += "desired:$it"
            },
            onStop = { events += "stop" }
        )
        advanceUntilIdle()

        viewModel.setMonitoringEnabled(false)
        advanceUntilIdle()

        assertEquals(listOf("desired:false", "stop"), events)
        assertFalse(desired)
    }

    @Test
    fun `initial refresh restores desired monitoring after process recreation`() = runTest {
        var starts = 0
        val viewModel = createViewModel(
            loadState = { monitoring(true) },
            onStart = { starts++ }
        )

        advanceUntilIdle()

        assertEquals(1, starts)
        assertTrue(viewModel.uiState.value.monitoringDesired)
    }

    @Test
    fun `capability statuses remain independent`() = runTest {
        val viewModel = createViewModel(
            checker = CapabilityChecker {
                CapabilitySnapshot(
                    mapOf(
                        AppCapability.NOTIFICATIONS to CapabilityAvailability.NOT_GRANTED,
                        AppCapability.FOREGROUND_SERVICE to CapabilityAvailability.AVAILABLE,
                        AppCapability.DATA_SYNC_SESSION to CapabilityAvailability.PLATFORM_LIMITED,
                        AppCapability.EXACT_ALARM to CapabilityAvailability.NOT_REQUIRED
                    )
                )
            }
        )

        advanceUntilIdle()

        assertEquals(CapabilityAvailability.NOT_GRANTED, viewModel.uiState.value.capabilities[AppCapability.NOTIFICATIONS])
        assertEquals(CapabilityAvailability.AVAILABLE, viewModel.uiState.value.capabilities[AppCapability.FOREGROUND_SERVICE])
        assertEquals(CapabilityAvailability.PLATFORM_LIMITED, viewModel.uiState.value.capabilities[AppCapability.DATA_SYNC_SESSION])
        assertEquals(CapabilityAvailability.NOT_REQUIRED, viewModel.uiState.value.capabilities[AppCapability.EXACT_ALARM])
    }

    @Test
    fun `initial refresh failure is projected into ui state`() = runTest {
        val viewModel = createViewModel(
            loadState = { error("database_closed") }
        )

        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.loading)
        assertEquals("database_closed", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `permission result refresh failure is projected into ui state`() = runTest {
        var loadCount = 0
        val viewModel = createViewModel(
            loadState = {
                loadCount++
                if (loadCount == 1) monitoring(false) else error("database_closed")
            }
        )
        advanceUntilIdle()

        viewModel.onNotificationPermissionResult(granted = true, canAskAgain = true)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.loading)
        assertEquals("database_closed", viewModel.uiState.value.errorMessage)
    }

    private fun createViewModel(
        checker: CapabilityChecker = snapshotChecker(),
        history: CapabilityPermissionHistory = FakePermissionHistory(),
        loadState: suspend () -> MonitoringStateEntity = { monitoring(false) },
        setDesired: suspend (Boolean) -> Unit = {},
        onStart: () -> Unit = {},
        onStop: () -> Unit = {}
    ): CapabilityViewModel = CapabilityViewModel(
        application = application,
        checker = checker,
        permissionHistory = history,
        loadMonitoringState = loadState,
        setMonitoringDesired = setDesired,
        startMonitoring = { _, _ ->
            onStart()
            ServiceStartResult.Started
        },
        stopMonitoring = { onStop() }
    )

    private fun snapshotChecker(
        notification: CapabilityAvailability = CapabilityAvailability.AVAILABLE
    ) = CapabilityChecker { capabilitySnapshot(notification) }

    private fun capabilitySnapshot(
        notification: CapabilityAvailability
    ) = CapabilitySnapshot(
        mapOf(
            AppCapability.NOTIFICATIONS to notification,
            AppCapability.FOREGROUND_SERVICE to CapabilityAvailability.AVAILABLE,
            AppCapability.DATA_SYNC_SESSION to CapabilityAvailability.AVAILABLE,
            AppCapability.EXACT_ALARM to CapabilityAvailability.NOT_REQUIRED
        )
    )

    private fun monitoring(desired: Boolean) = MonitoringStateEntity(
        desired = desired,
        observedState = if (desired) MonitoringObservedState.STARTING else MonitoringObservedState.STOPPED,
        updatedAt = 1L
    )

    private class FakePermissionHistory : CapabilityPermissionHistory {
        override var notificationRequested: Boolean = false
        override var notificationPermanentlyDenied: Boolean = false
    }
}
