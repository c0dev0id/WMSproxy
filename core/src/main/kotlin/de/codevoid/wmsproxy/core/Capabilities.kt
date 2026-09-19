package de.codevoid.wmsproxy.core

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/** Which protocol a capabilities document described. */
enum class ServiceKind { WMS, WMTS }

/** A position in degrees, used to aim a probe at where a layer actually has data. */
data class LonLat(val longitude: Double, val latitude: Double)

/** A layer found in a capabilities document, already reduced to something serveable. */
data class DiscoveredLayer(
    /** The upstream's own identifier, e.g. `MobiData-BW:charge_points`. */
    val name: String,
    val title: String,
    val service: ServiceKind,
    val format: String,
    /** Ready for [TileLayer.urlTemplate]; every placeholder is one `urlFor` expands. */
    val template: String,
    /**
     * The middle of the layer's declared extent, when it declares one.
     *
     * Only useful for aiming a measurement. A tile over empty ocean renders instantly
     * whatever the layer costs, so timing one would say nothing about the layer.
     */
    val centre: LonLat? = null,
) {
    /**
     * A path segment derived from [name]. Upstream identifiers carry colons, slashes and
     * spaces; the route this answers on cannot.
     */
    fun suggestedLayerId(): String = asPathSegment(name, fallback = "layer")
}

/**
 * Reduces arbitrary text to something usable as a URL path segment.
 *
 * Titles and identifiers carry colons, slashes, spaces and accents; a route cannot.
 */
internal fun asPathSegment(text: String, fallback: String): String =
    text.map { if (it.isLetterOrDigit() && it.code < 128 || it == '.' || it == '-' || it == '_') it else '_' }
        .joinToString("")
        .trim('_')
        .replace(Regex("_+"), "_")
        .ifBlank { fallback }

/** A layer that was found and deliberately not offered, with the reason shown to the user. */
data class SkippedLayer(val name: String, val reason: String)

sealed interface CapabilitiesResult {
    data class Success(
        val service: ServiceKind,
        val title: String,
        val layers: List<DiscoveredLayer>,
        val skipped: List<SkippedLayer>,
    ) : CapabilitiesResult {
        /**
         * A source name proposed from the service's own title.
         *
         * From the document, not from the URL that fetched it: the server states what it
         * calls itself, and a hostname is at best a guess at the same thing.
         */
        fun suggestedSourceId(): String = asPathSegment(title, fallback = "imported")
    }

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

    /**
     * Picks a format from what a server advertises, or null when none is an image.
     *
     * Returns the advertised string rather than the preference that matched it. A server
     * offering only `image/png; mode=8bit` means that exact value, and asking for bare
     * `image/png` is asking for something it did not offer.
     *
     * The fallback past the preference list is what makes ArcGIS servers usable: they
     * advertise `image/jpgpng`, meaning "PNG or JPEG depending on the tile", which is a
     * raster image under a name no fixed list would predict. The same test the relay
     * applies decides it, so import and delivery cannot disagree about what an image is.
     */
    private fun chooseFormat(advertised: List<String>): String? {
        for (preferred in FORMAT_PREFERENCE) {
            advertised.firstOrNull { it.startsWith(preferred) }?.let { return it }
        }
        return advertised.firstOrNull { TileMediaType.isRasterImage(it) }
    }

    fun parse(xml: String): CapabilitiesResult =
        parse(ByteArrayInputStream(xml.toByteArray()))

    /**
     * Reads straight from the response.
     *
     * The stream form is the one the app uses. Taking a String meant holding the document
     * three times over — the bytes, a UTF-16 copy roughly twice their size, and the bytes
     * again on the way into the parser — which is 17 MB of churn for a 5.8 MB document
     * before the tree is even built. The String overload stays for fixtures in tests.
     */
    fun parse(stream: InputStream): CapabilitiesResult {
        val root = try {
            documentElement(stream)
        } catch (e: org.xml.sax.SAXException) {
            return CapabilitiesResult.Failure("Not valid XML: ${e.message}")
        } catch (e: Exception) {
            // Anything that is not the document being malformed. Saying "not valid XML"
            // here sends the reader looking at a server that did nothing wrong.
            return CapabilitiesResult.Failure(
                "Could not read the response (${e.javaClass.simpleName}: ${e.message})",
            )
        } ?: return CapabilitiesResult.Failure("Empty response")

        return when (root.local()) {
            // 1.3.0 and 1.1.1 respectively.
            "WMS_Capabilities", "WMT_MS_Capabilities" -> parseWms(root)
            "Capabilities" -> parseWmts(root)
            "ServiceExceptionReport", "ExceptionReport" ->
                CapabilitiesResult.Failure(
                    "Server returned an exception: ${root.textContent.trim().take(200)}",
                )
            else -> CapabilitiesResult.Failure("Not a WMS or WMTS capabilities document")
        }
    }

    /**
     * Hardening steps applied one at a time, each tolerated when the implementation does
     * not offer it.
     *
     * Every one of these is optional across JAXP implementations, and Android differs
     * from the desktop JVM on several. `setXIncludeAware` is the sharp edge: Android's
     * abstract `DocumentBuilderFactory` throws `UnsupportedOperationException` from it
     * unconditionally, while the JVM's Xerces accepts it — so a single unguarded call
     * failed every import on the device while passing every test in CI. Anything touching
     * this factory is configuration, not parsing, and must not be able to refuse a
     * document that is perfectly good.
     *
     * The intent stays: this XML comes from a URL the user pasted, so an entity reference
     * must not be able to read local files or make the app fetch on someone's behalf. The
     * DOCTYPE itself stays allowed, because WMS 1.1.1 capabilities legitimately carry one.
     */
    private fun harden(factory: DocumentBuilderFactory) {
        for (feature in listOf(
            "http://xml.org/sax/features/external-general-entities",
            "http://xml.org/sax/features/external-parameter-entities",
            "http://apache.org/xml/features/nonvalidating/load-external-dtd",
        )) {
            runCatching { factory.setFeature(feature, false) }
        }
        runCatching { factory.isXIncludeAware = false }
        runCatching { factory.isExpandEntityReferences = false }
    }

    private fun documentElement(stream: InputStream): Element? {
        val factory = DocumentBuilderFactory.newInstance()
        // Namespace awareness is the one setting that is not optional: local names are how
        // every element here is matched, and without it `localName` comes back null.
        factory.isNamespaceAware = true
        harden(factory)

        return factory.newDocumentBuilder()
            .parse(stream)
            .documentElement
    }

    // ------------------------------------------------------------------ WMS

    private fun parseWms(root: Element): CapabilitiesResult {
        val version = root.getAttribute("version").ifBlank { "1.1.1" }
        // 1.3.0 renamed the parameter; the value it carries is the same thing.
        val crsParam = if (version.startsWith("1.3")) "CRS" else "SRS"

        val capability = root.child("Capability")
            ?: return CapabilitiesResult.Failure("Capabilities document has no Capability section")

        val getMap = capability.child("Request")?.child("GetMap")
        val formats = getMap?.children("Format")?.map { it.text() }.orEmpty()
        val format = chooseFormat(formats)
            ?: return CapabilitiesResult.Failure(
                "Server offers no raster image format this can use" +
                    formats.take(6).joinToString(prefix = " (offers ", postfix = ")"),
            )

        // Taken from the document, never derived from the URL that fetched it. The two
        // are often different — a service published behind a proxy or an alias names its
        // real GetMap endpoint here — and an OnlineResource is mandatory in both WMS
        // schemas, so its absence is a broken document to report rather than a gap to
        // paper over with a guess that would fail later, somewhere less obvious.
        val endpoint = getMap?.child("DCPType")?.child("HTTP")?.child("Get")
            ?.child("OnlineResource")?.href()
            ?.takeIf { it.isNotBlank() }
            ?: return CapabilitiesResult.Failure(
                "The document does not publish a GetMap endpoint",
            )

        val serviceTitle = root.child("Service")?.child("Title")?.text().orEmpty()

        val layers = mutableListOf<DiscoveredLayer>()
        val skipped = mutableListOf<SkippedLayer>()

        // CRS is an additive, inherited property (WMS 1.3.0 Annex E, table E.1): a layer
        // is available in its own CRSs *and* every one its ancestors declared. The union
        // below is therefore the specified behaviour, not an oversight — GeoServer and
        // MapServer both declare the full list once on the root layer and nothing on the
        // children, so intersecting, or letting a child's own list win, would reject
        // almost every real layer. A layer is only unserveable when no ancestor offered
        // WebMercator either. Collecting on the way down is why this walks recursively
        // rather than selecting every <Layer> in one sweep.
        fun walk(layer: Element, inheritedCrs: Set<String>, inheritedCentre: LonLat?) {
            val crs = inheritedCrs + layer.children(crsParam).map { it.text().uppercase() } +
                // A 1.3.0 document occasionally still carries SRS, and vice versa. Reading
                // both costs nothing and avoids rejecting a layer over a spelling.
                layer.children(if (crsParam == "CRS") "SRS" else "CRS").map { it.text().uppercase() }

            // Geographic extent is inherited like CRS is, so a leaf commonly declares
            // none and relies on the group above it.
            val centre = layer.geographicCentre() ?: inheritedCentre

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
                        centre = centre,
                    )
                }
            }
            layer.children("Layer").forEach { walk(it, crs, centre) }
        }

        capability.children("Layer").forEach { walk(it, emptySet(), null) }

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

    private fun parseWmts(root: Element): CapabilitiesResult {
        val contents = root.child("Contents")
            ?: return CapabilitiesResult.Failure("WMTS capabilities document has no Contents")

        val endpoint = kvpGetTileEndpoint(root)
            ?: return CapabilitiesResult.Failure(
                "The document does not publish a KVP GetTile endpoint",
            )
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
            val format = chooseFormat(formats)
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
                centre = layer.wgs84Centre(),
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

        placeholderFor(matrixIds, prefix = "")?.let { return it }

        val prefix = matrixIds.first().dropLastWhile { it.isDigit() }
        if (prefix.isEmpty()) return null
        return placeholderFor(matrixIds, prefix)?.let { prefix + it }
    }

    /**
     * The placeholder that reproduces [matrixIds] once [prefix] is removed, or null.
     *
     * Two shapes count as levels. Plain numbers give `{z}`. Numbers padded to a fixed
     * width give `{z:0N}` — `00`, `01`, `02` is how several national services index
     * their levels, and asking such a server for `0` gets nothing, because the
     * identifier it published is `00`. Mixed widths would be neither, and are refused.
     */
    private fun placeholderFor(matrixIds: List<String>, prefix: String): String? {
        val levels = levelsOf(matrixIds, prefix) ?: return null
        val rests = matrixIds.map { it.removePrefix(prefix) }

        if (rests.zip(levels).all { (rest, level) -> rest == level.toString() }) return ZOOM

        val width = rests.first().length
        val padded = rests.zip(levels).all { (rest, level) ->
            rest.length == width && rest == level.toString().padStart(width, '0')
        }
        return if (padded) "{z:0$width}" else null
    }

    /**
     * The zoom levels [matrixIds] denote once [prefix] is removed, or null if they are
     * not zoom levels at all.
     *
     * The range check is what separates a level from a scale denominator. A matrix set
     * indexed `1:500000`, `1:250000` parses as the prefix `1:` followed by an integer and
     * would otherwise yield the template `1:{z}` — which addresses nothing, since no
     * server has a level 12 called `1:12`. Bounding by the deepest zoom this project can
     * serve at all rejects that whole family, and ascending order rejects the coarse-to-
     * fine scale listing besides.
     *
     * Padding is handled rather than refused, by [placeholderFor]: `L00` is not `L` plus
     * 0, but it is `L` plus 0 padded to two digits, and the template can say so.
     */
    private fun levelsOf(matrixIds: List<String>, prefix: String): List<Int>? {
        val levels = matrixIds.map { id ->
            if (!id.startsWith(prefix)) return null
            val rest = id.removePrefix(prefix)
            // Digits only: toIntOrNull would otherwise accept a leading sign.
            if (rest.isEmpty() || !rest.all { it.isDigit() }) return null
            val value = rest.toIntOrNull() ?: return null
            if (value !in 0..MAX_ZOOM) return null
            value
        }
        return if (levels.zipWithNext().all { (a, b) -> b > a }) levels else null
    }

    /** Matches [TileMath.tilesPerAxis]: past this there is no tile to ask for. */
    private const val MAX_ZOOM = 30

    private const val ZOOM = "{z}"

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
        // Encoded around the placeholders, never through them: queryEncoded() would turn
        // {z} into %7Bz%7D and nothing would ever substitute it again.
        append("&TILEMATRIX=")
        append(matrixTemplate.encodedAroundPlaceholders())
        append("&TILEROW={y}")
        append("&TILECOL={x}")
        append("&FORMAT=").append(format.queryEncoded())
    }

    // --------------------------------------------------------------- helpers

    /** WMS 1.3.0's EX_GeographicBoundingBox, or 1.1.1's LatLonBoundingBox. */
    private fun Element.geographicCentre(): LonLat? {
        child("EX_GeographicBoundingBox")?.let { box ->
            val west = box.child("westBoundLongitude")?.text()?.toDoubleOrNull()
            val east = box.child("eastBoundLongitude")?.text()?.toDoubleOrNull()
            val south = box.child("southBoundLatitude")?.text()?.toDoubleOrNull()
            val north = box.child("northBoundLatitude")?.text()?.toDoubleOrNull()
            if (west != null && east != null && south != null && north != null) {
                return LonLat((west + east) / 2, (south + north) / 2)
            }
        }
        child("LatLonBoundingBox")?.let { box ->
            val west = box.getAttribute("minx").toDoubleOrNull()
            val east = box.getAttribute("maxx").toDoubleOrNull()
            val south = box.getAttribute("miny").toDoubleOrNull()
            val north = box.getAttribute("maxy").toDoubleOrNull()
            if (west != null && east != null && south != null && north != null) {
                return LonLat((west + east) / 2, (south + north) / 2)
            }
        }
        return null
    }

    /** `<ows:WGS84BoundingBox>`, whose corners are `longitude latitude` pairs. */
    private fun Element.wgs84Centre(): LonLat? {
        val box = child("WGS84BoundingBox") ?: return null
        val lower = box.child("LowerCorner")?.text()?.split(Regex("\\s+"))
        val upper = box.child("UpperCorner")?.text()?.split(Regex("\\s+"))
        if (lower == null || upper == null || lower.size < 2 || upper.size < 2) return null
        val west = lower[0].toDoubleOrNull() ?: return null
        val south = lower[1].toDoubleOrNull() ?: return null
        val east = upper[0].toDoubleOrNull() ?: return null
        val north = upper[1].toDoubleOrNull() ?: return null
        return LonLat((west + east) / 2, (south + north) / 2)
    }

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
    /** Any `{...}` token this project expands, so encoding can step over them. */
    /** Escaped at both ends for the reason given on [TileLayer.PADDED_ZOOM]. */
    private val PLACEHOLDER = Regex("""\{[a-z]+(?::\d{1,2})?\}""")

    /**
     * Percent-encodes everything except the placeholders, which must survive verbatim.
     */
    private fun String.encodedAroundPlaceholders(): String = buildString {
        var cursor = 0
        for (match in PLACEHOLDER.findAll(this@encodedAroundPlaceholders)) {
            append(this@encodedAroundPlaceholders.substring(cursor, match.range.first).queryEncoded())
            append(match.value)
            cursor = match.range.last + 1
        }
        append(this@encodedAroundPlaceholders.substring(cursor).queryEncoded())
    }

    private fun String.queryEncoded(): String = buildString {
        // Byte-wise over UTF-8, masked to unsigned. A signed Byte would render as
        // FFFFFFC3, and testing a high byte with isLetterOrDigit() would pass Latin-1
        // letters straight through unencoded — both break the moment a layer name
        // carries an umlaut, which German services do routinely.
        for (byte in this@queryEncoded.toByteArray()) {
            val value = byte.toInt() and 0xFF
            val c = value.toChar()
            val unreserved = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "-_.~:/,"
            // Locale.ROOT, because format() without one uses the device's default and
            // a locale with non-ASCII digits would percent-encode into gibberish the
            // upstream cannot read. Nothing here is for a human to look at.
            if (unreserved) append(c)
            else append('%').append(String.format(Locale.ROOT, "%02X", value))
        }
    }
}
