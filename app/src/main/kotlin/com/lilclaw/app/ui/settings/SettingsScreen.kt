package com.lilclaw.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lilclaw.app.service.GatewayState
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Gateway status card
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val (statusColor, statusText) = when (state.gatewayState) {
                            is GatewayState.Running -> MaterialTheme.colorScheme.primary to "Running"
                            is GatewayState.Starting -> MaterialTheme.colorScheme.tertiary to "Starting..."
                            is GatewayState.WaitingForUi -> MaterialTheme.colorScheme.tertiary to "Starting UI..."
                            is GatewayState.Preparing -> MaterialTheme.colorScheme.tertiary to "Preparing..."
                            is GatewayState.Downloading -> MaterialTheme.colorScheme.tertiary to "Downloading..."
                            is GatewayState.Extracting -> MaterialTheme.colorScheme.tertiary to "Extracting..."
                            is GatewayState.Idle -> MaterialTheme.colorScheme.onSurfaceVariant to "Stopped"
                            is GatewayState.Error -> MaterialTheme.colorScheme.error to "Error"
                        }
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .background(statusColor, shape = CircleShape),
                        )
                        Text(
                            text = "  Gateway: $statusText",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Port: ${state.gatewayPort}", style = MaterialTheme.typography.bodyMedium)
                    if (state.gatewayState is GatewayState.Error) {
                        val scrollState = rememberScrollState()
                        Text(
                            (state.gatewayState as GatewayState.Error).message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .heightIn(max = 200.dp)
                                .verticalScroll(scrollState),
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (state.gatewayState) {
                            is GatewayState.Running -> {
                                OutlinedButton(onClick = viewModel::stopGateway) { Text("Stop") }
                                Button(onClick = viewModel::restartGateway) { Text("Restart") }
                            }
                            is GatewayState.Idle, is GatewayState.Error -> {
                                Button(onClick = viewModel::startGateway) { Text("Start") }
                            }
                            else -> {}
                        }
                    }
                }
            }

            // Provider info card
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Provider", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Provider: ${state.provider.ifEmpty { "Not configured" }}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Model: ${state.model.ifEmpty { "Default" }}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            // About card
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("About", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("LilClaw (小爪子) v0.3.2", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Powered by OpenClaw",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
