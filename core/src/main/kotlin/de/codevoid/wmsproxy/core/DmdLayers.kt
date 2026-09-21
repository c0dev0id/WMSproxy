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
import java.util.Locale

/**
 * One DMD Hub custom map layer, in the shape DMD's own dialog writes it, field for field
 * and in this order — read off the account with *Log DMD layers*, not inferred. A tile
 * layer is the whole template in [url], placeholders as typed. A WMS layer is the
 * service's capabilities address in [url] with [wmsLayer] and [wmsVersion] beside it, and
 * DMD composes the GetMap itself. Neither carries a `tilePath`: DMD reads one when an
 * entry has it, but never writes one, and a form DMD only tolerates is not the form to
 * send.
 *
 * [enabled] and [maxZoom] are written to match DMD's own `pushNow`, but DMD **ignores
 * both on read**: its parser reconstructs the entry without them and the renderer
 * hardcodes the zoom range. They are here only so the wire form is identical, not because
 * they carry meaning — a layer is turned off by leaving it out of the pushed set, not by
 * flipping this flag.
 */
@Serializable
data class DmdLayer(
    val id: String,
    val name: String,
    val url: String,
    val keyName: String = "",
    val apiKey: String = "",
    val isWms: Boolean = false,
    val wmsLayer: String = "",
    val wmsVersion: String = DEFAULT_WMS_VERSION,
    val enabled: Boolean = true,
    val maxZoom: Int = 19,
)

/** DMD's own default, also written on a tile layer where no version applies. */
private const val DEFAULT_WMS_VERSION = "1.1.1"

/** The one rewrite DMD performs itself: it speaks WMS in its own way. */
private val DMD_SUBSTITUTES = setOf(Rewrite.WMS_BBOX)

/**
 * What keeps a source from being expressed as a DMD layer **without** the proxy, or null
 * when nothing does.
 *
 * DMD knowledge rather than a property of the source, which is why it lives here and
 * not beside [TileLayer.rewrites]: DMD substitutes only `{X}/{Y}/{Z}` and `{BBOX}` into
 * a fixed template, so any other rewrite has to go through the proxy, and a rewrite
 * added later blocks direct until DMD is shown to handle it.
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

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
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
     */
    fun layersFor(
        layers: List<TileLayer>,
        choiceFor: (String) -> DmdSyncChoice,
        proxyTemplate: (TileLayer) -> String,
    ): List<DmdLayer> = layers.mapNotNull { layer ->
        val choice = choiceFor(layer.path)
        if (!choice.enabled) return@mapNotNull null
        val template = if (layer.sendsDirect(choice)) layer.urlTemplate else proxyTemplate(layer)
        toDmdLayer(layer.displayName, layer.path, template)
    }

    /**
     * A DMD layer for [name] carrying [template], the id derived from [path].
     *
     * A GetMap template becomes what a user would have pasted into DMD's dialog for the
     * same layer: the service's capabilities address, the layer name and the version.
     * What the template asked for beyond that — format, CRS spelling — is DMD's to choose
     * now, which is why [directBlocker] keeps a source off Direct when the two differ.
     */
    fun toDmdLayer(name: String, path: String, template: String): DmdLayer {
        val id = layerId(path)
        if (!template.contains("{bbox}")) return DmdLayer(id = id, name = name, url = template)
        val query = template.queryParameters()
        val version = query["VERSION"] ?: DEFAULT_WMS_VERSION
        return DmdLayer(
            id = id,
            name = name,
            url = capabilitiesUrl(template, version),
            isWms = true,
            wmsLayer = query["LAYERS"].orEmpty(),
            wmsVersion = version,
        )
    }

    /**
     * The service behind a GetMap [template] as a capabilities address: the endpoint, any
     * parameter that belongs to it rather than to the request (MapServer's `map=`), then
     * `SERVICE`, `VERSION` and `REQUEST=GetCapabilities`. The form DMD's dialog stored when
     * a capabilities address was pasted into it, so the form DMD is known to digest.
     */
    internal fun capabilitiesUrl(template: String, version: String): String {
        val endpoint = template.substringBefore('?')
        val vendor = template.substringAfter('?', "").split('&').filter { pair ->
            pair.isNotEmpty() && pair.substringBefore('=').uppercase(Locale.ROOT) !in GETMAP_PARAMETERS
        }
        val own = listOf("SERVICE=WMS", "VERSION=$version", "REQUEST=GetCapabilities")
        return endpoint + "?" + (vendor + own).joinToString("&")
    }

    /** What a GetMap carries that is the request's, not the service's. */
    private val GETMAP_PARAMETERS = setOf(
        "SERVICE", "VERSION", "REQUEST", "LAYERS", "STYLES", "CRS", "SRS",
        "BBOX", "WIDTH", "HEIGHT", "FORMAT", "TRANSPARENT",
    )

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
