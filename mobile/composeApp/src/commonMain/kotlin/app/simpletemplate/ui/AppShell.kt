package app.simpletemplate.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.simpletemplate.shared.AppContainer
import app.simpletemplate.shared.Repositories
import app.simpletemplate.ui.items.ItemForm
import app.simpletemplate.ui.items.ItemsScreen
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass
import kotlin.uuid.Uuid

// Routes. Ids travel as strings; a null id opens the form for a new record.
// EXAMPLE feature routes: replace with the app's own.
@Serializable object ItemsRoute
@Serializable data class ItemEditRoute(val id: String? = null)
// Platform routes: keep.
@Serializable object StatusRoute
@Serializable object SignInRoute

private data class Tab(val label: String, val icon: ImageVector, val route: Any, val routeClass: KClass<*>)

/** The bottom tabs. The first is the start screen; Status (account, app lock, connection) stays last. */
private val tabs = listOf(
    Tab("Items", Icons.AutoMirrored.Filled.List, ItemsRoute, ItemsRoute::class),
    Tab("Status", Icons.Filled.Info, StatusRoute, StatusRoute::class),
)

@Composable
fun AppShell(container: AppContainer, repos: Repositories) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val destination = entry?.destination
    val onTab = tabs.any { tab -> destination?.hasRoute(tab.routeClass) == true }

    // An organisation workspace keeps itself in step with the platform while it is open.
    LaunchedEffect(repos) { repos.sync?.start(this) }

    // Screens have their own Scaffold; the insets this one handles are consumed so they are not
    // applied twice.
    Scaffold(
        bottomBar = { if (onTab) TabBar(nav, destination) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        NavHost(nav, startDestination = ItemsRoute, modifier = Modifier.padding(padding).consumeWindowInsets(padding)) {
            composable<ItemsRoute> {
                ItemsScreen(
                    repos.items,
                    onOpen = { nav.navigate(ItemEditRoute(it.toString())) },
                    onNew = { nav.navigate(ItemEditRoute()) },
                )
            }
            composable<ItemEditRoute> { backStack ->
                val route = backStack.toRoute<ItemEditRoute>()
                ItemForm(repos.items, route.id?.let(Uuid::parse)) { nav.popBackStack() }
            }
            composable<StatusRoute> {
                StatusScreen(container, repos, onSignIn = { nav.navigate(SignInRoute) })
            }
            composable<SignInRoute> {
                AppScreen(title = "Sign in", onBack = { nav.popBackStack() }) { modifier ->
                    Column(modifier.verticalScroll(rememberScrollState())) {
                        // Signed in to an organisation: it opens (a new workspace). Without one, the
                        // screen says what to do next.
                        LoginScreen(container.auth)
                    }
                }
            }
        }
    }
}

@Composable
private fun TabBar(nav: NavHostController, destination: NavDestination?) {
    NavigationBar {
        tabs.forEach { tab ->
            NavigationBarItem(
                selected = destination?.hasRoute(tab.routeClass) == true,
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
