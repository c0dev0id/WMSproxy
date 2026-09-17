package de.codevoid.wmsproxy.proxy

import de.codevoid.wmsproxy.core.TileMath
import de.codevoid.wmsproxy.core.TileRef

/**
 * One addressable tile source, served at
 * `/tileproxy/<source>[/<layer>]/{z}/{x}/{y}`.
 *
 * Source and layer are separate path segments because a single provider commonly hosts
 * many layers — one WMS or WMTS endpoint, dozens of layers — and they share connection
 * settings, credentials and headers. Flattening them into one identifier would lose that
 * grouping the moment configuration exists.
 *
 * [layer] is null when the provider has no layer concept, which is the normal case for
 * a plain XYZ template. The segment is then absent from the URL rather than filled with
 * an invented placeholder.
 *
 * Only XYZ-style templates for now. The template is expanded rather than concatenated so
 * placeholders can appear anywhere: real sources put them in the path
 * (`/{z}/{x}/{y}.png`), in the query (`?x={x}&y={y}&z={z}`), or spelled as WMTS KVP
 * (`TileMatrix`/`TileCol`/`TileRow`).
 */
data class TileLayer(
    /** Provider identifier, chosen by the user once configuration exists. */
    val source: String,
    /** Layer within that provider, or null when the provider exposes none. */
    val layer: String? = null,
    val title: String,
    val urlTemplate: String,
    /** OSGeo TMS numbers rows from the south; XYZ from the north. */
    val flipY: Boolean = false,
    /** Values rotated through `{s}`, for sources that shard across subdomains. */
    val subdomains: List<String> = emptyList(),
    /** Sent as Referer; some servers refuse requests without one. */
    val referer: String? = null,
) {
    /** The path this source answers on, without the tile coordinates. */
    val path: String get() = if (layer == null) source else "$source/$layer"

    /** Expands the template for one tile. */
    fun urlFor(tile: TileRef): String {
        val y = if (flipY) TileMath.flipY(tile.zoom, tile.y) else tile.y
        var url = urlTemplate
            .replace("{z}", tile.zoom.toString())
            .replace("{x}", tile.x.toString())
            .replace("{y}", y.toString())
            .replace("{q}", TileMath.quadKey(tile.zoom, tile.x, tile.y))
        if (subdomains.isNotEmpty()) {
            // Deterministic rather than random so the same tile always resolves to the
            // same host, which keeps the client's own cache useful.
            val index = (tile.x + tile.y).mod(subdomains.size)
            url = url.replace("{s}", subdomains[index])
        }
        return url
    }
}

/**
 * Layers available until the configuration UI exists.
 *
 * Hardcoded on purpose: the point of this build is to see what the client actually
 * sends, and that needs a layer which renders. Replaced by stored configuration later.
 */
object BuiltInSources {
    val all: List<TileLayer> = listOf(
        // The reference XYZ source: path-style placeholders, no layer segment, no
        // subdomains. Everything else is measured against how this one behaves.
        //
        // Using it obliges us to honour the OSM Foundation's tile usage policy — an
        // identifying User-Agent (see ProxyServer.USER_AGENT) and light traffic only.
        // The client caches, so a rider generates little, but this is a courtesy host
        // and not a basemap to build on. It is a placeholder until sources are
        // configurable, not the answer to where tiles should come from.
        TileLayer(
            source = "osm",
            title = "OpenStreetMap",
            urlTemplate = "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
        ),
        // Exercises the two paths the OSM layer cannot: `{s}` subdomain rotation, and a
        // source that carries a layer segment. Both are implemented and neither had ever
        // been driven against a live server.
        TileLayer(
            source = "carto",
            layer = "light",
            title = "CARTO Positron",
            urlTemplate = "https://{s}.basemaps.cartocdn.com/light_all/{z}/{x}/{y}.png",
            subdomains = listOf("a", "b", "c", "d"),
        ),
    )

    fun find(source: String, layer: String?): TileLayer? =
        all.firstOrNull { it.source == source && it.layer == layer }
}
