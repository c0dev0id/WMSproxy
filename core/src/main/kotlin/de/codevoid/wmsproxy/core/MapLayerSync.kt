package de.codevoid.wmsproxy.core

import de.codevoid.wmsproxy.core.http.queryParameters

/**
 * The rewrites DMD's map-layer renderer handles natively, so a source needing one of
 * them can go direct rather than through WMSproxy: `scheme:"tms"` flips rows, `tiles[]`
 * carries one entry per subdomain, `token_map.z` names the padded-zoom tokens, and a
 * `{bbox-epsg-3857}` in `tiles[0]` lets the planner compose its own GetMap from
 * `source`. A rewrite not in this set — a quadkey, a Referer header, a cleartext
 * upstream — still needs the proxy.
 */
private val MAP_LAYER_SUBSTITUTES = setOf(
    Rewrite.WMS_BBOX,
    Rewrite.PADDED_ZOOM,
    Rewrite.FLIPPED_ROWS,
    Rewrite.SUBDOMAINS,
)

/**
 * The first rewrite a source needs that the map-layer renderer cannot do by itself, or
 * null when nothing blocks direct use. The map-layer counterpart to [directBlocker],
 * for the richer /api/ios/map-layers shape.
 */
fun TileLayer.mapLayerBlocker(): Rewrite? =
    rewrites().firstOrNull { it !in MAP_LAYER_SUBSTITUTES }

/** True when the source can be addressed by DMD itself in the map-layer shape. */
fun TileLayer.goesDirectAsMapLayer(): Boolean = mapLayerBlocker() == null

/**
 * Turns WMSproxy sources into DMD map layers in the shape POST `/map-layers/import`
 * consumes. One entry per source — the phone/planner split the legacy API required is
 * gone, because both readers render the same object.
 *
 * Pure string work, so it is unit-tested without a device: the HTTP calls live in `:app`
 * (`DmdHub`), which hands the server's bodies to this object and sends back what it
 * returns.
 */
object MapLayerSync {

    private const val ID_PREFIX = "cl_wmsproxy_"
    private const val BBOX_PLACEHOLDER = "{bbox-epsg-3857}"

    /**
     * Zoom levels for which [MapLayerTokens] emits a padded-zoom token when a source
     * uses `{z:NN}`. WMSproxy does not measure a source's real maximum, so the token
     * list is produced for every integer from zero to this one — a few unused entries
     * past the server's real cap are cheap and keep the common case correct.
     */
    private const val TOKEN_MAP_MAX_ZOOM = 24

    /** A stable id per source, so a re-import updates in place. Mirrors the old sync. */
    fun layerId(path: String): String = ID_PREFIX + path.replace('/', '_')

    /** True when [id] was written by this app, so the sync may rewrite or delete it. */
    fun isOurs(id: String): Boolean = id.startsWith(ID_PREFIX)

    /**
     * One [MapLayer] per source: direct where [goesDirectAsMapLayer] says the renderer
     * can carry the template itself, through [proxyTemplate] otherwise.
     */
    fun layersFor(
        layers: List<TileLayer>,
        proxyTemplate: (TileLayer) -> String,
    ): List<MapLayer> = layers.map { layer ->
        if (layer.goesDirectAsMapLayer()) directMapLayer(layer)
        else proxyMapLayer(layer, proxyTemplate(layer))
    }

    private fun directMapLayer(layer: TileLayer): MapLayer {
        val original = layer.urlTemplate
        val padWidth = paddedZoomWidth(original)
        return MapLayer(
            name = layer.displayName,
            tiles = tilesFor(original, layer.subdomains),
            scheme = if (layer.flipY) "tms" else "xyz",
            tokenMap = padWidth?.let { MapLayerTokens(z = zoomTokens(it)) },
            source = sourceFor(original),
            id = layerId(layer.path),
        )
    }

    private fun proxyMapLayer(layer: TileLayer, template: String): MapLayer =
        MapLayer(
            name = layer.displayName,
            tiles = listOf(template),
            source = MapLayerSource(input = template, type = "xyz"),
            id = layerId(layer.path),
        )

    /**
     * The entries for `tiles[]`: the template normalised to the placeholders the
     * renderer substitutes (`{bbox-epsg-3857}` for a bbox, `{z}` for a padded zoom)
     * and expanded across [subdomains] where `{s}` appears.
     */
    private fun tilesFor(template: String, subdomains: List<String>): List<String> {
        val normalised = TileLayer.PADDED_ZOOM
            .replace(template.replace("{bbox}", BBOX_PLACEHOLDER), "{z}")
        return expandSubdomains(normalised, subdomains)
    }

    private fun expandSubdomains(template: String, subdomains: List<String>): List<String> =
        if (template.contains("{s}") && subdomains.isNotEmpty()) {
            subdomains.map { template.replace("{s}", it) }
        } else {
            listOf(template)
        }

    private fun paddedZoomWidth(template: String): Int? =
        TileLayer.PADDED_ZOOM.find(template)?.groupValues?.get(1)?.toInt()

    private fun zoomTokens(width: Int): List<String> =
        (0..TOKEN_MAP_MAX_ZOOM).map { it.toString().padStart(width, '0') }

    private fun sourceFor(template: String): MapLayerSource = when {
        isWmsGetMap(template) -> MapLayerSource(
            input = template,
            type = "wms",
            layer = template.queryParameters()["LAYERS"].orEmpty().ifBlank { null },
        )
        isArcgisExport(template) -> MapLayerSource(
            input = template,
            type = "arcgis_mapserver",
        )
        else -> MapLayerSource(input = template, type = "xyz")
    }

    private fun isWmsGetMap(template: String): Boolean =
        template.contains("{bbox}") &&
            template.queryParameters()["REQUEST"].equals("GetMap", ignoreCase = true)

    private fun isArcgisExport(template: String): Boolean =
        template.contains("{bbox}") && !isWmsGetMap(template)
}
