package com.lilclaw.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.lilclaw.app.ui.chat.ChatScreen
import com.lilclaw.app.ui.settings.SettingsScreen
import com.lilclaw.app.ui.setup.SetupScreen
import com.lilclaw.app.ui.topics.TopicsScreen

object Routes {
    const val SETUP = "setup"
    const val TOPICS = "topics"
    const val CHAT = "chat/{topicId}"
    const val SETTINGS = "settings"

    fun chat(topicId: String) = "chat/$topicId"
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Routes.SETUP
    ) {
        composable(Routes.SETUP) {
            SetupScreen(
                onSetupComplete = {
                    navController.navigate(Routes.TOPICS) {
                        popUpTo(Routes.SETUP) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.TOPICS) {
            TopicsScreen(
                onTopicSelected = { topicId ->
                    navController.navigate(Routes.chat(topicId))
                },
                onSettingsClick = {
                    navController.navigate(Routes.SETTINGS)
                }
            )
        }

        composable(Routes.CHAT) { backStackEntry ->
            val topicId = backStackEntry.arguments?.getString("topicId") ?: return@composable
            ChatScreen(
                topicId = topicId,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
