package de.codevoid.wmsproxy.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import de.codevoid.wmsproxy.dmd.DmdViewModel
import de.codevoid.wmsproxy.ui.catalog.CatalogScreen
import de.codevoid.wmsproxy.ui.service.ServiceScreen
import de.codevoid.wmsproxy.ui.service.ServiceViewModel
import de.codevoid.wmsproxy.ui.settings.SettingsScreen
import de.codevoid.wmsproxy.update.UpdateViewModel
import kotlinx.serialization.Serializable

@Serializable
data object CatalogRoute

@Serializable
data class ServiceRoute(val key: String)

@Serializable
data object SettingsRoute

/**
 * The app: the one list, a detail screen per service, and settings, on one back stack.
 * The DMD and update view models belong to the activity, so a sync started from the
 * list is still running, and still reported, after the screen changes.
 */
@Composable
fun AppRoot(dmd: DmdViewModel = viewModel(), updates: UpdateViewModel = viewModel()) {
    val nav = rememberNavController()
    NotificationPermissionRequest()
    NavHost(navController = nav, startDestination = CatalogRoute) {
        composable<CatalogRoute> {
            CatalogScreen(
                dmd = dmd,
                onOpen = { nav.navigate(ServiceRoute(it)) },
                onSettings = { nav.navigate(SettingsRoute) },
            )
        }
        composable<ServiceRoute> { entry ->
            val route = entry.toRoute<ServiceRoute>()
            val viewModel: ServiceViewModel = viewModel(key = route.key) { ServiceViewModel(route.key) }
            ServiceScreen(viewModel = viewModel, onBack = { nav.popBackStack() })
        }
        composable<SettingsRoute> {
            SettingsScreen(dmd = dmd, updates = updates, onBack = { nav.popBackStack() })
        }
    }
}

/** The foreground service's notification needs this on Android 13+; asked once, on open. */
@Composable
private fun NotificationPermissionRequest() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
}
