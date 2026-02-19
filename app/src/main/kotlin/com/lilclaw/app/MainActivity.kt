package com.lilclaw.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.lilclaw.app.navigation.AppNavigation
import com.lilclaw.app.ui.theme.LilClawTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LilClawTheme {
                AppNavigation()
            }
        }
    }
}
