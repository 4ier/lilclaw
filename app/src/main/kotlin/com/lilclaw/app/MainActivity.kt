package com.lilclaw.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.lilclaw.app.navigation.AppNavigation
import com.lilclaw.app.ui.theme.LilClawTheme
import com.lilclaw.app.ui.webview.NativeBridge

class MainActivity : ComponentActivity() {
    lateinit var nativeBridge: NativeBridge
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        // NativeBridge must be created before setContent so launchers register in time
        nativeBridge = NativeBridge(this) { null } // webView set later
        nativeBridge.registerLaunchers()

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LilClawTheme {
                AppNavigation()
            }
        }
    }

    override fun onDestroy() {
        nativeBridge.destroy()
        super.onDestroy()
    }
}
