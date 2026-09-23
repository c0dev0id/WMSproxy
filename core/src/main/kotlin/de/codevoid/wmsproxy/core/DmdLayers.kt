package de.codevoid.wmsproxy.core

import de.codevoid.wmsproxy.core.http.queryParameters
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.putJsonArray

/**
 * One DMD Hub custom map layer, in the two forms **both** readers of the account render:
 * the DMD app on the phone and the route planner on the web. Read off the account with
 * *Log DMD layers* and tried in both, not inferred.
 *
 * A tile layer is the whole template in [url], placeholders as typed, with no `tilePath`
 * key at all — the form the phone's dialog writes for a pasted address, and both apps
 * fill `{z}/{x}/{y}` in it. A WMS layer is the endpoint in [url] and the whole GetMap
 * query, `{BBOX}` included, in [tilePath], with [wmsLayer] and [wmsVersion] beside it —
 * the form the planner writes for its own WMS layers, which the phone has rendered since
 * the sync began. `isWms` means "replace the bbox": the phone concatenates `url` and
 * `tilePath` and fills `{BBOX}` only when it is set, the planner composes a GetMap of its
 * own from `url`, [wmsLayer] and [wmsVersion] when it is set, and neither fills a bbox
 * when it is not. So every template with a bbox carries it, an ArcGIS export as much as
 * a WMS GetMap; the export renders on the phone only, since what the planner composes
 * is a WMS request.
 *
 * [enabled] and [maxZoom] are written to match DMD's own `pushNow`. The phone **ignores
 * both on read** — its parser reconstructs the entry without them and the renderer
 * hardcodes the zoom range — so a layer is turned off by leaving it out of the pushed
 * set, not by flipping the flag. The planner reads both: `enabled` as its own on/off,
 * and `maxZoom` as the deepest zoom that has tiles. [maxZoom] nonetheless stays at DMD's
 * default: the import's measured maximum is aimed at the centre of a service's extent,
 * which for a nationwide service is open water, and a number measured there would have
 * the planner overzoom a cache that goes far deeper.
 */
@Serializable
data class DmdLayer(
    val id: String,
    val name: String,
    val url: String,
    /** The GetMap query of a WMS layer; absent, not empty, on a tile layer. */
    val tilePath: String? = null,
    val keyName: String = "",
    val apiKey: String = "",
    val isWms: Boolean = false,
    val wmsLayer: String = "",
    val wmsVersion: String = DEFAULT_WMS_VERSION,
    val enabled: Boolean = true,
    val maxZoom: Int = DEFAULT_MAX_ZOOM,
)

/** DMD's own default, also written on a tile layer where no version applies. */
private const val DEFAULT_WMS_VERSION = "1.1.1"

/** DMD's own default; see [DmdLayer] for why the measured maximum is not sent. */
private const val DEFAULT_MAX_ZOOM = 19

/** The one rewrite DMD performs itself: it speaks WMS in its own way. */
private val DMD_SUBSTITUTES = setOf(Rewrite.WMS_BBOX)

/**
 * What keeps a source from being expressed as a DMD layer **without** the proxy, or null
 * when nothing does.
 *
 * DMD knowledge rather than a property of the source, which is why it lives here and
 * not beside [TileLayer.rewrites]: DMD substitutes only `{X}/{Y}/{Z}` and `{BBOX}` into
 * a fixed template, so any other rewrite has to go through the proxy, and a rewrite
 * added later blocks direct until DMD is shown to handle it. The connection is one of
 * them: DMD refuses cleartext under its network security policy — the reason the proxy
 * serves HTTPS at all — and the planner is a page served over HTTPS, which a browser
 * will not let fetch plain-HTTP tiles. A `http://` source therefore goes through the
 * proxy, whose certificate is what makes it reachable.
 *
 * A WMS template passes. DMD draws WebMercator and nothing else, so the bbox it
 * substitutes is EPSG:3857, whose axis order is the same under both WMS versions; the
 * version, the spelling of the CRS parameter and the layer name are fixed in the template
 * and travel with it. The trap the proxy absorbs — latitude-first geographic coordinates
 * under 1.3.0 — cannot arise in a request DMD makes.
 */
fun TileLayer.directBlocker(): Rewrite? = rewrites().firstOrNull { it !in DMD_SUBSTITUTES }

/**
 * How one source should be pushed to DMD.
 *
 * [enabled] is inclusion, not a DMD flag: DMD ignores the `enabled` field it is sent and
 * tracks on/off in a device-local pref of its own, so the only way to turn a layer off
 * over the wire is to leave it out of the pushed set. [direct] asks for the upstream URL
 * instead of the proxy's; whether it is granted is [sendsDirect]'s decision.
 */
@Serializable
data class DmdSyncChoice(val enabled: Boolean = true, val direct: Boolean = false)

/** The choice made for [path], or the default where none ever was. */
fun Map<String, DmdSyncChoice>.choiceFor(path: String): DmdSyncChoice = this[path] ?: DmdSyncChoice()

/**
 * Whether [choice] sends this source's own address rather than the proxy's. One decision,
 * shared by the switch that shows it and the sync that acts on it, so the two cannot
 * disagree.
 */
fun TileLayer.sendsDirect(choice: DmdSyncChoice): Boolean = choice.direct && directBlocker() == null

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
    private const val PLANNER_SUFFIX = "_planner"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        // A tile layer has no tilePath, and DMD writes no key for one; an explicit null
        // would be a third form neither side has seen.
        explicitNulls = false
    }

    private val choicesSerializer = MapSerializer(String.serializer(), DmdSyncChoice.serializer())

    /**
     * A stable id per source, so re-syncing overwrites its own layer instead of stacking
     * duplicates and DMD's device-local enabled state stays attached across syncs. Source
     * and layer names are already restricted to `[A-Za-z0-9._-]`, so only the path
     * separator needs escaping.
     */
    fun layerId(path: String): String = ID_PREFIX + path.replace('/', '_')

    /**
     * The DMD layers for the sources switched on, each carrying its own address where
     * [sendsDirect] allows and [proxyTemplate] otherwise, which always works. The name is
     * the source's [TileLayer.displayName], so the DMD list never carries an empty one.
     *
     * A source whose address is an ArcGIS export — a bbox template that is not a WMS
     * GetMap — becomes two entries, because no one entry renders it in both readers: the
     * phone fills a bbox only in a layer marked `isWms`, and the planner, for that mark,
     * composes a WMS request the export cannot answer, while filling a bbox in a plain
     * layer only through MapLibre's own `{bbox-epsg-3857}`, which the phone does not know.
     * So the phone gets its form under the name with *(DMD App)* appended and the planner
     * its form under *(Hub Planner)*; each reader keeps its own on/off, and the rider
     * switches the foreign one off in each place.
     */
    fun layersFor(
        layers: List<TileLayer>,
        choiceFor: (String) -> DmdSyncChoice,
        proxyTemplate: (TileLayer) -> String,
    ): List<DmdLayer> = layers.flatMap { layer ->
        val choice = choiceFor(layer.path)
        if (!choice.enabled) return@flatMap emptyList()
        val template = if (layer.sendsDirect(choice)) layer.urlTemplate else proxyTemplate(layer)
        if (!isExport(template)) return@flatMap listOf(toDmdLayer(layer.displayName, layer.path, template))
        listOf(
            toDmdLayer("${layer.displayName} (DMD App)", layer.path, template),
            DmdLayer(
                id = layerId(layer.path) + PLANNER_SUFFIX,
                name = "${layer.displayName} (Hub Planner)",
                url = template.replace("{bbox}", "{bbox-epsg-3857}"),
            ),
        )
    }

    /** A bbox template that is not a WMS GetMap: an ArcGIS export, in practice. */
    private fun isExport(template: String): Boolean =
        template.contains("{bbox}") && !template.queryParameters()["REQUEST"].equals("GetMap", ignoreCase = true)

    /**
     * A DMD layer for [name] carrying [template], the id derived from [path].
     *
     * A GetMap template is split at `?`: the endpoint stays in `url`, the query with its
     * `{bbox}` in DMD's spelling goes to `tilePath`, and the layer name and version are
     * read back out of it so the entry is whole — the planner's own form, see [DmdLayer].
     * The request the import measured travels as it is — the server's own format, CRS
     * spelling and vendor parameters — which is what makes a Direct WMS source safe to
     * hand over. Anything else is one address, as pasted.
     */
    fun toDmdLayer(name: String, path: String, template: String): DmdLayer {
        val id = layerId(path)
        val q = template.indexOf('?')
        val query = template.queryParameters()
        // isWms means "replace the bbox": the phone concatenates url and tilePath and
        // fills {BBOX} only for a layer so marked — a plain layer gets Z/X/Y and nothing
        // else, and an export template sent plain went out with the placeholder in it.
        // So every template with a bbox carries the mark, ArcGIS export included. The
        // planner composes a WMS GetMap from url, wmsLayer and wmsVersion for the same
        // mark, which renders a WMS and not an export; that reader is skipped for those.
        if (q < 0 || !template.contains("{bbox}")) return DmdLayer(id = id, name = name, url = template)
        val getMap = query["REQUEST"].equals("GetMap", ignoreCase = true)
        return DmdLayer(
            id = id,
            name = name,
            url = template.substring(0, q),
            tilePath = template.substring(q).replace("{bbox}", "{BBOX}"),
            isWms = true,
            // The planner's fields, filled only from a real GetMap: an ArcGIS export has a
            // `layers=show:<id>` of its own, which is not a WMS layer name.
            wmsLayer = if (getMap) query["LAYERS"].orEmpty() else "",
            wmsVersion = if (getMap) query["VERSION"] ?: DEFAULT_WMS_VERSION else DEFAULT_WMS_VERSION,
        )
    }

    /**
     * The POST body that adds [ours] to the account without disturbing anything else.
     *
     * Ours to replace are matched by either handle — name, because that is what the user
     * reads and asked to overwrite, or our own id prefix, which also clears a layer left
     * behind when a source was renamed. Everything else, an entry that is not even an
     * object included, is carried through verbatim. A malformed server body is treated
     * as an empty set rather than a reason to refuse the push.
     */
    fun mergeForPush(serverBody: String, ours: List<DmdLayer>): String {
        val ourNames = ours.mapTo(mutableSetOf()) { it.name }
        val ourIds = ours.mapTo(mutableSetOf()) { it.id }
        val foreign = layersIn(serverBody).filter {
            it.string("name") !in ourNames && it.string("id") !in ourIds
        }

        val merged = buildJsonObject {
            putJsonArray("layers") {
                foreign.forEach { add(it) }
                ours.forEach { add(json.encodeToJsonElement(DmdLayer.serializer(), it)) }
            }
        }
        return json.encodeToString(JsonObject.serializer(), merged)
    }

    /**
     * Every layer in the account, each exactly as the server sent it, ours included. This
     * is the dump the Settings log offers: a layer DMD wrote from a pasted address sits
     * beside the one this app pushed for the same source, so a difference in form can be
     * read off rather than guessed at.
     */
    fun accountEntries(serverBody: String): List<String> = layersIn(serverBody).map { it.toString() }

    /**
     * The account's layers this app did not write, each exactly as the server sent it.
     *
     * "Did not write" is narrower than "not ours to replace" in [mergeForPush]: a foreign
     * layer that happens to share one of our names is listed here, because seeing it
     * before a push overwrites it is the point. Verbatim rather than parsed, because a
     * layer DMD wrote itself is the reference for the form this app reproduces, and a
     * field this build does not know about is the interesting part.
     */
    fun foreignEntries(serverBody: String): List<String> =
        layersIn(serverBody)
            .filter { it.string("id")?.startsWith(ID_PREFIX) != true }
            .map { it.toString() }

    fun encodeChoices(choices: Map<String, DmdSyncChoice>): String =
        json.encodeToString(choicesSerializer, choices)

    /** An unreadable store is an empty one: every source then syncs through the proxy. */
    fun decodeChoices(text: String): Map<String, DmdSyncChoice> =
        runCatching { json.decodeFromString(choicesSerializer, text) }.getOrDefault(emptyMap())

    /** A malformed body is an empty set, never a reason to refuse. */
    private fun layersIn(serverBody: String): JsonArray =
        runCatching { json.parseToJsonElement(serverBody).jsonObject["layers"]?.jsonArray }
            .getOrNull() ?: JsonArray(emptyList())

    private fun JsonElement.string(field: String): String? =
        ((this as? JsonObject)?.get(field) as? JsonPrimitive)?.contentOrNull
}
