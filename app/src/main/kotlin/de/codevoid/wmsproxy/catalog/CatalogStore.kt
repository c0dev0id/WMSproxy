package de.codevoid.wmsproxy.catalog

import android.content.Context
import de.codevoid.wmsproxy.core.CachedService
import de.codevoid.wmsproxy.core.CatalogCache
import de.codevoid.wmsproxy.core.CatalogCodec
import de.codevoid.wmsproxy.writeAtomically
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import kotlin.concurrent.thread

/**
 * Every service document the app knows: the ones shipped with it in `catalog.json`, and
 * the ones the user's own reads and rescans added, on disk between launches. What the
 * screens see is the shipped set with the user's reads on top where they are newer
 * ([CatalogCache.overlaid]); only the user's part is ever written.
 *
 * Both parts are read once, off the main thread, from [init]: the list searches the
 * shipped layers, so it needs them from the start. A caller that must not race that
 * read calls [warmUp], which blocks until both are in. Every mutation comes from an IO
 * coroutine, a fetch finishing, so the user's file is written synchronously there,
 * through a rename so a reader never sees half.
 */
object CatalogStore {

    private const val FILE_NAME = "catalog-cache.json"
    private const val ASSET = "catalog.json"

    private lateinit var app: Context
    private lateinit var file: File

    @Volatile
    private var loaded = false

    private var bundled = CatalogCache()
    private var own = CatalogCache()

    private val _cache = MutableStateFlow(CatalogCache())
    val cache: StateFlow<CatalogCache> = _cache.asStateFlow()

    /** Called once from [de.codevoid.wmsproxy.WmsProxyApp]; starts the read in the background. */
    fun init(context: Context) {
        app = context.applicationContext
        file = File(app.filesDir, FILE_NAME)
        thread(name = "catalog-load") { warmUp() }
    }

    /** Reads both parts once. Cheap to call again; blocking, so not for the main thread. */
    @Synchronized
    fun warmUp() {
        if (loaded || !::app.isInitialized) return
        bundled = runCatching { app.assets.open(ASSET).use { it.readBytes().decodeToString() } }
            .getOrNull()
            ?.let(CatalogCodec::decodeCache)
            ?: CatalogCache()
        if (file.exists()) {
            own = CatalogCodec.decodeCache(runCatching { file.readText() }.getOrDefault(""))
        }
        loaded = true
        publish()
    }

    fun put(service: CachedService) = mutate { it.with(service) }

    /** Forgets the user's read of [url]; a shipped document for it, if any, shows again. */
    fun remove(url: String) = mutate { it.without(url) }

    // The parts are read before the first change so a mutation never writes a set that
    // was never loaded over the one on disk.
    private fun mutate(change: (CatalogCache) -> CatalogCache) {
        warmUp()
        synchronized(this) {
            own = change(own)
            publish()
            persist()
        }
    }

    private fun publish() {
        _cache.value = bundled.overlaid(own)
    }

    private fun persist() {
        if (!::file.isInitialized) return
        runCatching { file.writeAtomically(CatalogCodec.encodeCache(own).toByteArray()) }
    }
}
