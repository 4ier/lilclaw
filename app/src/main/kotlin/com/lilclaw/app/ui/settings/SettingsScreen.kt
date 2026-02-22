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
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
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
                            is GatewayState.Running -> MaterialTheme.colorScheme.primary to "运行中"
                            is GatewayState.Starting -> MaterialTheme.colorScheme.tertiary to "启动中..."
                            is GatewayState.WaitingForUi -> MaterialTheme.colorScheme.tertiary to "正在启动界面..."
                            is GatewayState.Preparing -> MaterialTheme.colorScheme.tertiary to "准备中..."
                            is GatewayState.Downloading -> MaterialTheme.colorScheme.tertiary to "下载中..."
                            is GatewayState.Extracting -> MaterialTheme.colorScheme.tertiary to "解压中..."
                            is GatewayState.Idle -> MaterialTheme.colorScheme.onSurfaceVariant to "已停止"
                            is GatewayState.Error -> MaterialTheme.colorScheme.error to "出错"
                        }
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .background(statusColor, shape = CircleShape),
                        )
                        Text(
                            text = "  引擎: $statusText",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("端口: ${state.gatewayPort}", style = MaterialTheme.typography.bodyMedium)
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
                                OutlinedButton(onClick = viewModel::stopGateway) { Text("停止") }
                                Button(onClick = viewModel::restartGateway) { Text("重启") }
                            }
                            is GatewayState.Idle, is GatewayState.Error -> {
                                Button(onClick = viewModel::startGateway) { Text("启动") }
                            }
                            else -> {}
                        }
                    }
                }
            }

            // Provider info card
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("服务商", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "服务商: ${state.provider.ifEmpty { "未配置" }}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "模型: ${state.model.ifEmpty { "默认" }}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            // About card
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("关于", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("小爪 (LilClaw) v${com.lilclaw.app.BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "由 OpenClaw 驱动",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
