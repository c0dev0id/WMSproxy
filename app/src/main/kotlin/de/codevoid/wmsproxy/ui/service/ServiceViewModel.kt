package de.codevoid.wmsproxy.ui.service

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.codevoid.wmsproxy.catalog.CapabilitiesFetcher
import de.codevoid.wmsproxy.catalog.CapabilitiesFetcher.FetchResult
import de.codevoid.wmsproxy.catalog.Catalog
import de.codevoid.wmsproxy.catalog.CatalogStore
import de.codevoid.wmsproxy.catalog.ProbeQueue
import de.codevoid.wmsproxy.catalog.UserServices
import de.codevoid.wmsproxy.core.CachedService
import de.codevoid.wmsproxy.core.LayerRow
import de.codevoid.wmsproxy.core.ServiceDetail
import de.codevoid.wmsproxy.core.ServiceItem
import de.codevoid.wmsproxy.core.SkippedLayer
import de.codevoid.wmsproxy.core.SourceValidator
import de.codevoid.wmsproxy.core.XyzTemplate
import de.codevoid.wmsproxy.proxy.ProxyService
import de.codevoid.wmsproxy.proxy.Sources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Whether the service's document is being read, and how the last read went. */
sealed interface ServiceStatus {
    data object Idle : ServiceStatus
    data object Fetching : ServiceStatus
    data class Failed(val message: String) : ServiceStatus
}

/** Everything the detail screen draws. */
data class ServiceUiState(
    /** Null until the list has the service, or when the key names nothing. */
    val item: ServiceItem? = null,
    val cached: CachedService? = null,
    /** The rows narrowed by the layer search. */
    val rows: List<LayerRow> = emptyList(),
    val totalRows: Int = 0,
    val skipped: List<SkippedLayer> = emptyList(),
    /** Loaded rows the proxy has to carry. */
    val proxied: List<LayerRow> = emptyList(),
    val status: ServiceStatus = ServiceStatus.Idle,
    /** Stored paths under measurement. */
    val measuring: Set<String> = emptySet(),
    /** True when a loaded row needs the proxy and it is not running. */
    val proxyOff: Boolean = false,
    /** A one-off message, such as why a layer could not be loaded. */
    val notice: String? = null,
)

/**
 * One service: reads its document the first time (or takes it from the cache), loads
 * and unloads layers, and re-reads on request. Keyed by the service key, one instance
 * per detail screen on the back stack.
 */
class ServiceViewModel(val key: String) : ViewModel() {

    private val layerQuery = MutableStateFlow("")
    private val status = MutableStateFlow<ServiceStatus>(ServiceStatus.Idle)
    private val notice = MutableStateFlow<String?>(null)

    @Volatile
    private var fetchStarted = false

    /** The service URL, or null for a local service, which has nothing to read. */
    private val url: String? = key.takeIf { it.contains("://") }

    private data class Assembled(
        val item: ServiceItem?,
        val cached: CachedService?,
        val rows: List<LayerRow>,
        val query: String,
        val status: ServiceStatus,
        val notice: String?,
    )

    val state: StateFlow<ServiceUiState> =
        combine(Catalog.items, CatalogStore.cache, Sources.config, layerQuery, status) { items, cache, config, query, status ->
            val item = items.firstOrNull { it.key == key }
            val cached = cache[key]
            Assembled(item, cached, item?.let { ServiceDetail.rows(it, cached, config.layers) }.orEmpty(), query, status, null)
        }
            .combine(notice) { assembled, notice -> assembled.copy(notice = notice) }
            .combine(ProbeQueue.pending) { assembled, pending -> assembled to pending }
            .combine(ProxyService.running) { (assembled, pending), running ->
                val proxied = assembled.rows.filter { it.loaded && it.blocker != null }
                ServiceUiState(
                    item = assembled.item,
                    cached = assembled.cached,
                    rows = ServiceDetail.filterRows(assembled.rows, assembled.query),
                    totalRows = assembled.rows.size,
                    skipped = assembled.cached?.skipped.orEmpty(),
                    proxied = proxied,
                    status = assembled.status,
                    measuring = assembled.rows.map { it.candidate.path }.filter { it in pending }.toSet(),
                    proxyOff = proxied.isNotEmpty() && !running,
                    notice = assembled.notice,
                )
            }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ServiceUiState())

    /** Reads the document unless the cache already has it. Called when the screen opens. */
    fun ensureFetched() {
        if (fetchStarted) return
        fetchStarted = true
        val url = url ?: return
        viewModelScope.launch(Dispatchers.IO) {
            CatalogStore.warmUp()
            if (CatalogStore.cache.value[url] != null) return@launch
            read(url)
        }
    }

    fun retry() {
        fetchStarted = false
        ensureFetched()
    }

    /**
     * Reads the document again, forgets every measurement, and re-measures the loaded
     * layers with their fresh templates. A template service has nothing to re-read.
     */
    fun rescan() {
        val url = url ?: return
        if (status.value is ServiceStatus.Fetching) return
        viewModelScope.launch(Dispatchers.IO) {
            val fresh = read(url) ?: return@launch
            val item = Catalog.items.value.firstOrNull { it.key == key } ?: return@launch
            for (row in ServiceDetail.rows(item, fresh, Sources.config.value.layers)) {
                if (!row.loaded || row.stale) continue
                val discovered = fresh.layer(row.id) ?: continue
                val refreshed = row.candidate.copy(urlTemplate = discovered.template, minZoom = null, maxZoom = null)
                Sources.replace(row.candidate, refreshed)
                ProbeQueue.enqueue(key, refreshed, discovered.centre)
            }
        }
    }

    /** Fetches, caches and returns the document, or records the failure and returns null. */
    private fun read(url: String): CachedService? {
        val now = System.currentTimeMillis()
        if (XyzTemplate.isTemplate(url)) {
            return CachedService.forTemplate(url, now).also { CatalogStore.put(it); status.value = ServiceStatus.Idle }
        }
        status.value = ServiceStatus.Fetching
        return when (val result = CapabilitiesFetcher.fetch(url)) {
            is FetchResult.Document -> CachedService.of(url, result.from, result.document, now).also {
                CatalogStore.put(it)
                status.value = ServiceStatus.Idle
            }
            is FetchResult.Failed -> {
                status.value = ServiceStatus.Failed(result.message)
                null
            }
        }
    }

    /**
     * Loads or unloads one layer. Loading stores it at once and measures it afterwards,
     * unless the cache already knows where it answers; the row says which.
     */
    fun setLoaded(row: LayerRow, on: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (!on) {
                Sources.remove(row.candidate)
                return@launch
            }
            if (row.loaded) return@launch
            SourceValidator.validate(row.candidate, Sources.config.value.layers)?.let {
                notice.value = it
                return@launch
            }
            Sources.add(row.candidate)
            val measured = state.value.cached?.measured?.containsKey(row.id) == true
            if (!measured) ProbeQueue.enqueue(key, row.candidate, row.centre)
        }
    }

    fun unloadAll() {
        viewModelScope.launch(Dispatchers.IO) { unloadEverything() }
    }

    /** For an own service: unloads its layers and forgets it. The screen pops afterwards. */
    fun removeService() {
        viewModelScope.launch(Dispatchers.IO) {
            unloadEverything()
            UserServices.removeOwn(key)
            CatalogStore.remove(key)
        }
    }

    private fun unloadEverything() {
        val item = Catalog.items.value.firstOrNull { it.key == key } ?: return
        ServiceDetail.storedFor(item, Sources.config.value.layers).forEach { Sources.remove(it) }
    }

    fun toggleFavorite() = UserServices.toggleFavorite(key)

    fun setLayerQuery(text: String) {
        layerQuery.value = text
    }

    fun clearNotice() {
        notice.value = null
    }
}
