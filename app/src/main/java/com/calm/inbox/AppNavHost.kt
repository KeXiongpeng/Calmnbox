package com.calm.inbox

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Summarize
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.calm.inbox.features.inbox.InboxScreen
import com.calm.inbox.features.settings.SettingsScreen

@Composable
fun AppNavHost(
    navController: NavHostController = rememberNavController(),
    modifier: Modifier = Modifier
) {
    val backStackEntry = navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry.value?.destination?.route

    Scaffold(
        modifier = modifier,
        bottomBar = {
            NavigationBar {
                BottomDestination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = currentRoute == destination.route,
                        onClick = { navController.navigateSingleTop(destination.route) },
                        icon = {
                            Icon(destination.icon, contentDescription = destination.label)
                        },
                        label = { Text(destination.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "inbox",
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("inbox") { InboxScreen() }
            composable("brief") { RouteScaffold("brief", "W2 Task 13") }
            composable("chat") { RouteScaffold("chat", "W3 Task 15") }
            composable("settings") { SettingsScreen() }
        }
    }
}

private enum class BottomDestination(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    INBOX("inbox", "收件箱", Icons.Outlined.Inbox),
    BRIEF("brief", "简报", Icons.Outlined.Summarize),
    CHAT("chat", "问答", Icons.AutoMirrored.Outlined.Chat),
    SETTINGS("settings", "设置", Icons.Outlined.Settings)
}

private fun NavHostController.navigateSingleTop(route: String) {
    navigate(route) {
        launchSingleTop = true
        popUpTo("inbox") {
            saveState = true
        }
        restoreState = true
    }
}

@Composable
private fun RouteScaffold(route: String, owner: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(text = "route=$route; owner=$owner")
    }
}
