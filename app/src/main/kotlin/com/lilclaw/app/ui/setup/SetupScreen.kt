package com.lilclaw.app.ui.setup

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lilclaw.app.service.GatewayManager
import org.koin.androidx.compose.koinViewModel

private val PROVIDERS = listOf("OpenAI", "Anthropic", "DeepSeek", "AWS Bedrock", "Custom")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    onSetupComplete: () -> Unit,
    viewModel: SetupViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()

    if (state.step == SetupStep.DONE) {
        onSetupComplete()
        return
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            AnimatedContent(targetState = state.step, label = "setup_step") { step ->
                when (step) {
                    SetupStep.WELCOME -> WelcomeStep(onNext = viewModel::onNextFromWelcome)
                    SetupStep.EXTRACT -> ExtractStep(
                        progress = state.extractionProgress,
                        isDownloading = state.isDownloading,
                        currentLayer = state.currentLayer,
                        error = state.extractionError,
                        onRetry = viewModel::onRetryExtraction,
                    )
                    SetupStep.PROVIDER -> ProviderStep(
                        state = state,
                        onProviderChanged = viewModel::onProviderChanged,
                        onApiKeyChanged = viewModel::onApiKeyChanged,
                        onModelChanged = viewModel::onModelChanged,
                        onTest = viewModel::onTestConnection,
                        onComplete = viewModel::onComplete,
                    )
                    SetupStep.DONE -> { /* handled above */ }
                }
            }
        }
    }
}

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize(),
    ) {
        Text(
            text = "🐾",
            style = MaterialTheme.typography.displayLarge,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "LilClaw",
            style = MaterialTheme.typography.headlineLarge,
        )
        Text(
            text = "小爪子",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Your pocket AI gateway",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(48.dp))
        Button(onClick = onNext, modifier = Modifier.fillMaxWidth(0.6f)) {
            Text("Get Started")
        }
    }
}

@Composable
private fun ExtractStep(
    progress: Float,
    isDownloading: Boolean,
    currentLayer: String = "",
    error: String? = null,
    onRetry: () -> Unit = {},
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize(),
    ) {
        if (error != null) {
            Text(
                text = "Setup failed",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth(0.6f)) {
                Text("Retry")
            }
        } else {
            val layerLabel = when (currentLayer) {
                "base" -> "Base system"
                "openclaw" -> "AI engine"
                "chatspa" -> "Chat UI"
                "setup" -> "Finishing setup"
                else -> "Preparing"
            }

            val layerSize = GatewayManager.LAYERS.find { it.name == currentLayer }?.displaySize

            Text(
                text = if (isDownloading) "Downloading..." else "Extracting...",
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = layerLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (layerSize != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "($layerSize)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                text = "Total ~73 MB — one-time download",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(24.dp))

            // Layer progress indicators
            Column(modifier = Modifier.fillMaxWidth(0.8f)) {
                for (layer in GatewayManager.LAYERS) {
                    val layerDone = when {
                        currentLayer == "setup" -> true
                        currentLayer == "" && progress >= 1f -> true
                        GatewayManager.LAYERS.indexOf(layer) <
                                GatewayManager.LAYERS.indexOfFirst { it.name == currentLayer } -> true
                        else -> false
                    }
                    val isCurrent = layer.name == currentLayer

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = when {
                                layerDone -> "✓"
                                isCurrent -> "●"
                                else -> "○"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (layerDone) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(20.dp),
                        )
                        Text(
                            text = when (layer.name) {
                                "base" -> "Base system"
                                "openclaw" -> "AI engine"
                                "chatspa" -> "Chat UI"
                                else -> layer.name
                            },
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
                            color = if (isCurrent) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = layer.displaySize,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(0.8f),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "${(progress * 100).toInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderStep(
    state: SetupState,
    onProviderChanged: (String) -> Unit,
    onApiKeyChanged: (String) -> Unit,
    onModelChanged: (String) -> Unit,
    onTest: () -> Unit,
    onComplete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Configure Provider",
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(32.dp))

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            OutlinedTextField(
                value = state.provider.ifEmpty { "Select provider" },
                onValueChange = {},
                readOnly = true,
                label = { Text("Provider") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                PROVIDERS.forEach { provider ->
                    DropdownMenuItem(
                        text = { Text(provider) },
                        onClick = {
                            onProviderChanged(provider)
                            expanded = false
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = state.apiKey,
            onValueChange = onApiKeyChanged,
            label = { Text("API Key") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = state.model,
            onValueChange = onModelChanged,
            label = { Text("Model (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        if (state.connectionError != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = state.connectionError,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(32.dp))

        Button(
            onClick = onComplete,
            enabled = state.provider.isNotEmpty() && state.apiKey.isNotEmpty() && !state.isTestingConnection,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.isTestingConnection) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Text("Continue")
            }
        }
    }
}
