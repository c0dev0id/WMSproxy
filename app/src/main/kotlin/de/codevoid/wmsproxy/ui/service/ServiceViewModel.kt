package de.codevoid.wmsproxy.ui.service

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.codevoid.wmsproxy.catalog.CapabilitiesFetcher
import de.codevoid.wmsproxy.catalog.Catalog
import de.codevoid.wmsproxy.catalog.CatalogStore
import de.codevoid.wmsproxy.catalog.UserServices
import de.codevoid.wmsproxy.core.CachedService
import de.codevoid.wmsproxy.core.LayerRow
import de.codevoid.wmsproxy.core.ServiceCatalog
import de.codevoid.wmsproxy.core.ServiceDetail
import de.codevoid.wmsproxy.core.ServiceItem
import de.codevoid.wmsproxy.core.ServiceReader
import de.codevoid.wmsproxy.core.SkippedLayer
import de.codevoid.wmsproxy.core.SourceValidator
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
    /** True when a loaded row needs the proxy and it is not running. */
    val proxyOff: Boolean = false,
    /** A one-off message, such as why a layer could not be loaded. */
    val notice: String? = null,
) {
    /** How many of [rows] are loaded; with [rows] it sets the select-all checkbox. */
    val visibleLoaded: Int get() = rows.count { it.loaded }
}

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
    private val url: String? = ServiceCatalog.urlOf(key)

    /** The rows and what follows from them, rebuilt when the stored layers or the document change. */
    private class Assembled(val item: ServiceItem?, val cached: CachedService?, val rows: List<LayerRow>) {
        val proxied: List<LayerRow> = rows.filter { it.loaded && it.blocker != null }
    }

    private val assembled = combine(Catalog.items, CatalogStore.cache, Sources.config) { items, cache, config ->
        val item = items.firstOrNull { it.key == key }
        val cached = cache[key]
        Assembled(item, cached, item?.let { ServiceDetail.rows(it, cached, config.layers) }.orEmpty())
    }

    val state: StateFlow<ServiceUiState> =
        combine(assembled, layerQuery, status, notice, ProxyService.running) { a, query, status, notice, running ->
            ServiceUiState(
                item = a.item,
                cached = a.cached,
                rows = ServiceDetail.filterRows(a.rows, query),
                totalRows = a.rows.size,
                skipped = a.cached?.skipped.orEmpty(),
                proxied = a.proxied,
                status = status,
                proxyOff = a.proxied.isNotEmpty() && !running,
                notice = notice,
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
     * Reads the document again and brings every loaded layer's address up to date with
     * it, so a server that moved its endpoint is followed. A template service has
     * nothing to re-read.
     */
    fun rescan() {
        val url = url ?: return
        if (status.value is ServiceStatus.Fetching) return
        viewModelScope.launch(Dispatchers.IO) {
            val fresh = read(url) ?: return@launch
            val item = Catalog.items.value.firstOrNull { it.key == key } ?: return@launch
            val moved = ServiceDetail.rows(item, fresh, Sources.config.value.layers)
                .filter { it.loaded && !it.stale }
                .mapNotNull { row ->
                    val template = fresh.layer(row.id)?.template
                    if (template == null || template == row.candidate.urlTemplate) null
                    else row.candidate to row.candidate.copy(urlTemplate = template)
                }
                .toMap()
            Sources.replaceAll(moved)
        }
    }

    /** Reads and caches the service, or records the failure and returns null. */
    private fun read(url: String): CachedService? {
        status.value = ServiceStatus.Fetching
        return when (val result = CapabilitiesFetcher.read(url)) {
            is ServiceReader.Read.Service -> result.service.also { status.value = ServiceStatus.Idle }
            is ServiceReader.Read.Failed -> {
                status.value = ServiceStatus.Failed(result.message)
                null
            }
        }
    }

    /** Loads or unloads one layer: stored or removed at once, nothing asked of the server. */
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
        }
    }

    /** Loads or unloads every row on screen, the search honoured: nothing hidden changes. */
    fun setAllVisibleLoaded(on: Boolean) {
        val rows = state.value.rows
        viewModelScope.launch(Dispatchers.IO) {
            if (on) {
                val batch = ServiceDetail.toLoad(rows, Sources.config.value.layers)
                Sources.addAll(batch.rows.map { it.candidate })
                batch.notice()?.let { notice.value = it }
            } else {
                Sources.removeAll(ServiceDetail.toUnload(rows))
            }
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
        Sources.removeAll(ServiceDetail.storedFor(item, Sources.config.value.layers))
    }

    fun toggleFavorite() = UserServices.toggleFavorite(key)

    fun setLayerQuery(text: String) {
        layerQuery.value = text
    }

    fun clearNotice() {
        notice.value = null
    }
}
