package com.lilclaw.app.share

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lilclaw.app.ui.theme.LilClawTheme

class ShareReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedText = when (intent?.action) {
            Intent.ACTION_SEND -> {
                if (intent.type?.startsWith("text/") == true) {
                    intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
                } else {
                    intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)?.toString() ?: ""
                }
            }
            else -> ""
        }

        setContent {
            LilClawTheme {
                var prompt by remember { mutableStateOf("") }

                Scaffold { padding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = "Send to LilClaw",
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = sharedText.take(200) + if (sharedText.length > 200) "..." else "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(
                            value = prompt,
                            onValueChange = { prompt = it },
                            label = { Text("Add instructions (optional)") },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 3,
                        )
                        Spacer(Modifier.height(24.dp))
                        Button(
                            onClick = {
                                // TODO: Send to gateway via GatewayClient
                                // For now, just close
                                finish()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Send")
                        }
                    }
                }
            }
        }
    }
}
