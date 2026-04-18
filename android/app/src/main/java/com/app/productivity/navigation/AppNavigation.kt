package com.app.productivity.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.app.productivity.ui.auth.AuthViewModel
import com.app.productivity.ui.auth.LoginScreen
import com.app.productivity.ui.auth.RegisterScreen
import com.app.productivity.ui.calendar.CalendarScreen
import com.app.productivity.ui.dashboard.DashboardScreen
import com.app.productivity.ui.sessions.SessionsScreen
import com.app.productivity.ui.settings.RemindersScreen
import com.app.productivity.ui.sleep.SleepScreen
import com.app.productivity.util.NotificationHelper

sealed class Screen(val route: String) {
    data object Login : Screen("login")
    data object Register : Screen("register")
    data object Dashboard : Screen("dashboard")
    data object Sleep : Screen("sleep")
    data object Sessions : Screen("sessions")
    data object Calendar : Screen("calendar")
    data object Reminders : Screen("reminders")
}

data class BottomNavItem(
    val screen: Screen,
    val label: String,
    val icon: ImageVector
)

val bottomNavItems = listOf(
    BottomNavItem(Screen.Dashboard, "Dashboard", Icons.Filled.Dashboard),
    BottomNavItem(Screen.Sleep, "Sleep", Icons.Filled.Nightlight),
    BottomNavItem(Screen.Sessions, "Sessions", Icons.Filled.Timer),
    BottomNavItem(Screen.Calendar, "Calendar", Icons.Filled.CalendarMonth),
)

@Composable
fun AppNavigation(
    authViewModel: AuthViewModel = viewModel(),
    pendingDeepLink: String? = null,
    onDeepLinkConsumed: () -> Unit = {},
) {
    val uiState by authViewModel.uiState.collectAsState()
    val navController = rememberNavController()

    if (uiState.isCheckingAuth) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val startDestination = if (uiState.isLoggedIn) Screen.Dashboard.route else Screen.Login.route

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val showBottomBar = currentDestination?.hierarchy?.any { dest ->
        bottomNavItems.any { it.screen.route == dest.route }
    } == true

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomNavItems.forEach { item ->
                        NavigationBarItem(
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label) },
                            selected = currentDestination?.hierarchy?.any { it.route == item.screen.route } == true,
                            onClick = {
                                navController.navigate(item.screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Login.route) {
                LoginScreen(
                    uiState = uiState,
                    onLogin = { email, password -> authViewModel.login(email, password) },
                    onNavigateToRegister = {
                        navController.navigate(Screen.Register.route)
                    }
                )
            }
            composable(Screen.Register.route) {
                RegisterScreen(
                    uiState = uiState,
                    onRegister = { email, password -> authViewModel.register(email, password) },
                    onNavigateToLogin = {
                        navController.popBackStack()
                    }
                )
            }
            composable(Screen.Dashboard.route) {
                DashboardScreen(
                    onNavigateToSleep = { navigateToTab(navController, Screen.Sleep) },
                    onNavigateToSessions = { navigateToTab(navController, Screen.Sessions) },
                    onNavigateToCalendar = { navigateToTab(navController, Screen.Calendar) },
                    onNavigateToReminders = { navController.navigate(Screen.Reminders.route) },
                    onLogout = { authViewModel.logout() }
                )
            }
            composable(Screen.Sleep.route) { SleepScreen() }
            composable(Screen.Sessions.route) { SessionsScreen() }
            composable(Screen.Calendar.route) { CalendarScreen() }
            composable(Screen.Reminders.route) {
                RemindersScreen(onBack = { navController.popBackStack() })
            }
        }
    }

    LaunchedEffect(uiState.isLoggedIn) {
        if (uiState.isLoggedIn) {
            navController.navigate(Screen.Dashboard.route) {
                popUpTo(Screen.Login.route) { inclusive = true }
            }
        } else {
            navController.navigate(Screen.Login.route) {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    LaunchedEffect(pendingDeepLink, uiState.isLoggedIn) {
        val target = pendingDeepLink ?: return@LaunchedEffect
        if (!uiState.isLoggedIn) return@LaunchedEffect
        val screen = when (target) {
            NotificationHelper.DEEP_LINK_SLEEP -> Screen.Sleep
            NotificationHelper.DEEP_LINK_SESSIONS -> Screen.Sessions
            NotificationHelper.DEEP_LINK_CALENDAR -> Screen.Calendar
            NotificationHelper.DEEP_LINK_DASHBOARD -> Screen.Dashboard
            else -> null
        }
        if (screen != null) {
            navigateToTab(navController, screen)
            onDeepLinkConsumed()
        }
    }
}

private fun navigateToTab(
    navController: androidx.navigation.NavHostController,
    screen: Screen,
) {
    navController.navigate(screen.route) {
        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
fun PlaceholderScreen(title: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = title, style = MaterialTheme.typography.headlineMedium)
    }
}
