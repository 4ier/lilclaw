package com.lilclaw.app

import android.app.Application
import com.lilclaw.app.data.SettingsRepository
import com.lilclaw.app.di.appModule
import com.lilclaw.app.service.GatewayManager
import com.lilclaw.app.service.GatewayState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

        // Auto-start gateway if setup is complete
        appScope.launch {
            val settings: SettingsRepository by inject()
            val gateway: GatewayManager by inject()

            val setupComplete = settings.isSetupComplete.first()
            if (setupComplete && gateway.isRootfsExtracted) {
                val provider = settings.provider.first()
                val model = settings.model.first()
                if (gateway.state.value is GatewayState.Stopped) {
                    gateway.start(provider = provider, apiKey = "", model = model)
                }
            }
        }
    }
}
