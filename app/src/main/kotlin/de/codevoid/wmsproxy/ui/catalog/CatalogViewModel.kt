package de.codevoid.wmsproxy.ui.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.codevoid.wmsproxy.catalog.Catalog
import de.codevoid.wmsproxy.catalog.CatalogStore
import de.codevoid.wmsproxy.catalog.ProbeQueue
import de.codevoid.wmsproxy.catalog.UserServices
import de.codevoid.wmsproxy.core.CatalogFilter
import de.codevoid.wmsproxy.core.ServiceCatalog
import de.codevoid.wmsproxy.core.ServiceItem
import de.codevoid.wmsproxy.library.LibraryPrefs
import de.codevoid.wmsproxy.proxy.ProxyService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Everything the list screen draws, computed off the main thread. */
data class CatalogUiState(
    val groups: Map<String, List<ServiceItem>> = emptyMap(),
    val regions: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
    val filter: CatalogFilter = CatalogFilter(),
    /** Loaded layers that need the proxy while it is off; zero when nothing is wrong. */
    val proxyWarning: Int = 0,
    val probing: ProbeQueue.Progress? = null,
    val verified: String = "",
    val total: Int = 0,
)

/** What adding an address by hand led to. */
sealed interface AddResult {
    /** The service to open: a new own service, or the library entry that already had the address. */
    data class Opened(val key: String) : AddResult
    data object Invalid : AddResult
}

class CatalogViewModel : ViewModel() {

    private val query = MutableStateFlow("")

    private data class Narrowed(
        val groups: Map<String, List<ServiceItem>>,
        val regions: List<String>,
        val categories: List<String>,
        val filter: CatalogFilter,
        val proxied: Int,
        val verified: String,
        val total: Int,
    )

    val state: StateFlow<CatalogUiState> =
        combine(Catalog.items, Catalog.library, LibraryPrefs.filter, query) { items, library, stored, typed ->
            val filter = stored.copy(query = typed)
            Narrowed(
                groups = ServiceCatalog.filtered(items, filter, library.regions),
                regions = ServiceCatalog.regions(items, library.regions),
                categories = ServiceCatalog.categories(items),
                filter = filter,
                proxied = items.sumOf { it.proxied },
                verified = library.verified,
                total = items.size,
            )
        }
            .combine(ProxyService.running) { narrowed, running -> narrowed to running }
            .combine(ProbeQueue.progress) { (narrowed, running), probing ->
                CatalogUiState(
                    groups = narrowed.groups,
                    regions = narrowed.regions,
                    categories = narrowed.categories,
                    filter = narrowed.filter,
                    proxyWarning = if (running) 0 else narrowed.proxied,
                    probing = probing,
                    verified = narrowed.verified,
                    total = narrowed.total,
                )
            }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CatalogUiState())

    init {
        // The cache of read documents is what turns a library count into a loaded one.
        viewModelScope.launch(Dispatchers.IO) { CatalogStore.warmUp() }
    }

    fun setQuery(text: String) {
        query.value = text
    }

    fun setFilter(change: (CatalogFilter) -> CatalogFilter) = LibraryPrefs.setFilter(change)

    /** Resets the chips; the search text is the screen's and is cleared there. */
    fun clearFilters() = LibraryPrefs.setFilter { CatalogFilter() }

    fun toggleFavorite(key: String) = UserServices.toggleFavorite(key)

    /**
     * Takes an address typed by hand. One the library already has opens the library's
     * entry rather than a second copy; anything else becomes an own service, read when
     * its screen opens.
     */
    fun addService(raw: String): AddResult {
        val url = raw.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) return AddResult.Invalid
        if (Catalog.library.value.entries.none { it.url == url }) UserServices.addOwn(url)
        return AddResult.Opened(url)
    }
}
