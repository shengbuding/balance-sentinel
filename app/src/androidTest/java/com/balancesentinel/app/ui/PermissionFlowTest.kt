package com.balancesentinel.app.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.balancesentinel.app.R
import com.balancesentinel.app.data.local.monitoring.MonitoringObservedState
import com.balancesentinel.app.data.local.monitoring.MonitoringStateEntity
import com.balancesentinel.app.platform.permission.AppCapability
import com.balancesentinel.app.platform.permission.CapabilityAvailability
import com.balancesentinel.app.platform.permission.CapabilityChecker
import com.balancesentinel.app.platform.permission.CapabilityPermissionHistory
import com.balancesentinel.app.platform.permission.CapabilitySnapshot
import com.balancesentinel.app.ui.components.CapabilityStatusCard
import com.balancesentinel.app.ui.theme.DeepSeekBalanceTheme
import com.balancesentinel.app.ui.viewmodel.CapabilityUiEvent
import com.balancesentinel.app.ui.viewmodel.CapabilityViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the application-owned permission UI and event boundary. The Android
 * permission dialog itself remains a platform surface and is not simulated here.
 */
@RunWith(AndroidJUnit4::class)
class PermissionFlowTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun permissionIsRequestedOnlyAfterUserEnablesMonitoring() {
        var desired = false
        var starts = 0
        val history = FakePermissionHistory()
        val viewModel = createViewModel(
            checker = checker(CapabilityAvailability.NOT_GRANTED),
            history = history,
            loadState = { monitoring(desired) },
            setDesired = { desired = it },
            onStart = { starts++ }
        )

        setCapabilityContent(viewModel)
        composeRule.waitUntil(5_000) { !viewModel.uiState.value.loading }

        composeRule.onNodeWithText(string(R.string.capability_not_granted))
            .assertIsDisplayed()
        composeRule.onNodeWithTag("monitoring_desired_switch").assertIsOff()
        assertFalse(history.notificationRequested)
        assertEquals(0, starts)

        composeRule.onNodeWithTag("monitoring_desired_switch").performClick()
        val event = runBlocking {
            withTimeout(5_000) { viewModel.events.first() }
        }

        composeRule.waitUntil(5_000) { desired && !viewModel.uiState.value.loading }
        assertEquals(CapabilityUiEvent.RequestNotificationPermission, event)
        assertTrue(history.notificationRequested)
        assertEquals(0, starts)
    }

    @Test
    fun permanentDenialExposesSystemSettingsResolution() {
        val history = FakePermissionHistory(notificationPermanentlyDenied = true)
        val viewModel = createViewModel(
            checker = CapabilityChecker { permanentlyDenied ->
                checker(
                    if (permanentlyDenied) {
                        CapabilityAvailability.PERMANENTLY_DENIED
                    } else {
                        CapabilityAvailability.NOT_GRANTED
                    }
                ).read(permanentlyDenied)
            },
            history = history
        )

        setCapabilityContent(viewModel)
        composeRule.waitUntil(5_000) { !viewModel.uiState.value.loading }

        composeRule.onNodeWithText(string(R.string.capability_permanently_denied))
            .assertIsDisplayed()
        composeRule.onNodeWithTag("open_notification_settings")
            .assertIsDisplayed()
            .performClick()
        val event = runBlocking {
            withTimeout(5_000) { viewModel.events.first() }
        }

        assertEquals(CapabilityUiEvent.OpenAppSettings, event)
    }

    @Test
    fun grantStartsDesiredMonitoringExactlyOnceAndUpdatesSwitch() {
        var availability = CapabilityAvailability.NOT_GRANTED
        var desired = false
        var starts = 0
        val viewModel = createViewModel(
            checker = CapabilityChecker { checker(availability).read(false) },
            loadState = { monitoring(desired) },
            setDesired = { desired = it },
            onStart = { starts++ }
        )

        setCapabilityContent(viewModel)
        composeRule.waitUntil(5_000) { !viewModel.uiState.value.loading }
        composeRule.onNodeWithTag("monitoring_desired_switch").performClick()
        runBlocking { withTimeout(5_000) { viewModel.events.first() } }

        availability = CapabilityAvailability.AVAILABLE
        composeRule.runOnIdle {
            viewModel.onNotificationPermissionResult(granted = true, canAskAgain = true)
        }
        composeRule.waitUntil(5_000) {
            starts == 1 && viewModel.uiState.value.monitoringEffective
        }
        composeRule.runOnIdle { viewModel.refresh() }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("monitoring_desired_switch").assertIsOn()
        assertEquals(1, starts)
    }

    private fun setCapabilityContent(viewModel: CapabilityViewModel) {
        composeRule.setContent {
            DeepSeekBalanceTheme {
                CapabilityStatusCard(viewModel)
            }
        }
    }

    private fun createViewModel(
        checker: CapabilityChecker,
        history: CapabilityPermissionHistory = FakePermissionHistory(),
        loadState: suspend () -> MonitoringStateEntity = { monitoring(false) },
        setDesired: suspend (Boolean) -> Unit = {},
        onStart: () -> Unit = {}
    ) = CapabilityViewModel(
        application = composeRule.activity.application as Application,
        checker = checker,
        permissionHistory = history,
        loadMonitoringState = loadState,
        setMonitoringDesired = setDesired,
        startMonitoring = { onStart() },
        stopMonitoring = {}
    )

    private fun checker(notification: CapabilityAvailability) = CapabilityChecker {
        CapabilitySnapshot(
            mapOf(
                AppCapability.NOTIFICATIONS to notification,
                AppCapability.FOREGROUND_SERVICE to CapabilityAvailability.AVAILABLE,
                AppCapability.DATA_SYNC_SESSION to CapabilityAvailability.AVAILABLE,
                AppCapability.EXACT_ALARM to CapabilityAvailability.NOT_REQUIRED
            )
        )
    }

    private fun monitoring(desired: Boolean) = MonitoringStateEntity(
        desired = desired,
        observedState = if (desired) {
            MonitoringObservedState.STARTING
        } else {
            MonitoringObservedState.STOPPED
        },
        updatedAt = 1L
    )

    private fun string(resourceId: Int): String = composeRule.activity.getString(resourceId)

    private class FakePermissionHistory(
        override var notificationRequested: Boolean = false,
        override var notificationPermanentlyDenied: Boolean = false
    ) : CapabilityPermissionHistory
}
