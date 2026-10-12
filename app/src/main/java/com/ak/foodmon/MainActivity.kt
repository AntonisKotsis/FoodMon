package com.ak.foodmon

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ak.foodmon.ui.exercise.AddExerciseScreen
import com.ak.foodmon.ui.history.HistoryScreen
import com.ak.foodmon.ui.profile.EditProfileScreen
import com.ak.foodmon.ui.scan.ScanScreen
import com.ak.foodmon.ui.settings.SettingsScreen
import com.ak.foodmon.ui.theme.FoodMonTheme
import com.ak.foodmon.ui.today.TodayScreen
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    /** Screen requested by a widget button, consumed once the nav graph has navigated to it. */
    private var pendingDestination by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) pendingDestination = intent.getStringExtra(EXTRA_DESTINATION)
        setContent {
            FoodMonTheme {
                FoodMonNavHost(pendingDestination, onDestinationHandled = { pendingDestination = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingDestination = intent.getStringExtra(EXTRA_DESTINATION)
    }

    companion object {
        const val EXTRA_DESTINATION = "destination"
        const val DEST_SCAN = "scan"
        const val DEST_EXERCISE = "exercise"
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("today", "Today", Icons.Default.Today),
    Tab("history", "History", Icons.Default.BarChart),
    Tab("settings", "Settings", Icons.Default.Settings),
)

@Composable
private fun FoodMonNavHost(pendingDestination: String?, onDestinationHandled: () -> Unit) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()

    LaunchedEffect(pendingDestination) {
        if (pendingDestination == MainActivity.DEST_SCAN || pendingDestination == MainActivity.DEST_EXERCISE) {
            nav.navigate("$pendingDestination/${LocalDate.now()}") {
                popUpTo("today")
                launchSingleTop = true
            }
        }
        if (pendingDestination != null) onDestinationHandled()
    }
    val currentRoute = backStack?.destination?.route
    val showTabs = tabs.any { it.route == currentRoute }

    Scaffold(
        bottomBar = {
            if (showTabs) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = "today", modifier = Modifier.padding(bottom = padding.calculateBottomPadding())) {
            composable("today") {
                TodayScreen(
                    onScanFood = { date -> nav.navigate("scan/$date") },
                    onAddExercise = { date -> nav.navigate("exercise/$date") },
                    onEditProfile = { id -> nav.navigate("profile/$id") },
                )
            }
            composable("history") { HistoryScreen() }
            composable("settings") {
                SettingsScreen(onEditProfile = { id -> nav.navigate("profile/$id") })
            }
            composable("scan/{date}") { entry ->
                ScanScreen(date = entry.arguments?.getString("date")!!, onDone = { nav.popBackStack() })
            }
            composable("exercise/{date}") { entry ->
                AddExerciseScreen(date = entry.arguments?.getString("date")!!, onDone = { nav.popBackStack() })
            }
            composable("profile/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                EditProfileScreen(profileId = entry.arguments?.getLong("id") ?: 0L, onDone = { nav.popBackStack() })
            }
        }
    }
}