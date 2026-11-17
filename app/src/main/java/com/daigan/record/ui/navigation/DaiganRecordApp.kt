package com.daigan.record.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.navArgument
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daigan.record.feature.accounts.AccountFormScreen
import com.daigan.record.feature.accounts.AccountsScreen
import com.daigan.record.feature.accounts.ArchivedRecordsScreen
import com.daigan.record.feature.accounts.OwnerDetailScreen
import com.daigan.record.feature.accounts.OwnerFormScreen
import com.daigan.record.feature.orders.BackfillScreen
import com.daigan.record.feature.orders.NewOrderScreen
import com.daigan.record.feature.orders.OrderDetailScreen
import com.daigan.record.feature.orders.OrderEditScreen
import com.daigan.record.feature.settings.BackupScreen
import com.daigan.record.feature.settings.PageTransitionViewModel
import com.daigan.record.feature.settings.ReceiptHistoryScreen
import com.daigan.record.feature.settings.ReminderSettingsScreen
import com.daigan.record.feature.settings.SettingsScreen
import com.daigan.record.feature.settings.VersionManagementScreen
import com.daigan.record.feature.stats.StatsScreen
import com.daigan.record.feature.templates.ServerOptionsScreen
import com.daigan.record.feature.templates.TemplateEditorScreen
import com.daigan.record.feature.templates.TemplateLibraryScreen
import com.daigan.record.feature.today.TodayScreen

private data class MainDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val mainDestinations = listOf(
    MainDestination(Routes.TODAY, "今日事项", Icons.AutoMirrored.Filled.List),
    MainDestination(Routes.ACCOUNTS, "账号信息", Icons.Filled.Person),
    MainDestination(Routes.STATS, "数据统计", Icons.Filled.BarChart),
)

@Composable
fun DaiganRecordApp(
    navController: NavHostController = rememberNavController(),
    initialOrderId: String? = null,
    homeRequest: Long = 0L,
    onInitialOrderHandled: () -> Unit = {},
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in Routes.mainDestinations

    // Read once here so every navigation path — bottom bar, deep links, pushes and pops —
    // sees the same value. `hiltViewModel()` resolves to the activity-scoped store owner
    // because this call site sits above the NavHost.
    val pageTransitionViewModel: PageTransitionViewModel = hiltViewModel()
    val pageTransitions by pageTransitionViewModel.pageTransitionAnimations
        .collectAsStateWithLifecycle()

    // A notification tap opens the order it refers to, including after process death.
    // The screen validates that the order still exists, so a restored-away or deleted
    // order shows a clear not-found state instead of stale content.
    LaunchedEffect(initialOrderId) {
        if (!initialOrderId.isNullOrBlank()) {
            navController.navigate(Routes.orderDetail(initialOrderId)) {
                launchSingleTop = true
            }
            onInitialOrderHandled()
        }
    }

    LaunchedEffect(homeRequest) {
        if (homeRequest > 0L) {
            navController.navigate(Routes.TODAY) {
                popUpTo(navController.graph.id) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    mainDestinations.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = { navController.navigateToMain(destination.route) },
                            icon = { Icon(destination.icon, contentDescription = destination.label) },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        // One destination builder shared by both branches, so the graph exists exactly
        // once and toggling the preference cannot drop or duplicate a route.
        val destinations: NavGraphBuilder.() -> Unit = {
            composable(Routes.TODAY) {
                TodayScreen(
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onOpenOrder = { orderId -> navController.navigate(Routes.orderDetail(orderId)) },
                )
            }
            composable(Routes.ACCOUNTS) {
                AccountsScreen(
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onOpenOwner = { ownerId -> navController.navigate(Routes.ownerDetail(ownerId)) },
                    onCreateOwner = { navController.navigate(Routes.ownerForm()) },
                    onOpenArchived = { navController.navigate(Routes.ARCHIVED) },
                )
            }
            composable(Routes.STATS) {
                StatsScreen(
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onOpenOrder = { orderId -> navController.navigate(Routes.orderDetail(orderId)) },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenSection = { section ->
                        when (section) {
                            SettingsSection.RECEIPTS -> navController.navigate(Routes.RECEIPTS)
                            SettingsSection.TEMPLATES -> navController.navigate(Routes.TEMPLATES)
                            SettingsSection.REMINDERS -> navController.navigate(Routes.REMINDERS)
                            SettingsSection.BACKUP -> navController.navigate(Routes.BACKUP)
                            SettingsSection.UPDATES -> navController.navigate(Routes.UPDATES)
                        }
                    },
                )
            }
            composable(Routes.RECEIPTS) {
                ReceiptHistoryScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.TEMPLATES) {
                TemplateLibraryScreen(
                    onBack = { navController.popBackStack() },
                    onCreateTemplate = { navController.navigate(Routes.templateEditor()) },
                    onEditTemplate = { templateId ->
                        navController.navigate(Routes.templateEditor(templateId))
                    },
                    onManageServerOptions = { navController.navigate(Routes.SERVER_OPTIONS) },
                )
            }
            composable(Routes.SERVER_OPTIONS) {
                ServerOptionsScreen(onBack = { navController.popBackStack() })
            }
            composable(
                route = Routes.TEMPLATE_EDITOR,
                arguments = listOf(
                    navArgument(Routes.TEMPLATE_ID_ARG) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) {
                TemplateEditorScreen(onDone = { navController.popBackStack() })
            }
            composable(Routes.REMINDERS) {
                ReminderSettingsScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.BACKUP) {
                BackupScreen(
                    onBack = { navController.popBackStack() },
                    onRestoreFinished = {
                        // The dataset was replaced: discard the whole settings stack and
                        // return to the start destination so no screen keeps stale data.
                        navController.navigate(Routes.TODAY) {
                            popUpTo(navController.graph.id) {
                                inclusive = true
                            }
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable(Routes.UPDATES) {
                VersionManagementScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.ARCHIVED) {
                ArchivedRecordsScreen(onBack = { navController.popBackStack() })
            }
            composable(
                route = Routes.OWNER_DETAIL,
                arguments = listOf(navArgument(Routes.OWNER_ID_ARG) { type = NavType.StringType }),
            ) { entry ->
                val ownerId = entry.arguments?.getString(Routes.OWNER_ID_ARG).orEmpty()
                OwnerDetailScreen(
                    onBack = { navController.popBackStack() },
                    onEditOwner = { id -> navController.navigate(Routes.ownerForm(id)) },
                    onAddAccount = { id -> navController.navigate(Routes.accountForm(id)) },
                    onEditAccount = { id, accountId ->
                        navController.navigate(Routes.accountForm(id, accountId))
                    },
                    onCreateOrder = { accountId ->
                        navController.navigate(Routes.orderNew(ownerId, accountId))
                    },
                    onOpenOrder = { orderId ->
                        navController.navigate(Routes.orderDetail(orderId))
                    },
                )
            }
            composable(
                route = Routes.ORDER_NEW,
                arguments = listOf(
                    navArgument(Routes.OWNER_ID_ARG) { type = NavType.StringType },
                    navArgument(Routes.ACCOUNT_ID_ARG) { type = NavType.StringType },
                ),
            ) {
                NewOrderScreen(
                    onBack = { navController.popBackStack() },
                    onCreated = { orderId ->
                        navController.navigate(Routes.orderDetail(orderId)) {
                            popUpTo(Routes.ORDER_NEW) { inclusive = true }
                        }
                    },
                )
            }
            composable(
                route = Routes.ORDER_DETAIL,
                arguments = listOf(navArgument(Routes.ORDER_ID_ARG) { type = NavType.StringType }),
            ) { entry ->
                val orderId = entry.arguments?.getString(Routes.ORDER_ID_ARG).orEmpty()
                OrderDetailScreen(
                    onBack = { navController.popBackStack() },
                    onBackfill = { periodId ->
                        navController.navigate(Routes.orderBackfill(orderId, periodId))
                    },
                    onOpenEditor = { navController.navigate(Routes.orderEdit(orderId)) },
                )
            }
            composable(
                route = Routes.ORDER_EDIT,
                arguments = listOf(navArgument(Routes.ORDER_ID_ARG) { type = NavType.StringType }),
            ) {
                OrderEditScreen(onBack = { navController.popBackStack() })
            }
            composable(
                route = Routes.ORDER_BACKFILL,
                arguments = listOf(
                    navArgument(Routes.ORDER_ID_ARG) { type = NavType.StringType },
                    navArgument(Routes.PERIOD_ID_ARG) { type = NavType.StringType },
                ),
            ) {
                BackfillScreen(onBack = { navController.popBackStack() })
            }
            composable(
                route = Routes.OWNER_FORM,
                arguments = listOf(
                    navArgument(Routes.OWNER_ID_ARG) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) {
                OwnerFormScreen(onDone = { navController.popBackStack() })
            }
            composable(
                route = Routes.ACCOUNT_FORM,
                arguments = listOf(
                    navArgument(Routes.OWNER_ID_ARG) { type = NavType.StringType },
                    navArgument(Routes.ACCOUNT_ID_ARG) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) {
                AccountFormScreen(onDone = { navController.popBackStack() })
            }
        }

        if (pageTransitions) {
            // Default behaviour: the transition parameters are omitted, so Navigation
            // Compose's own enter/exit/pop transitions apply exactly as they always have.
            NavHost(
                navController = navController,
                startDestination = Routes.TODAY,
                modifier = Modifier.padding(innerPadding),
            ) { destinations() }
        } else {
            // Navigation Compose 2.9 types these lambdas as returning a non-null
            // transition, so "no animation" is expressed with the identity transition
            // rather than null. Every forward push, back pop and bottom-bar switch
            // between the main pages then completes immediately.
            NavHost(
                navController = navController,
                startDestination = Routes.TODAY,
                modifier = Modifier.padding(innerPadding),
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { ExitTransition.None },
            ) { destinations() }
        }
    }
}

private fun NavHostController.navigateToMain(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
