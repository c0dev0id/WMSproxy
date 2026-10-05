package de.codevoid.wmsproxy.catalog

import android.content.Context
import de.codevoid.wmsproxy.core.ServiceCatalog
import de.codevoid.wmsproxy.core.ServiceItem
import de.codevoid.wmsproxy.core.SourceLibrary
import de.codevoid.wmsproxy.proxy.BundledLibrary
import de.codevoid.wmsproxy.proxy.Sources
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlin.concurrent.thread

/**
 * The one list, assembled once from the library, the user's file, the cache and the
 * stored layers whenever any of them changes, and shared by every screen that reads it.
 */
object Catalog {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _library = MutableStateFlow(SourceLibrary())
    val library: StateFlow<SourceLibrary> = _library.asStateFlow()

    val items: StateFlow<List<ServiceItem>> =
        combine(_library, UserServices.state, CatalogStore.cache, Sources.config) { library, user, cache, config ->
            ServiceCatalog.assemble(library, user, cache, config.layers)
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Called once from [de.codevoid.wmsproxy.WmsProxyApp]; the asset is read off the main thread. */
    fun init(context: Context) {
        val app = context.applicationContext
        thread(name = "library-load") { _library.value = BundledLibrary.get(app) }
    }
}
