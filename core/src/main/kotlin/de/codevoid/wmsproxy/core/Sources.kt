package de.codevoid.wmsproxy.core

import de.codevoid.wmsproxy.core.http.queryParameters
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
    /**
     * The zoom levels this source was measured to serve usefully, null when it has not
     * been measured and everything is passed through.
     *
     * Established once, when the source is added, because servers almost never declare
     * it. Holding the range here is what lets a request outside it be refused without
     * touching the network — a tile the source cannot render in time is better answered
     * immediately than after a timeout that occupies a connection.
     */
    val minZoom: Int? = null,
    val maxZoom: Int? = null,
) {
    /** The path this source answers on, without the tile coordinates. */
    val path: String get() = if (layer == null) source else "$source/$layer"

    /** What the user sees: the title, or the path when no title was given. */
    val displayName: String get() = title.ifBlank { path }

    /** False only when a measured range exists and [zoom] falls outside it. */
    fun serves(zoom: Int): Boolean =
        (minZoom == null || zoom >= minZoom) && (maxZoom == null || zoom <= maxZoom)

    /**
     * This source with its zoom placeholder unpadded, or null when it was never padded.
     *
     * A server that names its levels `00`, `01` … may answer `0`, `1` … just the same;
     * TopPlusOpen does. Whether it does is measured when the source is added, because the
     * plain form is the only one DMD can substitute by itself.
     */
    fun withPlainZoom(): TileLayer? =
        if (PADDED_ZOOM.containsMatchIn(urlTemplate)) {
            copy(urlTemplate = PADDED_ZOOM.replace(urlTemplate, "{z}"))
        } else {
            null
        }

    companion object {
        /**
         * `{z:02}` and friends — the zoom padded to the width given.
         *
         * Both braces are escaped. A trailing unescaped `}` is a literal on the JVM, so
         * it compiles in a unit test and says nothing about the device: this runs in a
         * companion initialiser, which makes any rejection an ExceptionInInitializerError
         * on the first TileLayer built, before the app has a screen to report it on.
         */
        internal val PADDED_ZOOM = Regex("""\{z:(\d{1,2})\}""")

        /** Either spelling of the zoom placeholder, for templates that must carry one. */
        internal fun hasZoomPlaceholder(template: String): Boolean =
            template.contains("{z}") || PADDED_ZOOM.containsMatchIn(template)
    }

    /** How the range reads on screen, or null when nothing was measured. */
    fun zoomRangeLabel(): String? = when {
        minZoom != null && maxZoom != null -> "z$minZoom–z$maxZoom"
        minZoom != null -> "z$minZoom and deeper"
        maxZoom != null -> "up to z$maxZoom"
        else -> null
    }

    /**
     * Expands the template for one tile.
     *
     * `{bbox}` is what lets a WMS server be an upstream without a second code path. A
     * tile has an exact WebMercator extent, so a `GetMap` for that extent at 256×256 is
     * the same picture — the request is still only a string being filled in, and no
     * pixel is touched. It is arithmetic from the tile index, not a reprojection.
     *
     * `{z:02}` is the zoom padded to a fixed width. Several national services index
     * their levels `00`, `01`, `02` rather than `0`, `1`, `2`, and asking such a server
     * for level `0` gets nothing — the identifier it published is `00`.
     */
    fun urlFor(tile: TileRef): String {
        val y = if (flipY) TileMath.flipY(tile.zoom, tile.y) else tile.y
        var url = PADDED_ZOOM.replace(urlTemplate) { match ->
            tile.zoom.toString().padStart(match.groupValues[1].toInt(), '0')
        }
        url = url
            .replace("{z}", tile.zoom.toString())
            .replace("{x}", tile.x.toString())
            .replace("{y}", y.toString())
        // Computed only when asked for, like the bbox: a quadkey costs a loop per tile and
        // is meaningless for a template that has no place to put it.
        if (url.contains("{q}")) {
            url = url.replace("{q}", TileMath.quadKey(tile.zoom, tile.x, tile.y))
        }
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

/**
 * What the proxy does to a request on the way to the upstream that a plain XYZ client
 * would not: in [TileLayer.urlFor] for all but the last, in the relay's request headers
 * for [REFERER]. Listed in the order they are applied.
 *
 * [WMS_FORMAT] and [WMS_CRS] are the two parts of a GetMap the import measured against
 * the server that a client composing its own GetMap would fix for itself: the image
 * format, where the server offers no plain `image/png`, and the spelling of WebMercator,
 * where it knows it only as `EPSG:900913` or another alias. The import prefers `image/png`
 * and `EPSG:3857` whenever a server offers them, so either appearing in a template means
 * the server did not.
 */
enum class Rewrite { PADDED_ZOOM, FLIPPED_ROWS, QUADKEY, WMS_BBOX, WMS_FORMAT, WMS_CRS, SUBDOMAINS, REFERER }

/** The rewrites the proxy performs for this source. Empty for a source it only relays. */
fun TileLayer.rewrites(): List<Rewrite> = buildList {
    if (TileLayer.PADDED_ZOOM.containsMatchIn(urlTemplate)) add(Rewrite.PADDED_ZOOM)
    if (flipY) add(Rewrite.FLIPPED_ROWS)
    if (urlTemplate.contains("{q}")) add(Rewrite.QUADKEY)
    if (urlTemplate.contains("{bbox}")) {
        add(Rewrite.WMS_BBOX)
        // Only a value that differs counts: a hand-typed template naming neither leaves
        // them to the server's defaults, which is not a request of its own.
        val query = urlTemplate.queryParameters()
        if (query["FORMAT"]?.let { it != "image/png" } == true) add(Rewrite.WMS_FORMAT)
        if ((query["CRS"] ?: query["SRS"])?.let { it != "EPSG:3857" } == true) add(Rewrite.WMS_CRS)
    }
    if (urlTemplate.contains("{s}")) add(Rewrite.SUBDOMAINS)
    if (referer != null) add(Rewrite.REFERER)
}

/** The stored set of sources. A wrapper, so the file can gain fields without a rewrite. */
@Serializable
data class SourceConfig(
    val layers: List<TileLayer> = emptyList(),
    /**
     * Whether the proxy should come back after a reboot.
     *
     * Not a preference the user sets: it records that they started the proxy and have not
     * stopped it since. A phone that reboots on a ride should come back serving tiles
     * without anyone taking a glove off, and one the user deliberately stopped should
     * stay stopped.
     */
    val startOnBoot: Boolean = false
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

    /** True for a character [NAME] admits; [asPathSegment] is built from it. */
    private fun Char.isNameChar(): Boolean =
        this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this == '.' || this == '-' || this == '_'

    /**
     * Reduces arbitrary text to something [NAME] accepts, so a suggestion made from a
     * server's title or identifier is valid by construction. Titles carry colons,
     * slashes, spaces and accents; a route cannot.
     */
    fun asPathSegment(text: String, fallback: String): String =
        text.map { if (it.isNameChar()) it else '_' }
            .joinToString("")
            .trim('_')
            .replace(Regex("_+"), "_")
            .ifBlank { fallback }

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

        val hasXyz = TileLayer.hasZoomPlaceholder(url) && url.contains("{x}") && url.contains("{y}")
        if (!hasXyz && !url.contains("{q}") && !url.contains("{bbox}")) {
            return "Tile URL needs {z} (or {z:02}), {x} and {y} — or {q} for a " +
                "quadkey, or {bbox} for a WMS GetMap"
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
