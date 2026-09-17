package de.codevoid.wmsproxy.core.wms

import de.codevoid.wmsproxy.core.Bbox

/** The WMS operations the proxy understands. */
enum class WmsOperation { GET_CAPABILITIES, GET_MAP, OTHER }

data class GetMapRequest(
    val version: String,
    val layers: List<String>,
    val crs: String,
    val bbox: Bbox,
    val width: Int,
    val height: Int,
    val format: String,
    val transparent: Boolean,
)

/**
 * Parses WMS key-value-pair query strings.
 *
 * Three things make this less trivial than splitting on `&`:
 *
 * - **Parameter names are case-insensitive** per the spec. Clients send `REQUEST`,
 *   `request` and `Request` interchangeably.
 * - **The CRS parameter is spelled differently per version** — `SRS` in 1.1.1, `CRS` in
 *   1.3.0.
 * - **Axis order flips.** WMS 1.3.0 orders a geographic CRS latitude-first, so an
 *   EPSG:4326 bbox arrives as minLat,minLon,maxLat,maxLon while 1.1.1 sends
 *   minLon,minLat,maxLon,maxLat. Getting this wrong produces a map that renders
 *   perfectly and is in the wrong place, so it is normalized here, once, and everything
 *   downstream works in x-then-y.
 */
object WmsKvp {

    /** CRS codes that are geographic, and therefore axis-swapped in WMS 1.3.0. */
    private val GEOGRAPHIC = setOf("EPSG:4326", "EPSG:4258", "EPSG:4269")

    /** Aliases various servers use for WebMercator. */
    private val WEB_MERCATOR_ALIASES = setOf("EPSG:900913", "EPSG:102100", "EPSG:102113")

    fun parseQuery(raw: String): Map<String, String> =
        raw.removePrefix("?")
            .split('&')
            .filter { it.isNotEmpty() }
            .associate { pair ->
                val i = pair.indexOf('=')
                if (i < 0) {
                    decode(pair).uppercase() to ""
                } else {
                    decode(pair.substring(0, i)).uppercase() to decode(pair.substring(i + 1))
                }
            }

    fun operationOf(params: Map<String, String>): WmsOperation =
        when (params["REQUEST"]?.lowercase()) {
            "getcapabilities" -> WmsOperation.GET_CAPABILITIES
            "getmap" -> WmsOperation.GET_MAP
            else -> WmsOperation.OTHER
        }

    /** Normalizes WebMercator aliases so downstream comparisons are on one spelling. */
    fun normalizeCrs(code: String): String {
        val upper = code.trim().uppercase()
        return if (upper in WEB_MERCATOR_ALIASES) "EPSG:3857" else upper
    }

    fun isGeographic(code: String): Boolean = normalizeCrs(code) in GEOGRAPHIC

    /**
     * Builds a GetMap from parsed parameters, or null when a required one is missing or
     * malformed. Callers turn null into a service exception rather than guessing.
     */
    fun parseGetMap(params: Map<String, String>): GetMapRequest? {
        val version = params["VERSION"] ?: params["WMTVER"] ?: "1.3.0"
        val crsRaw = params["CRS"] ?: params["SRS"] ?: return null
        val crs = normalizeCrs(crsRaw)

        val numbers = params["BBOX"]?.split(',')?.mapNotNull { it.trim().toDoubleOrNull() }
        if (numbers == null || numbers.size != 4) return null

        val width = params["WIDTH"]?.toIntOrNull() ?: return null
        val height = params["HEIGHT"]?.toIntOrNull() ?: return null
        if (width <= 0 || height <= 0) return null

        // The one place axis order is interpreted.
        val latFirst = version.startsWith("1.3") && isGeographic(crs)
        val bbox = if (latFirst) {
            Bbox(minX = numbers[1], minY = numbers[0], maxX = numbers[3], maxY = numbers[2])
        } else {
            Bbox(minX = numbers[0], minY = numbers[1], maxX = numbers[2], maxY = numbers[3])
        }

        return GetMapRequest(
            version = version,
            layers = params["LAYERS"].orEmpty().split(',').filter { it.isNotBlank() },
            crs = crs,
            bbox = bbox,
            width = width,
            height = height,
            format = params["FORMAT"] ?: "image/png",
            transparent = params["TRANSPARENT"].equals("true", ignoreCase = true),
        )
    }

    /**
     * `+` means space in a query string, which is what URLDecoder already does. A
     * malformed escape decodes to itself rather than throwing: a client that sends one
     * should get a service exception naming the real problem, not a decoder crash.
     */
    private fun decode(s: String): String =
        runCatching { java.net.URLDecoder.decode(s, Charsets.UTF_8.name()) }.getOrDefault(s)
}
