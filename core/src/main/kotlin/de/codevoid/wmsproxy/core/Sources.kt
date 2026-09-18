package de.codevoid.wmsproxy.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One addressable tile source, served at `/tileproxy/<source>[/<layer>]/{z}/{x}/{y}`.
 *
 * Source and layer are separate path segments because a single provider commonly hosts
 * many layers — one WMS or WMTS endpoint, dozens of layers — sharing connection
 * settings, credentials and headers. Flattening them into one identifier would lose that
 * grouping.
 *
 * [layer] is null when the provider has no layer concept, the normal case for a plain
 * XYZ template. The segment is then absent from the URL rather than filled with an
 * invented placeholder.
 *
 * The template is expanded rather than concatenated, so placeholders can sit anywhere:
 * real sources put them in the path (`/{z}/{x}/{y}.png`), in the query
 * (`?x={x}&y={y}&z={z}`), or spelled as WMTS KVP (`TileMatrix`/`TileCol`/`TileRow`).
 */
@Serializable
data class TileLayer(
    /** Provider identifier, chosen by the user and used as a URL path segment. */
    val source: String,
    /** Layer within that provider, or null when the provider exposes none. */
    val layer: String? = null,
    val title: String = "",
    val urlTemplate: String = "",
    /** OSGeo TMS numbers rows from the south; XYZ from the north. */
    val flipY: Boolean = false,
    /** Values rotated through `{s}`, for sources that shard across subdomains. */
    val subdomains: List<String> = emptyList(),
    /** Sent as Referer; some servers refuse requests without one. */
    val referer: String? = null,
) {
    /** The path this source answers on, without the tile coordinates. */
    val path: String get() = if (layer == null) source else "$source/$layer"

    /**
     * Expands the template for one tile.
     *
     * `{bbox}` is what lets a WMS server be an upstream without a second code path. A
     * tile has an exact WebMercator extent, so a `GetMap` for that extent at 256×256 is
     * the same picture — the request is still only a string being filled in, and no
     * pixel is touched. It is arithmetic from the tile index, not a reprojection.
     */
    fun urlFor(tile: TileRef): String {
        val y = if (flipY) TileMath.flipY(tile.zoom, tile.y) else tile.y
        var url = urlTemplate
            .replace("{z}", tile.zoom.toString())
            .replace("{x}", tile.x.toString())
            .replace("{y}", y.toString())
            .replace("{q}", TileMath.quadKey(tile.zoom, tile.x, tile.y))
        if (url.contains("{bbox}")) {
            // Always the unflipped row: the extent is a property of the tile, not of the
            // row numbering the upstream happens to use.
            url = url.replace("{bbox}", TileMath.tileBbox(tile.zoom, tile.x, tile.y).asWmsParameter())
        }
        if (subdomains.isNotEmpty()) {
            // Deterministic rather than random so the same tile always resolves to the
            // same host, which keeps the client's own cache useful.
            val index = (tile.x + tile.y).mod(subdomains.size)
            url = url.replace("{s}", subdomains[index])
        }
        return url
    }
}

/** The stored set of sources. A wrapper, so the file can gain fields without a rewrite. */
@Serializable
data class SourceConfig(
    val layers: List<TileLayer> = emptyList(),
    /**
     * Which scheme the displayed tile URLs use. One setting for every source, because it
     * is a property of the client reading them rather than of any one server.
     *
     * Defaults to HTTPS: a client that refuses cleartext to loopback is the reason the
     * TLS listener exists at all, and that refusal is the first thing a new user hits.
     */
    val useHttps: Boolean = true,
)

/**
 * Checks a source before it can be saved.
 *
 * Every rule here exists because breaking it produces a source that fails later and
 * further away — a template missing `{y}` fetches the same tile forever, a name with a
 * slash silently changes the route it answers on. Catching them at the point of entry
 * means the user finds out while typing rather than while riding.
 */
object SourceValidator {

    /**
     * Deliberately narrower than what a URL path permits. These names are typed by hand,
     * read back off a screen and pasted into another app, so anything needing escaping —
     * spaces, slashes, non-ASCII — is a liability rather than a feature.
     */
    private val NAME = Regex("[A-Za-z0-9._-]+")

    /** Null when [layer] can be saved alongside [existing], otherwise what is wrong. */
    fun validate(layer: TileLayer, existing: List<TileLayer> = emptyList()): String? {
        if (layer.source.isBlank()) return "Source name is required"
        if (!NAME.matches(layer.source)) {
            return "Source name may use letters, digits, dot, dash and underscore only"
        }
        layer.layer?.let {
            if (it.isBlank()) return "Layer name is empty — leave it out if there is none"
            if (!NAME.matches(it)) {
                return "Layer name may use letters, digits, dot, dash and underscore only"
            }
        }

        val url = layer.urlTemplate
        if (url.isBlank()) return "Tile URL template is required"
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "Tile URL must start with http:// or https://"
        }

        val hasXyz = url.contains("{z}") && url.contains("{x}") && url.contains("{y}")
        if (!hasXyz && !url.contains("{q}") && !url.contains("{bbox}")) {
            return "Tile URL needs {z}, {x} and {y} — or {q} for a quadkey, " +
                "or {bbox} for a WMS GetMap"
        }
        if (url.contains("{s}") && layer.subdomains.isEmpty()) {
            return "Tile URL uses {s}, so at least one subdomain is required"
        }
        if (layer.subdomains.isNotEmpty() && !url.contains("{s}")) {
            return "Subdomains are set but the URL has no {s} to put them in"
        }

        if (existing.any { it.source == layer.source && it.layer == layer.layer }) {
            return "A source with that name already exists"
        }
        return null
    }
}

/**
 * Reads and writes the stored configuration.
 *
 * Lenient on the way in on purpose: a config written by an older build, or hand-edited
 * after an export, should cost the user the fields that no longer parse rather than the
 * whole file. Unparseable input yields an empty config instead of throwing, because
 * there is nothing useful the app could do with the exception at that point.
 */
object SourceCodec {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(config: SourceConfig): String = json.encodeToString(SourceConfig.serializer(), config)

    fun decode(text: String): SourceConfig =
        runCatching { json.decodeFromString(SourceConfig.serializer(), text) }
            .getOrDefault(SourceConfig())
}
