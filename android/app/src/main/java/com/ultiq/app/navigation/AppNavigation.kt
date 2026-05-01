package com.ultiq.app.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Checklist
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
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import com.ultiq.app.ui.auth.AuthViewModel
import com.ultiq.app.ui.auth.ForgotPasswordScreen
import com.ultiq.app.ui.auth.LoginScreen
import com.ultiq.app.ui.auth.RegisterScreen
import com.ultiq.app.ui.auth.ResetPasswordScreen
import com.ultiq.app.ui.calendar.CalendarScreen
import com.ultiq.app.ui.checklist.ChecklistScreen
import com.ultiq.app.ui.checklist.WeeklyPlannerScreen
import com.ultiq.app.ui.dashboard.DashboardScreen
import com.ultiq.app.ui.onboarding.OnboardingScreen
import com.ultiq.app.ui.reports.WeeklyReportScreen
import com.ultiq.app.ui.sessions.SessionsScreen
import com.ultiq.app.ui.settings.ChangePasswordScreen
import com.ultiq.app.ui.settings.RemindersScreen
import com.ultiq.app.ui.settings.SettingsScreen
import com.ultiq.app.ui.settings.TermsScreen
import com.ultiq.app.ui.sleep.SleepScreen
import com.ultiq.app.util.NotificationHelper

sealed class Screen(val route: String) {
    data object Onboarding : Screen("onboarding")
    data object Login : Screen("login")
    data object Register : Screen("register")
    data object ForgotPassword : Screen("forgot_password")
    data object ResetPassword : Screen("reset_password?token={token}") {
        fun route(token: String) = "reset_password?token=$token"
    }
    data object Dashboard : Screen("dashboard")
    data object Checklist : Screen("checklist")
    data object WeeklyPlanner : Screen("weekly_planner")
    data object Sleep : Screen("sleep")
    data object Sessions : Screen("sessions")
    data object Calendar : Screen("calendar")
    data object Settings : Screen("settings")
    data object Reminders : Screen("reminders")
    data object Reports : Screen("reports")
    data object ChangePassword : Screen("change_password")
    data object Terms : Screen("terms")
}

data class BottomNavItem(
    val screen: Screen,
    val label: String,
    val icon: ImageVector
)

val bottomNavItems = listOf(
    BottomNavItem(Screen.Dashboard, "Dashboard", Icons.Filled.Dashboard),
    BottomNavItem(Screen.Checklist, "Checklist", Icons.Filled.Checklist),
    BottomNavItem(Screen.Sleep, "Sleep", Icons.Filled.Nightlight),
    BottomNavItem(Screen.Sessions, "Focus", Icons.Filled.Timer),
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

    if (uiState.isCheckingAuth || uiState.isCheckingOnboarding) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val startDestination = when {
        !uiState.onboardingCompleted -> Screen.Onboarding.route
        uiState.isLoggedIn -> Screen.Dashboard.route
        else -> Screen.Login.route
    }

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
            modifier = Modifier.padding(innerPadding),
            enterTransition = { slideInHorizontally(tween(250)) { it / 6 } + fadeIn(tween(250)) },
            exitTransition = { fadeOut(tween(150)) },
            popEnterTransition = { slideInHorizontally(tween(250)) { -it / 6 } + fadeIn(tween(250)) },
            popExitTransition = { slideOutHorizontally(tween(200)) { it / 6 } + fadeOut(tween(200)) },
        ) {
            composable(Screen.Onboarding.route) {
                OnboardingScreen(onFinish = { /* auth-state observer routes us onward */ })
            }
            composable(Screen.Login.route) {
                LoginScreen(
                    uiState = uiState,
                    onLogin = { email, password -> authViewModel.login(email, password) },
                    onNavigateToRegister = {
                        navController.navigate(Screen.Register.route)
                    },
                    onNavigateToForgotPassword = {
                        authViewModel.consumeForgotPasswordSent()
                        authViewModel.clearError()
                        navController.navigate(Screen.ForgotPassword.route)
                    },
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
            composable(Screen.ForgotPassword.route) {
                ForgotPasswordScreen(
                    uiState = uiState,
                    onSubmit = { email -> authViewModel.forgotPassword(email) },
                    onBack = {
                        authViewModel.consumeForgotPasswordSent()
                        authViewModel.clearError()
                        navController.popBackStack()
                    },
                )
            }
            composable(
                route = Screen.ResetPassword.route,
                arguments = listOf(navArgument("token") {
                    type = NavType.StringType
                    defaultValue = ""
                }),
                deepLinks = listOf(navDeepLink {
                    uriPattern = "ultiq://reset-password?token={token}"
                }),
            ) { entry ->
                val token = entry.arguments?.getString("token").orEmpty()
                ResetPasswordScreen(
                    uiState = uiState,
                    token = token,
                    onSubmit = { t, p -> authViewModel.resetPassword(t, p) },
                    onDoneNavigateBack = {
                        authViewModel.consumeResetPasswordSuccess()
                        authViewModel.clearError()
                        navController.navigate(Screen.Login.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                )
            }
            composable(Screen.Dashboard.route) {
                DashboardScreen(
                    onNavigateToSleep = { navigateToTab(navController, Screen.Sleep) },
                    onNavigateToSessions = { navigateToTab(navController, Screen.Sessions) },
                    onNavigateToCalendar = { navigateToTab(navController, Screen.Calendar) },
                    onNavigateToChecklist = { navigateToTab(navController, Screen.Checklist) },
                    onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                    onNavigateToReports = { navController.navigate(Screen.Reports.route) },
                    onLogout = { authViewModel.logout() }
                )
            }
            composable(Screen.Checklist.route) {
                ChecklistScreen(
                    onNavigateToWeeklyPlanner = { navController.navigate(Screen.WeeklyPlanner.route) },
                )
            }
            composable(Screen.WeeklyPlanner.route) {
                WeeklyPlannerScreen(onDone = { navController.popBackStack() })
            }
            composable(Screen.Sleep.route) { SleepScreen() }
            composable(Screen.Sessions.route) { SessionsScreen() }
            composable(Screen.Calendar.route) { CalendarScreen() }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onNavigateToReminders = { navController.navigate(Screen.Reminders.route) },
                    onNavigateToReports = { navController.navigate(Screen.Reports.route) },
                    onNavigateToChangePassword = { navController.navigate(Screen.ChangePassword.route) },
                    onNavigateToTerms = { navController.navigate(Screen.Terms.route) },
                    onLogout = { authViewModel.logout() },
                    onResetAccount = { authViewModel.resetAccount() },
                    onDeleteAccount = { authViewModel.deleteAccount() },
                )
            }
            composable(Screen.Terms.route) {
                TermsScreen(onBack = { navController.popBackStack() })
            }
            composable(Screen.Reminders.route) {
                RemindersScreen(onBack = { navController.popBackStack() })
            }
            composable(Screen.ChangePassword.route) {
                ChangePasswordScreen(onBack = { navController.popBackStack() })
            }
            composable(Screen.Reports.route) {
                WeeklyReportScreen(onBack = { navController.popBackStack() })
            }
        }
    }

    // When onboarding completes, move to login/dashboard
    LaunchedEffect(uiState.onboardingCompleted) {
        if (uiState.onboardingCompleted &&
            navController.currentDestination?.route == Screen.Onboarding.route
        ) {
            val target = if (uiState.isLoggedIn) Screen.Dashboard.route else Screen.Login.route
            navController.navigate(target) {
                popUpTo(Screen.Onboarding.route) { inclusive = true }
            }
        }
    }

    // Auth state transitions
    LaunchedEffect(uiState.isLoggedIn, uiState.onboardingCompleted) {
        if (!uiState.onboardingCompleted) return@LaunchedEffect
        if (uiState.isLoggedIn) {
            navController.navigate(Screen.Dashboard.route) {
                popUpTo(Screen.Login.route) { inclusive = true }
            }
        } else {
            val current = navController.currentDestination?.route
            if (current != Screen.Login.route && current != Screen.Register.route) {
                navController.navigate(Screen.Login.route) {
                    popUpTo(0) { inclusive = true }
                }
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
