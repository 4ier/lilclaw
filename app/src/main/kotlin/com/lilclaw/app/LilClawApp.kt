package com.lilclaw.app

import android.app.Application
import com.lilclaw.app.data.GatewayClient
import com.lilclaw.app.data.GatewayConnectionState
import com.lilclaw.app.data.SettingsRepository
import com.lilclaw.app.di.appModule
import com.lilclaw.app.service.GatewayManager
import com.lilclaw.app.service.GatewayState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class LilClawApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@LilClawApp)
            modules(appModule)
        }

        // Auto-connect WebSocket when gateway becomes Running
        appScope.launch {
            val gateway: GatewayManager by inject()
            val client: GatewayClient by inject()

            gateway.state.collect { state ->
                if (state is GatewayState.Running) {
                    delay(500) // Brief buffer after listening detected
                    if (client.connectionState.value !is GatewayConnectionState.Connected) {
                        client.connect(port = 3000, token = "lilclaw-local")
                    }
                }
            }
        }

        // Auto-start gateway if setup is complete (app restart case)
        appScope.launch {
            val settings: SettingsRepository by inject()
            val gateway: GatewayManager by inject()

            val setupComplete = settings.isSetupComplete.first()
            if (setupComplete && gateway.isRootfsExtracted) {
                val provider = settings.provider.first()
                val apiKey = settings.apiKey.first()
                val model = settings.model.first()
                if (gateway.state.value is GatewayState.Stopped) {
                    gateway.start(provider = provider, apiKey = apiKey, model = model)
                }
            }
        }
    }
}
