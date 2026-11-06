package de.codevoid.wmsproxy.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One DMD Hub map layer in the shape `/api/ios/map-layers/import` consumes. [tiles]
 * carries request-ready URLs with subdomains pre-expanded, so the reader does not need
 * to know about `{s}`. [source] keeps the template as the user typed it along with the
 * kind of service it is, which lets the planner compose a fresh request for a WMS layer
 * instead of reusing [tiles].
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
    /** `"base"` stands on its own; `"overlay"` draws on top of a base map. */
    val role: String = DEFAULT_ROLE,
    val opacity: Double = DEFAULT_OPACITY,
    val enabled: MapLayerEnabled = MapLayerEnabled(),
    val auth: MapLayerAuth = MapLayerAuth(),
    @SerialName("token_map") val tokenMap: MapLayerTokens? = null,
    val source: MapLayerSource,
    val id: String? = null,
)

/** Separate on/off per reader so a layer can be shown in one and hidden in the other. */
@Serializable
data class MapLayerEnabled(val planner: Boolean = true, val dmd2: Boolean = true)

/** `"none"` is the only mode WMSproxy produces today; the server also defines `"query"` and `"basic"`. */
@Serializable
data class MapLayerAuth(val mode: String = "none")

/** Padded-zoom tokens, one per zoom level: `z[14]` is what replaces `{z}` at zoom 14. */
@Serializable
data class MapLayerTokens(val z: List<String>)

/**
 * Where the layer came from and what kind of service it is. [input] is the template as
 * it was typed, `{s}` and all; [type] is one of `"xyz"`, `"wms"`, `"arcgis_mapserver"`.
 */
@Serializable
data class MapLayerSource(
    val input: String,
    val type: String,
    val layer: String? = null,
)

object MapLayerCodec {

    // encodeDefaults so the server sees the fields WMSproxy relies on (scheme, tile_size,
    // auth, enabled); explicitNulls off so the optional source fields stay out of the body.
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    /** The body of POST `/map-layers/import`: `{"layers":[ {…}, {…} ]}`. */
    fun encodeImportBody(layers: List<MapLayer>): String =
        json.encodeToString(ImportBody.serializer(), ImportBody(layers))

    @Serializable
    private data class ImportBody(val layers: List<MapLayer>)
}

private const val DEFAULT_SCHEME = "xyz"
private const val DEFAULT_TILE_SIZE = 256
private const val DEFAULT_MIN_ZOOM = 0
private const val DEFAULT_MAX_ZOOM = 24
private const val DEFAULT_ROLE = "overlay"
private const val DEFAULT_OPACITY = 1.0
