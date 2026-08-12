package com.balancesentinel.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.balancesentinel.app.R
import com.balancesentinel.app.platform.permission.AppCapability
import com.balancesentinel.app.platform.permission.CapabilityAvailability
import com.balancesentinel.app.ui.viewmodel.CapabilityViewModel

@Composable
fun CapabilityStatusCard(viewModel: CapabilityViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.capability_monitoring_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        stringResource(R.string.capability_monitoring_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = state.monitoringDesired,
                    onCheckedChange = viewModel::setMonitoringEnabled,
                    enabled = !state.loading,
                    modifier = Modifier.testTag("monitoring_desired_switch")
                )
            }

            CapabilityRow(
                stringResource(R.string.capability_notifications),
                state.capabilities[AppCapability.NOTIFICATIONS]
            )
            CapabilityRow(
                stringResource(R.string.capability_foreground_service),
                state.capabilities[AppCapability.FOREGROUND_SERVICE]
            )
            CapabilityRow(
                stringResource(R.string.capability_data_sync),
                state.capabilities[AppCapability.DATA_SYNC_SESSION]
            )
            CapabilityRow(
                stringResource(R.string.capability_exact_alarm),
                state.capabilities[AppCapability.EXACT_ALARM]
            )

            if (state.capabilities[AppCapability.NOTIFICATIONS] == CapabilityAvailability.PERMANENTLY_DENIED) {
                Button(
                    onClick = viewModel::requestPermissionResolution,
                    modifier = Modifier.fillMaxWidth().testTag("open_notification_settings")
                ) {
                    Text(stringResource(R.string.capability_open_settings))
                }
            }

            state.errorMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
fun NotificationCapabilityBanner(viewModel: CapabilityViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val notification = state.capabilities[AppCapability.NOTIFICATIONS]
    if (notification.allowsMonitoring) return

    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                stringResource(R.string.capability_notification_required),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            if (notification == CapabilityAvailability.PERMANENTLY_DENIED) {
                Spacer(Modifier.height(8.dp))
                Button(onClick = viewModel::requestPermissionResolution) {
                    Text(stringResource(R.string.capability_open_settings))
                }
            }
        }
    }
}

@Composable
private fun CapabilityRow(label: String, availability: CapabilityAvailability) {
    val value = when (availability) {
        CapabilityAvailability.AVAILABLE -> stringResource(R.string.capability_available)
        CapabilityAvailability.NOT_GRANTED -> stringResource(R.string.capability_not_granted)
        CapabilityAvailability.PERMANENTLY_DENIED -> stringResource(R.string.capability_permanently_denied)
        CapabilityAvailability.PLATFORM_LIMITED -> stringResource(R.string.capability_platform_limited)
        CapabilityAvailability.NOT_REQUIRED -> stringResource(R.string.capability_not_required)
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(
            value,
            style = MaterialTheme.typography.labelMedium,
            color = if (availability.allowsMonitoring) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            }
        )
    }
}
