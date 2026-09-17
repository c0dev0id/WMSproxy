package de.codevoid.wmsproxy.core

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** Which protocol a capabilities document described. */
enum class ServiceKind { WMS, WMTS }

/** A layer found in a capabilities document, already reduced to something serveable. */
data class DiscoveredLayer(
    /** The upstream's own identifier, e.g. `MobiData-BW:charge_points`. */
    val name: String,
    val title: String,
    val service: ServiceKind,
    val format: String,
    /** Ready for [TileLayer.urlTemplate]; every placeholder is one `urlFor` expands. */
    val template: String,
) {
    /**
     * A path segment derived from [name]. Upstream identifiers carry colons, slashes and
     * spaces; the route this answers on cannot.
     */
    fun suggestedLayerId(): String =
        name.map { if (it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') it else '_' }
            .joinToString("")
            .trim('_')
            .ifBlank { "layer" }
}

/** A layer that was found and deliberately not offered, with the reason shown to the user. */
data class SkippedLayer(val name: String, val reason: String)

sealed interface CapabilitiesResult {
    data class Success(
        val service: ServiceKind,
        val title: String,
        val layers: List<DiscoveredLayer>,
        val skipped: List<SkippedLayer>,
    ) : CapabilitiesResult

    data class Failure(val message: String) : CapabilitiesResult
}

/**
 * Turns a WMS or WMTS `GetCapabilities` document into layers this proxy can serve.
 *
 * **Only EPSG:3857 is ever requested.** Tiles are cut on the WebMercator grid and there
 * is no reprojection anywhere in this project, so a layer an upstream cannot serve in
 * WebMercator is reported as skipped rather than fetched in something else and labelled
 * wrongly. That single rule also disposes of the WMS 1.3.0 axis-order trap: latitude-first
 * ordering applies to *geographic* CRSs, and WebMercator is projected, so the bbox goes
 * out x-first in both 1.1.1 and 1.3.0 with nothing to remember.
 *
 * Nothing here fetches. Parsing is pure so it can be driven from fixtures in CI.
 */
object CapabilitiesParser {

    /** WebMercator under every name servers have used for it over the years. */
    private val WEB_MERCATOR = setOf(
        "EPSG:3857",
        "EPSG:900913",
        "EPSG:102100",
        "EPSG:102113",
        "OSGEO:41001",
    )

    /** Just the codes, for matching the URI forms WMTS uses to name the same thing. */
    private val WEB_MERCATOR_CODES = setOf("3857", "900913", "102100", "102113", "41001")

    /**
     * Reduces a CRS identifier to its bare code.
     *
     * WMTS names a CRS three different ways for the same projection —
     * `EPSG:3857`, `urn:ogc:def:crs:EPSG::3857`, and
     * `http://www.opengis.net/def/crs/EPSG/0/3857` — so the tail after the last colon
     * *and* the last slash is the only part all three agree on. Comparing whole strings,
     * or asking whether one ends with "3857", would either miss a form or accept a
     * hypothetical EPSG:103857.
     */
    internal fun crsCode(crs: String): String =
        crs.trim().substringAfterLast(':').substringAfterLast('/')

    /** Preferred first. PNG leads because a layer with transparency needs it. */
    private val FORMAT_PREFERENCE = listOf("image/png", "image/jpeg", "image/webp", "image/gif")

    fun parse(xml: String, requestUrl: String): CapabilitiesResult {
        val root = runCatching { documentElement(xml) }
            .getOrElse { return CapabilitiesResult.Failure("Not valid XML: ${it.message}") }
            ?: return CapabilitiesResult.Failure("Empty response")

        return when (root.local()) {
            // 1.3.0 and 1.1.1 respectively.
            "WMS_Capabilities", "WMT_MS_Capabilities" -> parseWms(root, requestUrl)
            "Capabilities" -> parseWmts(root, requestUrl)
            "ServiceExceptionReport", "ExceptionReport" ->
                CapabilitiesResult.Failure(
                    "Server returned an exception: ${root.textContent.trim().take(200)}",
                )
            else -> CapabilitiesResult.Failure("Not a WMS or WMTS capabilities document")
        }
    }

    private fun documentElement(xml: String): Element? {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // External entities off. The document comes from a URL the user pasted, so it
            // is untrusted input, and an entity reference would let it read local files or
            // make the app fetch on its behalf. The DOCTYPE itself has to stay allowed:
            // WMS 1.1.1 capabilities legitimately carry one.
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        return factory.newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray()))
            .documentElement
    }

    // ------------------------------------------------------------------ WMS

    private fun parseWms(root: Element, requestUrl: String): CapabilitiesResult {
        val version = root.getAttribute("version").ifBlank { "1.1.1" }
        // 1.3.0 renamed the parameter; the value it carries is the same thing.
        val crsParam = if (version.startsWith("1.3")) "CRS" else "SRS"

        val capability = root.child("Capability")
            ?: return CapabilitiesResult.Failure("Capabilities document has no Capability section")

        val getMap = capability.child("Request")?.child("GetMap")
        val formats = getMap?.children("Format")?.map { it.text() }.orEmpty()
        val format = FORMAT_PREFERENCE.firstOrNull { pref -> formats.any { it.startsWith(pref) } }
            ?: return CapabilitiesResult.Failure(
                "Server offers no raster image format this can use" +
                    formats.take(6).joinToString(prefix = " (offers ", postfix = ")"),
            )

        val endpoint = getMap?.child("DCPType")?.child("HTTP")?.child("Get")
            ?.child("OnlineResource")?.href()
            ?.takeIf { it.isNotBlank() }
            ?: requestUrl.substringBefore('?')

        val serviceTitle = root.child("Service")?.child("Title")?.text().orEmpty()

        val layers = mutableListOf<DiscoveredLayer>()
        val skipped = mutableListOf<SkippedLayer>()

        // CRS elements are inherited down the layer tree, so a child may rely entirely on
        // what its parent declared. Collecting on the way down is the whole reason this
        // walks recursively rather than selecting every <Layer> in one sweep.
        fun walk(layer: Element, inheritedCrs: Set<String>) {
            val crs = inheritedCrs + layer.children(crsParam).map { it.text().uppercase() } +
                // A 1.3.0 document occasionally still carries SRS, and vice versa. Reading
                // both costs nothing and avoids rejecting a layer over a spelling.
                layer.children(if (crsParam == "CRS") "SRS" else "CRS").map { it.text().uppercase() }

            val name = layer.child("Name")?.text()
            if (!name.isNullOrBlank()) {
                val title = layer.child("Title")?.text()?.ifBlank { name } ?: name
                val mercator = WEB_MERCATOR.firstOrNull { it in crs }
                if (mercator == null) {
                    skipped += SkippedLayer(
                        name,
                        "does not offer WebMercator (EPSG:3857), and this proxy never reprojects",
                    )
                } else {
                    layers += DiscoveredLayer(
                        name = name,
                        title = title,
                        service = ServiceKind.WMS,
                        format = format,
                        template = wmsTemplate(endpoint, version, crsParam, mercator, name, format),
                    )
                }
            }
            layer.children("Layer").forEach { walk(it, crs) }
        }

        capability.children("Layer").forEach { walk(it, emptySet()) }

        return CapabilitiesResult.Success(ServiceKind.WMS, serviceTitle, layers, skipped)
    }

    private fun wmsTemplate(
        endpoint: String,
        version: String,
        crsParam: String,
        crs: String,
        layer: String,
        format: String,
    ): String = buildString {
        append(endpoint)
        append(endpoint.querySeparator())
        append("SERVICE=WMS")
        append("&VERSION=").append(version)
        append("&REQUEST=GetMap")
        append("&LAYERS=").append(layer.queryEncoded())
        // Empty STYLES means "the layer's default" and is required, not optional.
        append("&STYLES=")
        append('&').append(crsParam).append('=').append(crs)
        append("&BBOX={bbox}")
        append("&WIDTH=").append(TileMath.DEFAULT_TILE_SIZE)
        append("&HEIGHT=").append(TileMath.DEFAULT_TILE_SIZE)
        append("&FORMAT=").append(format.queryEncoded())
        // Overlays are useless without it, and a server that renders an opaque background
        // ignores it harmlessly.
        append("&TRANSPARENT=TRUE")
    }

    // ----------------------------------------------------------------- WMTS

    private fun parseWmts(root: Element, requestUrl: String): CapabilitiesResult {
        val contents = root.child("Contents")
            ?: return CapabilitiesResult.Failure("WMTS capabilities document has no Contents")

        val endpoint = kvpGetTileEndpoint(root) ?: requestUrl.substringBefore('?')
        val serviceTitle = root.child("ServiceIdentification")?.child("Title")?.text().orEmpty()

        // Matrix set identifier -> the TileMatrix identifiers it declares, in order.
        val matrixSets = contents.children("TileMatrixSet").associate { set ->
            val id = set.child("Identifier")?.text().orEmpty()
            val crs = set.child("SupportedCRS")?.text().orEmpty()
            id to WmtsMatrixSet(
                id = id,
                isWebMercator = crsCode(crs) in WEB_MERCATOR_CODES,
                matrixIds = set.children("TileMatrix").mapNotNull { it.child("Identifier")?.text() },
            )
        }

        val layers = mutableListOf<DiscoveredLayer>()
        val skipped = mutableListOf<SkippedLayer>()

        contents.children("Layer").forEach { layer ->
            val name = layer.child("Identifier")?.text().orEmpty()
            if (name.isBlank()) return@forEach
            val title = layer.child("Title")?.text()?.ifBlank { name } ?: name

            val formats = layer.children("Format").map { it.text() }
            val format = FORMAT_PREFERENCE.firstOrNull { pref -> formats.any { it.startsWith(pref) } }
            if (format == null) {
                skipped += SkippedLayer(
                    name,
                    if (formats.isEmpty()) {
                        "offers no tile format"
                    } else {
                        // The common case in the wild: a cache that holds vector tiles
                        // only. Drawing those would mean rendering, which this never does.
                        "offers only ${formats.first()}, which is not a raster image"
                    },
                )
                return@forEach
            }

            val linked = layer.children("TileMatrixSetLink")
                .mapNotNull { matrixSets[it.child("TileMatrixSet")?.text()] }
            val usable = linked.firstOrNull { it.isWebMercator }
            if (usable == null) {
                skipped += SkippedLayer(
                    name,
                    "has no WebMercator tile matrix set, and this proxy never resamples a grid",
                )
                return@forEach
            }

            val matrixTemplate = zoomTemplateFor(usable.matrixIds)
            if (matrixTemplate == null) {
                skipped += SkippedLayer(
                    name,
                    "tile matrix identifiers are not the zoom level " +
                        "(${usable.matrixIds.take(3).joinToString()}…), which cannot be expressed as a template",
                )
                return@forEach
            }

            val style = layer.children("Style")
                .firstOrNull { it.getAttribute("isDefault") == "true" }
                ?.child("Identifier")?.text()
                ?: layer.children("Style").firstOrNull()?.child("Identifier")?.text()
                ?: ""

            layers += DiscoveredLayer(
                name = name,
                title = title,
                service = ServiceKind.WMTS,
                format = format,
                template = wmtsTemplate(endpoint, name, style, usable.id, matrixTemplate, format),
            )
        }

        return CapabilitiesResult.Success(ServiceKind.WMTS, serviceTitle, layers, skipped)
    }

    private data class WmtsMatrixSet(
        val id: String,
        val isWebMercator: Boolean,
        val matrixIds: List<String>,
    )

    /**
     * Expresses a matrix set's identifiers as a template around `{z}`, or null if it
     * cannot be done.
     *
     * The bare-integer case (`0`, `1`, `2` …) is `{z}`. The common alternative is a fixed
     * prefix and the level appended — `EPSG:900913:0`, `EPSG:900913:1` — which is still
     * only a string, so `EPSG:900913:{z}` serves. Anything else (named scales, `L07` with
     * zero padding that varies) has no template form, and inventing one would send
     * requests for levels that do not exist.
     */
    internal fun zoomTemplateFor(matrixIds: List<String>): String? {
        if (matrixIds.isEmpty()) return null
        if (matrixIds.all { it.toIntOrNull() != null && it == it.toInt().toString() }) return "{z}"

        val prefix = matrixIds.first().dropLastWhile { it.isDigit() }
        if (prefix.isEmpty()) return null
        val consistent = matrixIds.all { id ->
            id.startsWith(prefix) && id.removePrefix(prefix).let { rest ->
                rest.toIntOrNull() != null && rest == rest.toInt().toString()
            }
        }
        return if (consistent) prefix + "{z}" else null
    }

    private fun kvpGetTileEndpoint(root: Element): String? {
        val operation = root.child("OperationsMetadata")
            ?.children("Operation")
            ?.firstOrNull { it.getAttribute("name") == "GetTile" }
            ?: return null

        val gets = operation.child("DCP")?.child("HTTP")?.children("Get").orEmpty()
        // A server commonly publishes both encodings at different paths; picking the REST
        // one and then appending KVP parameters to it would produce 404s all day.
        val kvp = gets.firstOrNull { get ->
            get.child("Constraint")?.child("AllowedValues")
                ?.children("Value")?.any { it.text().equals("KVP", ignoreCase = true) } == true
        }
        return (kvp ?: gets.firstOrNull())?.href()?.takeIf { it.isNotBlank() }
    }

    private fun wmtsTemplate(
        endpoint: String,
        layer: String,
        style: String,
        matrixSet: String,
        matrixTemplate: String,
        format: String,
    ): String = buildString {
        append(endpoint)
        append(endpoint.querySeparator())
        append("SERVICE=WMTS")
        append("&VERSION=1.0.0")
        append("&REQUEST=GetTile")
        append("&LAYER=").append(layer.queryEncoded())
        append("&STYLE=").append(style.queryEncoded())
        append("&TILEMATRIXSET=").append(matrixSet.queryEncoded())
        append("&TILEMATRIX=").append(matrixTemplate.queryEncoded())
        append("&TILEROW={y}")
        append("&TILECOL={x}")
        append("&FORMAT=").append(format.queryEncoded())
    }

    // --------------------------------------------------------------- helpers

    private fun Element.local(): String = localName ?: tagName.substringAfterLast(':')

    private fun Element.children(local: String): List<Element> {
        val out = mutableListOf<Element>()
        val kids = childNodes
        for (i in 0 until kids.length) {
            val node = kids.item(i)
            if (node.nodeType == Node.ELEMENT_NODE) {
                val element = node as Element
                if (element.local() == local) out += element
            }
        }
        return out
    }

    private fun Element.child(local: String): Element? = children(local).firstOrNull()

    private fun Element.text(): String = textContent.trim()

    /** `xlink:href`, read without depending on the prefix the document chose. */
    private fun Element.href(): String =
        getAttributeNS("http://www.w3.org/1999/xlink", "href")
            .ifBlank { getAttribute("xlink:href") }
            .ifBlank { getAttribute("href") }

    /**
     * What to put between an endpoint and the first parameter. An endpoint published in
     * capabilities often already carries query parameters the server needs — a MapServer
     * `map=` path is the classic — so appending `?` blindly would discard them.
     */
    private fun String.querySeparator(): String = when {
        endsWith("?") || endsWith("&") -> ""
        contains("?") -> "&"
        else -> "?"
    }

    /**
     * Percent-encodes what must be encoded and nothing else. `:` and `/` are left as they
     * are: both are legal in a query value, layer identifiers are full of the former, and
     * media types of the latter — and some servers match `FORMAT` literally.
     */
    private fun String.queryEncoded(): String = buildString {
        // Byte-wise over UTF-8, masked to unsigned. A signed Byte would render as
        // FFFFFFC3, and testing a high byte with isLetterOrDigit() would pass Latin-1
        // letters straight through unencoded — both break the moment a layer name
        // carries an umlaut, which German services do routinely.
        for (byte in this@queryEncoded.toByteArray()) {
            val value = byte.toInt() and 0xFF
            val c = value.toChar()
            val unreserved = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "-_.~:/,"
            if (unreserved) append(c) else append('%').append("%02X".format(value))
        }
    }
}
