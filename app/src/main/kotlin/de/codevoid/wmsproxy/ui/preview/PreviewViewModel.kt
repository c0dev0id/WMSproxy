package de.codevoid.wmsproxy.ui.preview

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.codevoid.wmsproxy.catalog.CapabilitiesFetcher
import de.codevoid.wmsproxy.catalog.CapabilitiesFetcher.FetchResult
import de.codevoid.wmsproxy.catalog.Catalog
import de.codevoid.wmsproxy.catalog.CatalogStore
import de.codevoid.wmsproxy.core.CachedService
import de.codevoid.wmsproxy.core.LayerRow
import de.codevoid.wmsproxy.core.ServiceDetail
import de.codevoid.wmsproxy.core.TileLayer
import de.codevoid.wmsproxy.proxy.Sources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Where the preview map opens: the phone's position when it is known, else the layer's. */
data class PreviewStart(val longitude: Double, val latitude: Double, val zoom: Double)

sealed interface PreviewState {
    data object Resolving : PreviewState

    /** Nothing to draw: the layer is not in the list any more. The screen closes. */
    data object Missing : PreviewState

    /** The layer to draw, where to open, and the base map to draw it over, if one could be read. */
    data class Ready(val layer: TileLayer, val start: PreviewStart, val baseMap: TileLayer?) : PreviewState
}

/**
 * Finds the layer the preview was opened for, where the map should start and which base
 * map to draw under it, all off the main thread, once per screen.
 */
class PreviewViewModel(private val key: String, private val layerId: String) : ViewModel() {

    private val _state = MutableStateFlow<PreviewState>(PreviewState.Resolving)
    val state: StateFlow<PreviewState> = _state.asStateFlow()

    @Volatile
    private var started = false

    fun resolve(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            val row = findRow()
            if (row == null) {
                _state.value = PreviewState.Missing
                return@launch
            }
            val baseMap = async { baseMapLayer() }
            val fix = lastKnown(app)
            val zoom = START_ZOOM.toDouble()
            val centre = row.centre
            val start = when {
                fix != null -> PreviewStart(fix.longitude, fix.latitude, zoom)
                centre != null -> PreviewStart(centre.longitude, centre.latitude, zoom)
                else -> PreviewStart(WORLD_LONGITUDE, WORLD_LATITUDE, WORLD_ZOOM)
            }
            _state.value = PreviewState.Ready(row.candidate, start, baseMap.await())
        }
    }

    /**
     * The row the detail screen showed, rebuilt from the same parts: the list's item,
     * the cached document and the stored layers. The list is assembled in the
     * background, so the item is waited for briefly; after a process restart it may
     * take a moment to appear.
     */
    private suspend fun findRow(): LayerRow? {
        CatalogStore.warmUp()
        val item = withTimeoutOrNull(ITEM_WAIT_MS) {
            Catalog.items.map { items -> items.firstOrNull { it.key == key } }.filterNotNull().first()
        } ?: return null
        val rows = ServiceDetail.rows(item, CatalogStore.cache.value[key], Sources.config.value.layers)
        return rows.firstOrNull { it.id == layerId && !it.stale } ?: rows.firstOrNull { it.id == layerId }
    }

    /**
     * The last fix the system has, any provider, and on Android 11+ a fresh one when
     * there is none, within a short wait. No Play Services: the platform API is enough
     * for a starting point. Null without permission, which the caller asked for first.
     */
    @SuppressLint("MissingPermission")
    private suspend fun lastKnown(context: Context): Location? {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        runCatching {
            providers.firstNotNullOfOrNull { provider ->
                if (manager.isProviderEnabled(provider)) manager.getLastKnownLocation(provider) else null
            }
        }.getOrNull()?.let { return it }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val provider = providers.firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
            ?: return null
        return withTimeoutOrNull(FIX_TIMEOUT_MS) {
            suspendCancellableCoroutine<Location?> { continuation ->
                val signal = CancellationSignal()
                continuation.invokeOnCancellation { signal.cancel() }
                runCatching {
                    manager.getCurrentLocation(provider, signal, ContextCompat.getMainExecutor(context)) { location ->
                        if (continuation.isActive) continuation.resume(location)
                    }
                }.onFailure { if (continuation.isActive) continuation.resume(null) }
            }
        }
    }

    /**
     * The base map: the library's OpenStreetMap WMS, read and cached like any other
     * service, as a layer the map can ask for tiles through. Null when it cannot be read;
     * the layer then shows alone.
     */
    private fun baseMapLayer(): TileLayer? {
        val cached = CatalogStore.cache.value[BASE_MAP_URL] ?: when (val result = CapabilitiesFetcher.fetch(BASE_MAP_URL)) {
            is FetchResult.Document ->
                CachedService.of(BASE_MAP_URL, result.from, result.document, System.currentTimeMillis())
                    .also { CatalogStore.put(it) }
            is FetchResult.Failed -> return null
        }
        val layer = cached.layer(BASE_MAP_LAYER) ?: cached.layers.firstOrNull() ?: return null
        return layer.toTileLayer(BASE_MAP_SOURCE, origin = BASE_MAP_URL)
    }

    private companion object {
        /** The library's "OpenStreetMap — terrestris" entry; its address is the key either way. */
        const val BASE_MAP_URL = "https://ows.terrestris.de/osm/service?SERVICE=WMS&REQUEST=GetCapabilities"
        const val BASE_MAP_LAYER = "OSM-WMS"
        const val BASE_MAP_SOURCE = "basemap"
        /** A neighbourhood: streets readable, most overlays drawn. */
        const val START_ZOOM = 13
        const val WORLD_LONGITUDE = 10.0
        const val WORLD_LATITUDE = 30.0
        const val WORLD_ZOOM = 2.0
        const val FIX_TIMEOUT_MS = 5_000L
        const val ITEM_WAIT_MS = 5_000L
    }
}
