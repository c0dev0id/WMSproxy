package de.codevoid.wmsproxy.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One DMD Hub map layer — the rich shape served at `/api/ios/map-layers`, which both
 * readers of the account now render from: the phone's DMD2 and the Hub route planner.
 *
 * The same shape describes a write and a read. On write, [id] is either a client-chosen
 * stable identifier (so re-syncing updates in place) or null (the server mints `ml_<hex>`)
 * and the server-side fields — [rev], [order], [hasSecret], and the probe result — are
 * absent. On read every field the server has filled in is present.
 *
 * [tiles] carries request-ready URLs with subdomains pre-expanded into one entry each,
 * so the readers do not need to know about `{s}`. [source] keeps the template as the
 * user typed it along with the kind of service it is, which lets the planner compose a
 * fresh request for a WMS or WMTS layer instead of reusing [tiles].
 *
 * Credentials travel out of band in an `import`/`update` sibling named `secret`, never
 * on the layer itself — the layer object never carries a key or a password. WMSproxy
 * has no credential concept today, so every layer goes out with [auth] at `"none"`.
 */
@Serializable
data class MapLayer(
    val name: String,
    val tiles: List<String>,
    /** `"xyz"` for north-origin rows, `"tms"` for south-origin — the server flips for us. */
    val scheme: String = DEFAULT_SCHEME,
    @SerialName("tile_size") val tileSize: Int = DEFAULT_TILE_SIZE,
    @SerialName("min_zoom") val minZoom: Int = DEFAULT_MIN_ZOOM,
    @SerialName("max_zoom") val maxZoom: Int = DEFAULT_MAX_ZOOM,
    /** `[west, south, east, north]` in EPSG:4326, or null for the whole world. */
    val bounds: List<Double>? = null,
    val attribution: MapLayerAttribution? = null,
    /** `"base"` stands on its own; `"overlay"` draws on top of a base map. */
    val role: String = DEFAULT_ROLE,
    val opacity: Double = DEFAULT_OPACITY,
    val enabled: MapLayerEnabled = MapLayerEnabled(),
    val auth: MapLayerAuth = MapLayerAuth(),
    @SerialName("token_map") val tokenMap: MapLayerTokens? = null,
    val needs: List<String> = emptyList(),
    val source: MapLayerSource,
    @SerialName("library_id") val libraryId: String? = null,
    val id: String? = null,
    val rev: Int? = null,
    val order: Int? = null,
    @SerialName("has_secret") val hasSecret: Boolean? = null,
)

@Serializable
data class MapLayerAttribution(val text: String = "", val url: String = "")

/** Separate on/off per reader so a layer can be shown in one and hidden in the other. */
@Serializable
data class MapLayerEnabled(val planner: Boolean = true, val dmd2: Boolean = true)

/**
 * `"none"` is the only mode WMSproxy produces today. The field exists because the server
 * defines `"query"` and `"basic"`, and a future WMSproxy credential model would set one
 * of them with [param] naming the query key for a query-mode layer.
 */
@Serializable
data class MapLayerAuth(val mode: String = "none", val param: String? = null)

/**
 * Padded-zoom tokens, one per zoom level: `z[14]` is what replaces `{z}` at zoom 14.
 * Null when no padding applies, so the common case emits nothing.
 */
@Serializable
data class MapLayerTokens(val z: List<String>? = null)

/**
 * Where the layer came from and what kind of service it is. [input] is the template as
 * it was typed, `{s}` and all; [type] is one of `"xyz"`, `"wms"`, `"arcgis_mapserver"`,
 * `"wmts"`. The WMTS-specific fields are set for `"wmts"` and absent otherwise.
 */
@Serializable
data class MapLayerSource(
    val input: String,
    val type: String,
    val layer: String? = null,
    val style: String? = null,
    @SerialName("matrix_set") val matrixSet: String? = null,
    val format: String? = null,
)

/**
 * The GET `/map-layers` envelope. A full fetch carries every layer in [layers] and
 * leaves [deleted] empty; a delta fetch (`?since=<rev>`) carries only the changed layers
 * and the ids of those removed since. [rev] is the new revision to store for the next
 * delta call.
 */
@Serializable
data class MapLayerList(
    val layers: List<MapLayer> = emptyList(),
    val deleted: List<String> = emptyList(),
    val rev: Int = 0,
)

object MapLayerCodec {

    // Lenient in, exact out: a field this build does not know about comes off the wire
    // and is dropped, so the parse stays forward-compatible. On write, null fields are
    // omitted — there is no third form besides present and absent. coerceInputValues
    // because the live server returns `null` for optional fields declared non-nullable
    // with a default; a JSON null against a defaulted field is a decode failure otherwise.
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
    }

    /** The body of POST `/map-layers/import`: `{"layers":[ {…}, {…} ]}`. */
    fun encodeImportBody(layers: List<MapLayer>): String =
        json.encodeToString(ImportBody.serializer(), ImportBody(layers))

    /** The body of PUT `/map-layers/<id>`: `{"layer":{…}}`. */
    fun encodeUpdateBody(layer: MapLayer): String =
        json.encodeToString(UpdateBody.serializer(), UpdateBody(layer))

    /** The body of POST `/map-layers/order`: `{"ids":["ml_…", …]}`. */
    fun encodeReorderBody(ids: List<String>): String =
        json.encodeToString(ReorderBody.serializer(), ReorderBody(ids))

    /** A malformed body is an empty list, never a reason to abort the caller. */
    fun parseList(body: String): MapLayerList =
        runCatching { json.decodeFromString(MapLayerList.serializer(), body) }
            .getOrDefault(MapLayerList())

    @Serializable
    private data class ImportBody(val layers: List<MapLayer>)

    @Serializable
    private data class UpdateBody(val layer: MapLayer)

    @Serializable
    private data class ReorderBody(val ids: List<String>)
}

private const val DEFAULT_SCHEME = "xyz"
private const val DEFAULT_TILE_SIZE = 256
private const val DEFAULT_MIN_ZOOM = 0
private const val DEFAULT_MAX_ZOOM = 24
private const val DEFAULT_ROLE = "overlay"
private const val DEFAULT_OPACITY = 1.0
