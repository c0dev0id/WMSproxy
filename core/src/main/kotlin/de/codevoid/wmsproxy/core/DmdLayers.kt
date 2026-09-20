package de.codevoid.wmsproxy.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.putJsonArray
import java.net.URLDecoder
import java.util.Locale

/**
 * One DMD Hub custom map layer, in the shape the endpoint round-trips.
 *
 * The field set mirrors DMD's own `CustomRasterEntry` exactly — `id, name, url, tilePath,
 * keyName, apiKey, isWms, wmsLayer, wmsVersion` — so a layer we push is indistinguishable
 * from one DMD created itself. [enabled] and [maxZoom] are written to match DMD's own
 * `pushNow`, but DMD **ignores both on read**: its parser reconstructs the entry without
 * them and the renderer hardcodes the zoom range. They are here only so the wire form is
 * identical, not because they carry meaning — a layer is turned off by leaving it out of
 * the pushed set, not by flipping this flag.
 */
@Serializable
data class DmdLayer(
    val id: String,
    val name: String,
    val url: String,
    val tilePath: String,
    val keyName: String = "",
    val apiKey: String = "",
    val isWms: Boolean = false,
    val wmsLayer: String = "",
    val wmsVersion: String = "1.1.1",
    val enabled: Boolean = true,
    val maxZoom: Int = 19,
)

/** A tile template split into DMD's `url` origin and `tilePath` remainder. */
data class DmdUrl(val url: String, val tilePath: String, val isWms: Boolean)

/** What a source needs that DMD cannot do on its own, so the proxy has to. */
enum class DirectBlocker { FLIPPED_ROWS, REFERER, SUBDOMAINS, QUADKEY, PADDED_ZOOM, NO_TILE_INDEX }

/**
 * Turns WMSproxy sources into DMD custom layers and folds them into the account's set.
 *
 * Everything here is pure string work so it is unit-tested without a device: the HTTP
 * GET/POST lives in `:app` (`DmdHub`), which hands the server's JSON to [mergeForPush]
 * and sends back what it returns.
 *
 * The POST is a full replacement of the account's custom layers, so a merge is the only
 * safe way to add ours without dropping the user's other layers: keep every foreign entry
 * verbatim (as raw JSON, so a field DMD adds later survives the round-trip) and substitute
 * only the ones that are ours.
 */
object DmdSync {

    private const val ID_PREFIX = "cl_wmsproxy_"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * A stable id per source, so re-syncing overwrites its own layer instead of stacking
     * duplicates and DMD's device-local enabled state stays attached across syncs. Source
     * and layer names are already restricted to `[A-Za-z0-9._-]`, so only the path
     * separator needs escaping.
     */
    fun layerId(path: String): String = ID_PREFIX + path.replace('/', '_')

    /**
     * What keeps a source from being expressed as a DMD layer **without** the proxy, or
     * null when nothing does.
     *
     * DMD substitutes only `{X}/{Y}/{Z}` and `{BBOX}` into a fixed template, so anything
     * needing a rewrite — a flipped TMS row, a quadkey, subdomain rotation, a padded zoom,
     * or a Referer header DMD cannot send — has to go through the proxy.
     *
     * A WMS template passes. DMD draws WebMercator and nothing else, so the bbox it
     * substitutes is EPSG:3857, whose axis order is the same under both WMS versions; the
     * version, the spelling of the CRS parameter and the layer name are fixed in the
     * template and travel with it. The trap the proxy absorbs — latitude-first geographic
     * coordinates under 1.3.0 — cannot arise in a request DMD makes.
     */
    fun TileLayer.directBlocker(): DirectBlocker? {
        val t = urlTemplate
        return when {
            flipY -> DirectBlocker.FLIPPED_ROWS
            referer != null -> DirectBlocker.REFERER
            subdomains.isNotEmpty() || t.contains("{s}") -> DirectBlocker.SUBDOMAINS
            t.contains("{q}") -> DirectBlocker.QUADKEY
            TileLayer.PADDED_ZOOM.containsMatchIn(t) -> DirectBlocker.PADDED_ZOOM
            t.contains("{bbox}") -> null
            t.contains("{z}") && t.contains("{x}") && t.contains("{y}") -> null
            else -> DirectBlocker.NO_TILE_INDEX
        }
    }

    fun TileLayer.directCompatible(): Boolean = directBlocker() == null

    /** A DMD layer for [name] carrying [template], the id derived from [path]. */
    fun toDmdLayer(name: String, path: String, template: String): DmdLayer {
        val split = splitTemplate(template)
        val query = if (split.isWms) queryParameters(split.tilePath) else emptyMap()
        return DmdLayer(
            id = layerId(path),
            name = name,
            url = split.url,
            tilePath = split.tilePath,
            isWms = split.isWms,
            // Filled from the template for a WMS layer, so the entry is complete whichever
            // of its fields DMD reads the layer name and version from.
            wmsLayer = query["LAYERS"].orEmpty(),
            wmsVersion = query["VERSION"] ?: "1.1.1",
        )
    }

    /** The query's parameters by upper-cased name, percent-decoded. */
    internal fun queryParameters(tilePath: String): Map<String, String> =
        tilePath.substringAfter('?', "")
            .split('&')
            .filter { it.isNotEmpty() }
            .associate { pair ->
                val key = pair.substringBefore('=').uppercase(Locale.ROOT)
                val value = pair.substringAfter('=', "")
                key to runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
            }

    /**
     * Splits a tile template into DMD's `url`/`tilePath` pair, a faithful port of DMD's own
     * `OnlineLayerManager.parseCustomUrl`, so our stored form is identical to a layer DMD
     * created. It uppercases the placeholders DMD recognises, maps the WMTS KVP and alias
     * spellings, and splits at the last `/` before `{Z}` — or at `?` when a `{BBOX}` marks
     * a WMS GetMap.
     */
    fun splitTemplate(template: String): DmdUrl {
        val s = template
            .replace("{z}", "{Z}").replace("{x}", "{X}").replace("{y}", "{Y}")
            .replace("{zoom}", "{Z}").replace("{col}", "{X}").replace("{row}", "{Y}")
            .replace("{TileMatrix}", "{Z}").replace("{TileCol}", "{X}").replace("{TileRow}", "{Y}")
            .replace("{tilematrix}", "{Z}").replace("{tilecol}", "{X}").replace("{tilerow}", "{Y}")
            .replace("{bbox}", "{BBOX}").replace("{r}", "").replace("@2x", "")

        if (s.contains("{BBOX}")) {
            val q = s.indexOf('?')
            return if (q > 0) DmdUrl(s.substring(0, q), s.substring(q), true) else DmdUrl(s, "", true)
        }

        val zoom = s.indexOf("{Z}")
        if (zoom <= 0) return DmdUrl(s, "/{Z}/{X}/{Y}.png", false)

        var split = zoom
        for (i in zoom - 1 downTo 0) {
            val c = s[i]
            if (c == '/' || c == '?') {
                split = i
                break
            }
        }
        return DmdUrl(s.substring(0, split), s.substring(split), false)
    }

    /**
     * The POST body that adds [ours] to the account without disturbing anything else.
     *
     * A foreign layer is one whose name and id are both not ours; it is carried through
     * verbatim. Ours are matched by either handle — name, because that is what the user
     * reads and asked to overwrite, or our own id prefix, which also clears a layer left
     * behind when a source was renamed. A malformed server body is treated as an empty
     * set rather than a reason to refuse the push.
     */
    fun mergeForPush(serverBody: String, ours: List<DmdLayer>): String {
        val existing = runCatching {
            json.parseToJsonElement(serverBody).jsonObject["layers"]?.jsonArray
        }.getOrNull() ?: JsonArray(emptyList())

        val ourNames = ours.mapTo(mutableSetOf()) { it.name }
        val ourIds = ours.mapTo(mutableSetOf()) { it.id }

        val foreign = existing.filter { element ->
            val obj = element as? JsonObject ?: return@filter false
            val name = (obj["name"] as? JsonPrimitive)?.contentOrNull
            val id = (obj["id"] as? JsonPrimitive)?.contentOrNull
            name !in ourNames && id !in ourIds
        }

        val merged = buildJsonObject {
            putJsonArray("layers") {
                foreign.forEach { add(it) }
                ours.forEach { add(json.encodeToJsonElement(DmdLayer.serializer(), it)) }
            }
        }
        return json.encodeToString(JsonObject.serializer(), merged)
    }
}
