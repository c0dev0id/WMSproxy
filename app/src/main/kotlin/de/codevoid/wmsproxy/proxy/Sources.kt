package de.codevoid.wmsproxy.proxy

import de.codevoid.wmsproxy.core.TileMath
import de.codevoid.wmsproxy.core.TileRef
import de.codevoid.wmsproxy.core.wms.ProxyLayer

/**
 * An upstream tile source and the URL template it is addressed by.
 *
 * Only XYZ-style templates for now. The template is expanded rather than concatenated so
 * the placeholders can appear anywhere — real sources put them in the path
 * (`/{z}/{x}/{y}.png`) or in the query (`?x={x}&y={y}&z={z}`), and WMTS KVP spells them
 * as TileMatrix/TileCol/TileRow.
 */
data class TileSource(
    val id: String,
    val title: String,
    val urlTemplate: String,
    /** OSGeo TMS numbers rows from the south; XYZ from the north. */
    val flipY: Boolean = false,
    /** Values rotated through `{s}`, for sources that shard across subdomains. */
    val subdomains: List<String> = emptyList(),
    /** Sent as Referer; some servers refuse requests without one. */
    val referer: String? = null,
) {
    fun asLayer(): ProxyLayer = ProxyLayer(id = id, title = title, abstract = urlTemplate)

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
            // same host, which keeps a client's own cache useful.
            val index = (tile.x + tile.y).mod(subdomains.size)
            url = url.replace("{s}", subdomains[index])
        }
        return url
    }
}

/**
 * Sources available until the configuration UI exists.
 *
 * Hardcoded on purpose: the point of this build is to see what DMD2 actually sends, and
 * that needs a layer which renders. Replaced by stored configuration in a later step.
 */
object BuiltInSources {
    val all: List<TileSource> = listOf(
        TileSource(
            id = "osm",
            title = "OpenStreetMap (autobahn.de)",
            urlTemplate = "https://tiles.autobahn.de/osm_tiles/{z}/{x}/{y}.png",
        ),
    )

    fun byId(id: String): TileSource? = all.firstOrNull { it.id == id }
}
