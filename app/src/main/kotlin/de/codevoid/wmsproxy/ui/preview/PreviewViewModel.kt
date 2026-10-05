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
import de.codevoid.wmsproxy.catalog.CatalogStore
import de.codevoid.wmsproxy.core.CachedService
import de.codevoid.wmsproxy.core.LonLat
import de.codevoid.wmsproxy.core.TileLayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Where the preview map opens: the phone's position when it is known, else the layer's. */
data class PreviewStart(val longitude: Double, val latitude: Double, val zoom: Double)

/**
 * Works out where the preview starts and which base map to draw under the layer, both
 * off the main thread, each once per screen.
 */
class PreviewViewModel : ViewModel() {

    private val _start = MutableStateFlow<PreviewStart?>(null)
    val start: StateFlow<PreviewStart?> = _start.asStateFlow()

    private val _baseMap = MutableStateFlow<TileLayer?>(null)
    val baseMap: StateFlow<TileLayer?> = _baseMap.asStateFlow()

    @Volatile
    private var resolved = false

    fun resolve(context: Context, layer: TileLayer, centre: LonLat?) {
        if (resolved) return
        resolved = true
        val app = context.applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            val zoom = startZoom(layer)
            val fix = lastKnown(app)
            _start.value = when {
                fix != null -> PreviewStart(fix.longitude, fix.latitude, zoom)
                centre != null -> PreviewStart(centre.longitude, centre.latitude, zoom)
                else -> PreviewStart(WORLD_LONGITUDE, WORLD_LATITUDE, WORLD_ZOOM)
            }
        }
        viewModelScope.launch(Dispatchers.IO) { _baseMap.value = baseMapLayer() }
    }

    /**
     * Inside the measured range where there is one, a little above its floor so the
     * layer is on screen at once; a neighbourhood zoom otherwise.
     */
    private fun startZoom(layer: TileLayer): Double {
        val min = layer.minZoom
        val max = layer.maxZoom
        return when {
            min != null && max != null -> (min + 2).coerceIn(min, max).toDouble()
            min != null -> (min + 2).toDouble()
            max != null -> minOf(DEFAULT_ZOOM, max).toDouble()
            else -> DEFAULT_ZOOM.toDouble()
        }
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
     * the overlay then shows alone.
     */
    private fun baseMapLayer(): TileLayer? {
        CatalogStore.warmUp()
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
        const val DEFAULT_ZOOM = 13
        const val WORLD_LONGITUDE = 10.0
        const val WORLD_LATITUDE = 30.0
        const val WORLD_ZOOM = 2.0
        const val FIX_TIMEOUT_MS = 5_000L
    }
}
