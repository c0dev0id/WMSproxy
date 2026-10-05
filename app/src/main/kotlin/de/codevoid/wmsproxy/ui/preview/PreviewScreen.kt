package de.codevoid.wmsproxy.ui.preview

import android.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.codevoid.wmsproxy.R
import de.codevoid.wmsproxy.preview.OsmdroidTiles
import de.codevoid.wmsproxy.preview.TemplateTileSource
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.TilesOverlay
import kotlin.math.floor

/** Where the map is looking. Kept across rotation, which builds the view again. */
private data class Camera(val latitude: Double, val longitude: Double, val zoom: Double)

private val CameraSaver = listSaver<Camera, Double>(
    save = { listOf(it.latitude, it.longitude, it.zoom) },
    restore = { Camera(it[0], it[1], it[2]) },
)

/**
 * The layer over a base map, full screen: pinch and pan to see where it draws, with
 * the tile level in a corner so where it appears and disappears can be read off.
 * Close is the one control.
 */
@Composable
internal fun PreviewScreen(viewModel: PreviewViewModel, onClose: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) { viewModel.resolve(context) }

    Box(modifier = Modifier.fillMaxSize()) {
        when (val current = state) {
            PreviewState.Resolving -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            PreviewState.Missing -> LaunchedEffect(Unit) { onClose() }
            is PreviewState.Ready -> {
                var camera by rememberSaveable(stateSaver = CameraSaver) {
                    mutableStateOf(Camera(current.start.latitude, current.start.longitude, current.start.zoom))
                }
                PreviewMap(current, camera) { camera = it }
                Box(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                    ZoomPill(zoom = camera.zoom, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp))
                    if (current.baseMap != null) {
                        Attribution(modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 28.dp))
                    }
                }
            }
        }
        Box(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            FloatingActionButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            ) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.close))
            }
        }
    }
}

/**
 * The map view itself, made once and kept for the life of the screen. The base map is
 * the view's own tile layer and the previewed layer an overlay on it, so a transparent
 * tile shows the streets through and an opaque one covers them, as it would in DMD.
 * Without a base map the layer is the tile layer and draws alone.
 */
@Composable
private fun PreviewMap(ready: PreviewState.Ready, initial: Camera, onCameraChange: (Camera) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnCameraChange by rememberUpdatedState(onCameraChange)

    val mapView = remember {
        OsmdroidTiles.configure(context)
        OsmdroidTiles.setReferer(ready.layer.referer)
        MapView(context).apply {
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            setTilesScaledToDpi(true)
            val baseMap = ready.baseMap
            if (baseMap == null) {
                setTileSource(TemplateTileSource(ready.layer))
            } else {
                setTileSource(TemplateTileSource(baseMap))
                val overlay = TilesOverlay(MapTileProviderBasic(context, TemplateTileSource(ready.layer)), context)
                // No grid while a tile loads: the base map is what shows in the meantime.
                overlay.loadingBackgroundColor = Color.TRANSPARENT
                overlay.loadingLineColor = Color.TRANSPARENT
                overlays.add(overlay)
            }
            controller.setZoom(initial.zoom)
            controller.setCenter(GeoPoint(initial.latitude, initial.longitude))
            addMapListener(object : MapListener {
                override fun onScroll(event: ScrollEvent?): Boolean = report()
                override fun onZoom(event: ZoomEvent?): Boolean = report()

                private fun report(): Boolean {
                    val centre = mapCenter
                    currentOnCameraChange(Camera(centre.latitude, centre.longitude, zoomLevelDouble))
                    return false
                }
            })
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDetach()
            OsmdroidTiles.setReferer(null)
        }
    }

    AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
}

/** The tile level the map is drawing, which is the zoom rounded down. */
@Composable
private fun ZoomPill(zoom: Double, modifier: Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        tonalElevation = 3.dp,
    ) {
        Text(
            text = stringResource(R.string.preview_zoom, floor(zoom).toInt()),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun Attribution(modifier: Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
    ) {
        Text(
            text = stringResource(R.string.preview_attribution),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
