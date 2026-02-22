package com.lilclaw.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
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
                SystemBarColors()
                AppNavigation()
            }
        }
    }

    @Composable
    private fun SystemBarColors() {
        val isDark = isSystemInDarkTheme()
        SideEffect {
            // Set navigation bar color to match the app background
            window.navigationBarColor = if (isDark) {
                android.graphics.Color.parseColor("#1a1410")
            } else {
                android.graphics.Color.WHITE
            }
            // Status bar color (transparent with edge-to-edge, but icons need correct contrast)
            window.statusBarColor = if (isDark) {
                android.graphics.Color.parseColor("#1a1410")
            } else {
                android.graphics.Color.WHITE
            }

            // Light status bar = dark icons (for light bg), dark status bar = light icons (for dark bg)
            @Suppress("DEPRECATION")
            val flags = window.decorView.systemUiVisibility
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (!isDark) {
                flags or android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                    android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            } else {
                flags and android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() and
                    android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            }
        }
    }

    override fun onDestroy() {
        nativeBridge.destroy()
        super.onDestroy()
    }
}
