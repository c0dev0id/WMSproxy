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
import kotlinx.serialization.json.jsonPrimitive

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

    private const val BBOX_PLACEHOLDER = "{bbox-epsg-3857}"

    private const val SCHEME_XYZ = "xyz"
    private const val SCHEME_TMS = "tms"
    private const val TYPE_XYZ = "xyz"
    private const val TYPE_WMS = "wms"
    private const val TYPE_ARCGIS_MAPSERVER = "arcgis_mapserver"

    /** The account's layers as raw JSON strings beside the id the server gave each. */
    data class AccountEntry(val id: String?, val text: String)

    /**
     * One sync pass, worked out before anything is sent. [layers] holds every loaded
     * layer by its path. [registry] is the stored path→id map pruned to ids the account
     * still has for a loaded layer: those layers are updated, the rest of [layers] is
     * imported. [deletes] go first: our layers no longer loaded and, with a full sync,
     * every layer that is not ours.
     */
    data class SyncPlan(
        val layers: Map<String, MapLayer>,
        val registry: Map<String, String>,
        val deletes: Set<String>,
    )

    /**
     * One [MapLayer] per source: direct where [goesDirect] says the renderer can carry
     * the template itself, through [proxyTemplate] otherwise. Every layer has `id = null`;
     * the caller sets an id for a PUT, or lets the Hub mint one on POST.
     */
    fun layersFor(
        layers: List<TileLayer>,
        proxyTemplate: (TileLayer) -> String,
    ): List<MapLayer> = layers.map { layerFor(it, proxyTemplate) }

    /** The [SyncPlan] for [loaded] against an account holding [accountIds]. */
    fun plan(
        loaded: List<TileLayer>,
        proxyTemplate: (TileLayer) -> String,
        registry: Map<String, String>,
        accountIds: Set<String>,
        fullSync: Boolean,
    ): SyncPlan {
        val layers = loaded.associate { it.path to layerFor(it, proxyTemplate) }
        val live = registry.filterValues { it in accountIds }
        val kept = live.filterKeys { it in layers }
        val deletable = if (fullSync) accountIds else live.values.toSet()
        return SyncPlan(layers, kept, deletable - kept.values.toSet())
    }

    /**
     * Every layer the server returned, each with its id and its JSON text verbatim. The
     * raw text is what the Settings log dumps: a field this build does not know about
     * is the interesting part, and round-tripping through [MapLayer] would drop it.
     */
    fun accountEntries(body: String): List<AccountEntry> =
        rawLayers(body).map { AccountEntry(it.string("id"), it.toString()) }

    /** The id POST `/map-layers/import` minted for the one layer it was sent; null when it took none. */
    fun importedId(body: String): String? = runCatching {
        Json.parseToJsonElement(body).jsonObject["imported"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    private fun layerFor(layer: TileLayer, proxyTemplate: (TileLayer) -> String): MapLayer =
        if (layer.goesDirect()) directMapLayer(layer) else proxyMapLayer(layer, proxyTemplate(layer))

    private fun directMapLayer(layer: TileLayer): MapLayer {
        val original = layer.urlTemplate
        val padWidth = paddedZoomWidth(original)
        return MapLayer(
            name = layer.displayName,
            tiles = tilesFor(original, layer.subdomains),
            scheme = if (layer.flipY) SCHEME_TMS else SCHEME_XYZ,
            tokenMap = padWidth?.let { MapLayerTokens(z = zoomTokens(it)) },
            source = sourceFor(original),
        )
    }

    private fun proxyMapLayer(layer: TileLayer, template: String): MapLayer =
        MapLayer(
            name = layer.displayName,
            tiles = listOf(template),
            source = MapLayerSource(input = template, type = TYPE_XYZ),
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
        (0..DEFAULT_MAX_ZOOM).map { it.toString().padStart(width, '0') }

    private fun sourceFor(template: String): MapLayerSource {
        if (isWmsGetMap(template)) return MapLayerSource(
            input = template,
            type = TYPE_WMS,
            layer = template.queryParameters()["LAYERS"].orEmpty().ifBlank { null },
        )
        if (template.contains("{bbox}")) return MapLayerSource(input = template, type = TYPE_ARCGIS_MAPSERVER)
        return MapLayerSource(input = template, type = TYPE_XYZ)
    }

    private fun isWmsGetMap(template: String): Boolean =
        template.contains("{bbox}") &&
            template.queryParameters()["REQUEST"].equals("GetMap", ignoreCase = true)

    private fun rawLayers(body: String): JsonArray =
        runCatching { Json.parseToJsonElement(body).jsonObject["layers"]?.jsonArray }
            .getOrNull() ?: JsonArray(emptyList())

    private fun JsonElement.string(field: String): String? =
        ((this as? JsonObject)?.get(field) as? JsonPrimitive)?.contentOrNull
}
