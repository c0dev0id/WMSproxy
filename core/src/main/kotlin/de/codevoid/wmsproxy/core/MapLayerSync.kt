package de.codevoid.wmsproxy.core

import de.codevoid.wmsproxy.core.http.queryParameters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * The rewrites DMD's map-layer renderer handles natively, so a source needing one of
 * them can go direct rather than through WMSproxy: `scheme:"tms"` flips rows, `tiles[]`
 * carries one entry per subdomain, `token_map.z` names the padded-zoom tokens, and a
 * `{bbox-epsg-3857}` in `tiles[0]` lets the planner compose its own GetMap from
 * `source`. A rewrite not in this set — a quadkey, a Referer header, a cleartext
 * upstream — still needs the proxy.
 */
private val DMD_SUBSTITUTES = setOf(
    Rewrite.WMS_BBOX,
    Rewrite.PADDED_ZOOM,
    Rewrite.FLIPPED_ROWS,
    Rewrite.SUBDOMAINS,
)

/**
 * The first rewrite a source needs that DMD's map-layer renderer cannot do by itself, or
 * null when nothing blocks direct use. The connection is one of them: DMD refuses
 * cleartext under its network security policy — the reason the proxy serves HTTPS at
 * all — and the planner is a page served over HTTPS, which a browser will not let
 * fetch plain-HTTP tiles. A `http://` source therefore goes through the proxy, whose
 * certificate is what makes it reachable.
 */
fun TileLayer.directBlocker(): Rewrite? =
    rewrites().firstOrNull { it !in DMD_SUBSTITUTES }

/**
 * True when DMD can address this layer itself: nothing in it needs the proxy. Direct is
 * the only mode; where it is not possible the proxy's address goes instead, and the UI
 * says why.
 */
fun TileLayer.goesDirect(): Boolean = directBlocker() == null

/** How much of a service DMD can address itself, judged over the layers it offers. */
enum class DmdReach {
    DIRECT, MIXED, PROXY;

    companion object {
        /** Null for no layers: nothing to judge. */
        fun of(layers: List<TileLayer>): DmdReach? {
            if (layers.isEmpty()) return null
            return when (layers.count { it.goesDirect() }) {
                layers.size -> DIRECT
                0 -> PROXY
                else -> MIXED
            }
        }
    }
}

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

    // Raw-JSON helpers below are so a field this build does not know about survives the
    // dump — a map layer DMD wrote itself is the reference for what this app reproduces.
    private val rawJson = Json { ignoreUnknownKeys = true }

    /** A stable id per source, so a re-import updates in place. Mirrors the old sync. */
    fun layerId(path: String): String = ID_PREFIX + path.replace('/', '_')

    /** True when [id] was written by this app, so the sync may rewrite or delete it. */
    fun isOurs(id: String): Boolean = id.startsWith(ID_PREFIX)

    /**
     * One [MapLayer] per source: direct where [goesDirect] says the renderer can carry
     * the template itself, through [proxyTemplate] otherwise.
     */
    fun layersFor(
        layers: List<TileLayer>,
        proxyTemplate: (TileLayer) -> String,
    ): List<MapLayer> = layers.map { layer ->
        if (layer.goesDirect()) directMapLayer(layer)
        else proxyMapLayer(layer, proxyTemplate(layer))
    }

    /**
     * Every layer in the server's body, each exactly as sent, ours included. This is
     * the dump the Settings log offers: a layer DMD wrote itself sits beside one this
     * app pushed, so a difference in form can be read off rather than guessed at.
     */
    fun accountEntries(body: String): List<String> = rawLayers(body).map { it.toString() }

    /**
     * The account's layers this app did not write, verbatim. Verbatim rather than
     * parsed, because a layer DMD wrote itself is the reference for the form this app
     * reproduces, and a field this build does not know about is the interesting part.
     */
    fun foreignEntries(body: String): List<String> =
        rawLayers(body)
            .filter { entry -> entry.string("id")?.let(::isOurs) != true }
            .map { it.toString() }

    /** Every id in [body] that carries this app's prefix. For finding orphans to delete. */
    fun ourIdsIn(body: String): List<String> =
        rawLayers(body).mapNotNull { entry -> entry.string("id")?.takeIf(::isOurs) }

    /** Every non-ours id in [body]. Used when a full sync deletes foreign layers too. */
    fun foreignIdsIn(body: String): List<String> =
        rawLayers(body).mapNotNull { entry ->
            entry.string("id")?.takeIf { !isOurs(it) }
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

    private fun rawLayers(body: String): JsonArray =
        runCatching { rawJson.parseToJsonElement(body).jsonObject["layers"]?.jsonArray }
            .getOrNull() ?: JsonArray(emptyList())

    private fun JsonElement.string(field: String): String? =
        ((this as? JsonObject)?.get(field) as? JsonPrimitive)?.contentOrNull
}
