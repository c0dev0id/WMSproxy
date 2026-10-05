package de.codevoid.wmsproxy.core

/**
 * One layer on a service's detail screen: either loaded, in which case [candidate] is
 * the stored layer, or not, in which case [candidate] is what loading it would store,
 * origin set and any cached measurement already applied.
 */
data class LayerRow(
    /** The document's own name for the layer, or the stored path for a row without one. */
    val id: String,
    val title: String,
    val service: ServiceKind?,
    val format: String,
    val candidate: TileLayer,
    val loaded: Boolean,
    val centre: LonLat?,
    /** Stored, but the document no longer offers it. */
    val stale: Boolean = false,
) {
    /** The first thing DMD could not do itself, or null when the layer goes direct. */
    val blocker: Rewrite? get() = candidate.directBlocker()

    fun zoomLabel(): String? = candidate.zoomRangeLabel()
}

/** Builds a service's rows from the cached document and the stored layers. */
object ServiceDetail {

    /** The stored layers that belong to [item]. */
    fun storedFor(item: ServiceItem, stored: List<TileLayer>): List<TileLayer> = when (item.origin) {
        Origin.LOCAL -> stored.filter { it.origin == null && it.source == item.key }
        else -> stored.filter { it.origin == item.key }
    }

    /**
     * The document's layers in its order, each marked loaded where a stored layer matches
     * it, then any stored layer the document no longer lists. Without a document, a local
     * service or an unread one, the stored layers alone.
     */
    fun rows(item: ServiceItem, cached: CachedService?, stored: List<TileLayer>): List<LayerRow> {
        val mine = storedFor(item, stored)
        if (item.origin == Origin.LOCAL || cached == null) return mine.map { storedRow(it, stale = false) }

        val source = ServiceCatalog.sourceIdFor(item, cached)
        val matched = mutableSetOf<TileLayer>()
        val rows = cached.layers.map { discovered ->
            val existing = mine.firstOrNull { it.layer == discovered.storedLayerId() }
            if (existing != null) {
                matched += existing
                LayerRow(discovered.name, discovered.title, discovered.service, discovered.format, existing, true, discovered.centre)
            } else {
                LayerRow(discovered.name, discovered.title, discovered.service, discovered.format, candidateFor(discovered, source, item.key, cached), false, discovered.centre)
            }
        }
        return rows + mine.filterNot { it in matched }.map { storedRow(it, stale = true) }
    }

    /** The rows whose title or id contains [query]; every row for a blank query. */
    fun filterRows(rows: List<LayerRow>, query: String): List<LayerRow> =
        if (query.isBlank()) rows
        else rows.filter { it.title.contains(query, ignoreCase = true) || it.id.contains(query, ignoreCase = true) }

    /** What [discovered] would be stored as, with the cached measurement applied when one exists. */
    private fun candidateFor(discovered: DiscoveredLayer, source: String, origin: String, cached: CachedService): TileLayer {
        val layer = discovered.toTileLayer(source, origin)
        val measured = cached.measured[discovered.name] ?: return layer
        return layer.copy(minZoom = measured.minZoom, maxZoom = measured.maxZoom, urlTemplate = measured.urlTemplate)
    }

    private fun storedRow(stored: TileLayer, stale: Boolean) =
        LayerRow(stored.path, stored.displayName, null, "", stored, true, null, stale)

    /** The layer segment [DiscoveredLayer.toTileLayer] gives this layer: null for a template. */
    private fun DiscoveredLayer.storedLayerId(): String? =
        if (service == ServiceKind.XYZ) null else suggestedLayerId()
}
