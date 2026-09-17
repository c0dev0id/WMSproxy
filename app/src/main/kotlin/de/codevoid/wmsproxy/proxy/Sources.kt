package de.codevoid.wmsproxy.proxy

import de.codevoid.wmsproxy.core.TileMath
import de.codevoid.wmsproxy.core.TileRef

/**
 * One layer of one upstream provider, addressed as `/t/<source>/<layer>/{z}/{x}/{y}`.
 *
 * Source and layer are separate path segments because a single provider commonly hosts
 * many layers — one WMS or WMTS endpoint, dozens of layers — and they share connection
 * settings, credentials and headers. Flattening them into one identifier would lose that
 * grouping the moment configuration exists.
 *
 * Only XYZ-style templates for now. The template is expanded rather than concatenated so
 * placeholders can appear anywhere: real sources put them in the path
 * (`/{z}/{x}/{y}.png`), in the query (`?x={x}&y={y}&z={z}`), or spelled as WMTS KVP
 * (`TileMatrix`/`TileCol`/`TileRow`).
 */
data class TileLayer(
    /** Provider identifier, the first path segment. */
    val source: String,
    /** Layer within that provider, the second path segment. */
    val layer: String,
    val title: String,
    val urlTemplate: String,
    /** OSGeo TMS numbers rows from the south; XYZ from the north. */
    val flipY: Boolean = false,
    /** Values rotated through `{s}`, for sources that shard across subdomains. */
    val subdomains: List<String> = emptyList(),
    /** Sent as Referer; some servers refuse requests without one. */
    val referer: String? = null,
) {
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
        TileLayer(
            source = "autobahn",
            layer = "osm",
            title = "OpenStreetMap (autobahn.de)",
            urlTemplate = "https://tiles.autobahn.de/osm_tiles/{z}/{x}/{y}.png",
        ),
    )

    fun find(source: String, layer: String): TileLayer? =
        all.firstOrNull { it.source == source && it.layer == layer }
}
