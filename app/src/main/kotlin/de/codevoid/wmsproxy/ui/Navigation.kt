package de.codevoid.wmsproxy.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import de.codevoid.wmsproxy.dmd.DmdViewModel
import de.codevoid.wmsproxy.ui.catalog.CatalogScreen
import de.codevoid.wmsproxy.ui.preview.PreviewScreen
import de.codevoid.wmsproxy.ui.preview.PreviewViewModel
import de.codevoid.wmsproxy.ui.service.ServiceScreen
import de.codevoid.wmsproxy.ui.service.ServiceViewModel
import de.codevoid.wmsproxy.ui.settings.SettingsScreen
import de.codevoid.wmsproxy.update.UpdateViewModel
import kotlinx.serialization.Serializable

@Serializable
data object CatalogRoute

/** [layerQuery] seeds the layer search: the list's search when a layer, not the service, matched. */
@Serializable
data class ServiceRoute(val key: String, val layerQuery: String = "")

@Serializable
data class PreviewRoute(val key: String, val layerId: String)

@Serializable
data object SettingsRoute

private val LOCATION_PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

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
                onOpen = { key, layerQuery -> nav.navigate(ServiceRoute(key, layerQuery)) },
                onSettings = { nav.navigate(SettingsRoute) },
            )
        }
        composable<ServiceRoute> { entry ->
            val route = entry.toRoute<ServiceRoute>()
            val viewModel: ServiceViewModel = viewModel(key = route.key) { ServiceViewModel(route.key) }
            // The preview opens at the phone's position, so a tap asks for location first;
            // the map opens whatever the answer, which only decides where it starts.
            var pendingPreview by remember { mutableStateOf<PreviewRoute?>(null) }
            val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                pendingPreview?.let { nav.navigate(it) }
                pendingPreview = null
            }
            ServiceScreen(
                viewModel = viewModel,
                onBack = { nav.popBackStack() },
                initialLayerQuery = route.layerQuery,
                onPreview = { row ->
                    pendingPreview = PreviewRoute(route.key, row.id)
                    askLocation.launch(LOCATION_PERMISSIONS)
                },
            )
        }
        composable<PreviewRoute> { entry ->
            val route = entry.toRoute<PreviewRoute>()
            val viewModel: PreviewViewModel = viewModel { PreviewViewModel(route.key, route.layerId) }
            PreviewScreen(viewModel = viewModel, onClose = { nav.popBackStack() })
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
