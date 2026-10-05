package de.codevoid.wmsproxy.catalog

import android.content.Context
import de.codevoid.wmsproxy.core.CachedService
import de.codevoid.wmsproxy.core.CatalogCache
import de.codevoid.wmsproxy.core.CatalogCodec
import de.codevoid.wmsproxy.core.TileLayer
import de.codevoid.wmsproxy.core.ZoomMeasurement
import de.codevoid.wmsproxy.writeAtomically
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

/**
 * Every service document read so far, with its measurements, on disk between launches.
 *
 * Read lazily and off the main thread: a service with a thousand layers makes the file
 * large, and a launch that only starts the proxy never needs it. Until [warmUp] has run
 * the list shows the library's own counts, which is what it showed before any read.
 * Every mutation comes from an IO coroutine — a fetch or a probe finishing — so the
 * file is written synchronously there, through a rename so a reader never sees half.
 */
object CatalogStore {

    private const val FILE_NAME = "catalog-cache.json"

    private lateinit var file: File

    @Volatile
    private var loaded = false

    private val _cache = MutableStateFlow(CatalogCache())
    val cache: StateFlow<CatalogCache> = _cache.asStateFlow()

    /** Called once from [de.codevoid.wmsproxy.WmsProxyApp]; records the path only. */
    fun init(context: Context) {
        file = File(context.filesDir, FILE_NAME)
    }

    /** Reads the file once. Cheap to call again; blocking, so not for the main thread. */
    @Synchronized
    fun warmUp() {
        if (loaded || !::file.isInitialized) return
        if (file.exists()) {
            _cache.value = CatalogCodec.decodeCache(runCatching { file.readText() }.getOrDefault(""))
        }
        loaded = true
    }

    fun put(service: CachedService) = mutate { it.with(service) }

    /**
     * Records what the probe found for [stored] against the document's own name for the
     * layer, so the measurement outlives unloading and the next load of it is free.
     * Nothing is recorded for a layer without an origin or whose document is not cached.
     */
    fun measured(stored: TileLayer, measurement: ZoomMeasurement) = mutate { cache ->
        val origin = stored.origin ?: return@mutate cache
        val service = cache[origin] ?: return@mutate cache
        val name = service.layers.firstOrNull { it.storedLayerId() == stored.layer }?.name ?: return@mutate cache
        cache.with(service.withMeasurement(name, measurement))
    }

    fun remove(url: String) = mutate { it.without(url) }

    // The file is read before the first change so a mutation never writes a cache that
    // was never loaded over the one on disk.
    private fun mutate(change: (CatalogCache) -> CatalogCache) {
        warmUp()
        _cache.update(change)
        persist()
    }

    @Synchronized
    private fun persist() {
        if (!::file.isInitialized) return
        runCatching { file.writeAtomically(CatalogCodec.encodeCache(_cache.value).toByteArray()) }
    }
}
