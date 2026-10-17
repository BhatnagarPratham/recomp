package app.recomp.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.recomp.ui.dev.DevScreen
import app.recomp.ui.today.TodayScreen
import kotlinx.serialization.Serializable

@Serializable
data object TodayRoute

@Serializable
data object DevRoute

private data class TopLevel(val label: String, val glyph: String, val route: Any)

private val topLevel = listOf(
    TopLevel("Today", "◉", TodayRoute),
    TopLevel("Dev", "⚙", DevRoute),
)

@Composable
fun RecompNavHost() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()

    Scaffold(
        bottomBar = {
            NavigationBar {
                topLevel.forEach { item ->
                    val selected = backStack?.destination?.hasRoute(item.route::class) == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            nav.navigate(item.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Text(item.glyph) },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = TodayRoute, modifier = Modifier.padding(padding)) {
            composable<TodayRoute> { TodayScreen() }
            composable<DevRoute> { DevScreen() }
        }
    }
}
