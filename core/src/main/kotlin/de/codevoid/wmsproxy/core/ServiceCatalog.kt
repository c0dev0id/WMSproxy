package de.codevoid.wmsproxy.core

/** Where a service in the list came from. */
enum class Origin {
    /** Shipped in the bundled library. */
    LIBRARY,
    /** Added by the user by address, or loaded from an address the library no longer has. */
    OWN,
    /** Layers stored by hand before services existed: no address, nothing to read. */
    LOCAL,
}

/**
 * One row of the list: a service, with what the user has done to it.
 *
 * [key] is the service URL for library and own services and the bare source name for a
 * local one; URLs carry `://` and source names cannot, so the two never collide.
 * [available] is how many layers the service offers: the cached document's count once
 * read, the library's measured count before that, null when nothing is known.
 */
data class ServiceItem(
    val key: String,
    val name: String,
    val region: String,
    val category: String,
    val note: String,
    val origin: Origin,
    val favorite: Boolean,
    val loaded: Int,
    val available: Int?,
    /** Loaded layers the proxy has to carry because DMD cannot fill their template itself. */
    val proxied: Int,
    /** What the search sees of each layer the read document offers; empty when unread. */
    val searchableLayers: List<String> = emptyList(),
    /** Set by [ServiceCatalog.filtered]: how many layers the search matched. */
    val matchingLayers: Int = 0,
    /** Set by [ServiceCatalog.filtered]: whether the name or note matched; true with no search. */
    val matchedByName: Boolean = true,
) {
    val isLoaded: Boolean get() = loaded > 0
    val needsProxy: Boolean get() = proxied > 0

    /** The address a service is read from; null for a local one, which has none. */
    val url: String? get() = ServiceCatalog.urlOf(key)
}

/** What the list is narrowed to. A null region or category matches everything. */
data class CatalogFilter(
    val favorites: Boolean = false,
    val loaded: Boolean = false,
    val region: String? = null,
    val category: String? = null,
    val query: String = "",
) {
    val active: Boolean get() = favorites || loaded || region != null || category != null || query.isNotBlank()
}

/**
 * The one list: library entries, the user's own services and whatever was stored by
 * hand, each with its loaded count, measured from the stored layers rather than kept.
 */
object ServiceCatalog {

    /** The region the user's own and local services are shown under. */
    const val MINE = "Mine"

    /** The address behind a key, or null for a local service: URLs carry `://`, source names cannot. */
    fun urlOf(key: String): String? = key.takeIf { it.contains("://") }

    private const val FALLBACK_SOURCE = "service"

    fun assemble(
        library: SourceLibrary,
        user: UserCatalog,
        cache: CatalogCache,
        stored: List<TileLayer>,
    ): List<ServiceItem> {
        val byOrigin = stored.filter { it.origin != null }.groupBy { it.origin!! }
        val local = stored.filter { it.origin == null }.groupBy { it.source }
        val known = mutableSetOf<String>()
        val items = mutableListOf<ServiceItem>()

        for (entry in library.entries) {
            known += entry.url
            val cached = cache[entry.url]
            items += item(
                key = entry.url,
                name = entry.name,
                region = entry.region,
                category = entry.category,
                note = entry.note,
                origin = Origin.LIBRARY,
                user = user,
                cached = cached,
                mine = byOrigin[entry.url].orEmpty(),
                fallbackAvailable = entry.usable.takeIf { entry.measured },
            )
        }
        for (own in user.own) {
            // An address that is already a library entry opens that entry; it is not listed twice.
            if (!known.add(own.url)) continue
            items += ownItem(own.url, user, cache[own.url], byOrigin[own.url].orEmpty())
        }
        // Layers loaded from an address neither the library nor the user's file knows any
        // more — a library URL that changed between releases — still need a row to be
        // seen, unloaded and previewed from.
        for ((origin, layers) in byOrigin) {
            if (!known.add(origin)) continue
            items += ownItem(origin, user, cache[origin], layers)
        }
        for ((source, layers) in local) {
            items += ServiceItem(
                key = source,
                name = source,
                region = MINE,
                category = "",
                note = "",
                origin = Origin.LOCAL,
                favorite = user.isFavorite(source),
                loaded = layers.size,
                available = layers.size,
                proxied = proxied(layers).size,
                searchableLayers = layers.map { it.displayName.lowercase() },
            )
        }
        return items
    }

    private fun ownItem(url: String, user: UserCatalog, cached: CachedService?, mine: List<TileLayer>) = item(
        key = url,
        name = cached?.title?.ifBlank { null } ?: Urls.hostOf(url).ifBlank { url },
        region = MINE,
        category = "",
        note = "",
        origin = Origin.OWN,
        user = user,
        cached = cached,
        mine = mine,
        fallbackAvailable = null,
    )

    private fun item(
        key: String,
        name: String,
        region: String,
        category: String,
        note: String,
        origin: Origin,
        user: UserCatalog,
        cached: CachedService?,
        mine: List<TileLayer>,
        fallbackAvailable: Int?,
    ) = ServiceItem(
        key = key,
        name = name,
        region = region,
        category = category,
        note = note,
        origin = origin,
        favorite = user.isFavorite(key),
        loaded = mine.size,
        available = cached?.layers?.size ?: fallbackAvailable,
        proxied = proxied(mine).size,
        searchableLayers = cached?.searchableLayers.orEmpty(),
    )

    /** The stored layers DMD cannot address itself, so the proxy has to be running for them. */
    fun proxied(stored: List<TileLayer>): List<TileLayer> = stored.filter { it.directBlocker() != null }

    /**
     * The items matching [filter], grouped by region in display order: [MINE] first, then
     * the library's own wide regions in its order, then the rest alphabetically; names
     * alphabetical within a region. The search looks at the name, the note and the
     * layers a service is known to offer, and each item says which of those matched.
     */
    fun filtered(
        items: List<ServiceItem>,
        filter: CatalogFilter,
        regionOrder: List<String>,
    ): Map<String, List<ServiceItem>> =
        items.asSequence()
            .filter { !filter.favorites || it.favorite }
            .filter { !filter.loaded || it.isLoaded }
            .filter { filter.region == null || it.region == filter.region }
            .filter { filter.category == null || it.category == filter.category }
            .mapNotNull { it.matched(filter.query) }
            .sortedWith(compareBy({ rank(it.region, regionOrder) }, { it.region }, { it.name }))
            .groupBy { it.region }

    /** This item with its match recorded, or null when nothing in it matches [query]. */
    private fun ServiceItem.matched(query: String): ServiceItem? {
        if (query.isBlank()) return this
        val byName = name.contains(query, ignoreCase = true) || note.contains(query, ignoreCase = true)
        val lower = query.lowercase()
        val hits = searchableLayers.count { it.contains(lower) }
        if (!byName && hits == 0) return null
        return copy(matchingLayers = hits, matchedByName = byName)
    }

    /** Every region present, in the order [filtered] shows them. */
    fun regions(items: List<ServiceItem>, regionOrder: List<String>): List<String> =
        items.map { it.region }.distinct().sortedWith(compareBy({ rank(it, regionOrder) }, { it }))

    /** Every non-blank category present, alphabetically. */
    fun categories(items: List<ServiceItem>): List<String> =
        items.mapNotNull { it.category.takeIf(String::isNotBlank) }.distinct().sorted()

    private fun rank(region: String, regionOrder: List<String>): Int = when {
        region == MINE -> -1
        else -> regionOrder.indexOf(region).takeIf { it >= 0 } ?: regionOrder.size
    }

    /**
     * The source path segment layers of [item] are stored under: the item's name, which
     * is the library entry's name or, for an own service, its title once read and its
     * host before; a local service's own name. Derived rather than stored, so the same
     * service always loads to the same route.
     */
    fun sourceIdFor(item: ServiceItem): String = when (item.origin) {
        Origin.LIBRARY, Origin.OWN -> SourceValidator.asPathSegment(item.name, fallback = FALLBACK_SOURCE)
        Origin.LOCAL -> item.key
    }
}
