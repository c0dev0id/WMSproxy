package de.codevoid.wmsproxy.proxy

import de.codevoid.wmsproxy.core.LoggedRequest
import de.codevoid.wmsproxy.core.RequestLog
import de.codevoid.wmsproxy.core.TileMath
import de.codevoid.wmsproxy.core.TileRef
import de.codevoid.wmsproxy.core.wms.CapabilitiesWriter
import de.codevoid.wmsproxy.core.wms.WmsKvp
import de.codevoid.wmsproxy.core.wms.WmsOperation
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.queryString
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * The embedded HTTP server, bound to loopback.
 *
 * Two façades over the same sources: a WMS service at `/wms` that a client adds once and
 * browses for layers, and an XYZ endpoint at `/t/{layer}/{z}/{x}/{y}` for clients that
 * take a URL template.
 *
 * Requests are rewritten, never re-rendered. The upstream response body is relayed as it
 * arrives; nothing here decodes an image.
 */
class ProxyServer(
    private val port: Int,
    private val log: RequestLog,
    private val sources: List<TileSource> = BuiltInSources.all,
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private var server: EmbeddedServer<*, *>? = null

    val baseUrl: String get() = "http://$HOST:$port"
    val wmsUrl: String get() = "$baseUrl/wms"

    fun tileTemplateFor(source: TileSource): String =
        "$baseUrl/t/${source.id}/{z}/{x}/{y}.png"

    /**
     * A full GetMap template for clients whose "custom raster" field substitutes
     * placeholders rather than reading GetCapabilities. DMD2's dialog documents exactly
     * this shape, so the service URL on its own is not usable there.
     *
     * WIDTH and HEIGHT are pinned to the tile size because the alignment gate only
     * accepts an extent that is exactly one tile at the grid's pixel size.
     */
    fun wmsTemplateFor(source: TileSource): String =
        "$wmsUrl?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=${source.id}" +
            "&STYLES=&CRS=EPSG:3857&BBOX={bbox}" +
            "&WIDTH=${TileMath.DEFAULT_TILE_SIZE}&HEIGHT=${TileMath.DEFAULT_TILE_SIZE}" +
            "&FORMAT=image/png&TRANSPARENT=true"

    fun start() {
        if (server != null) return
        server = embeddedServer(CIO, port = port, host = HOST) {
            routing {
                get("/wms") { handleWms(call) }
                get("/t/{layer}/{z}/{x}/{y}") { handleTile(call) }
                get("/") {
                    call.respondText(
                        "WMSproxy\n\nWMS: $wmsUrl\n" +
                            sources.joinToString("\n") { "XYZ: ${tileTemplateFor(it)}" },
                        ContentType.Text.Plain,
                    )
                }
            }
        }.also { it.start(wait = false) }
    }

    fun stop() {
        server?.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
        server = null
    }

    private suspend fun handleWms(call: ApplicationCall) {
        val query = call.request.queryString()
        val params = WmsKvp.parseQuery(query)
        val version = params["VERSION"] ?: params["WMTVER"] ?: "1.3.0"

        when (WmsKvp.operationOf(params)) {
            WmsOperation.GET_CAPABILITIES -> {
                val body = CapabilitiesWriter.write(version, wmsUrl, sources.map { it.asLayer() })
                record(call, HttpStatusCode.OK.value, "GetCapabilities $version")
                call.respondText(body, ContentType.Text.Xml)
            }

            WmsOperation.GET_MAP -> handleGetMap(call, params, version)

            WmsOperation.OTHER -> {
                val op = params["REQUEST"] ?: "(none)"
                record(call, HttpStatusCode.BadRequest.value, "unsupported REQUEST=$op")
                respondException(call, version, HttpStatusCode.BadRequest, "Unsupported REQUEST=$op")
            }
        }
    }

    private suspend fun handleGetMap(
        call: ApplicationCall,
        params: Map<String, String>,
        version: String,
    ) {
        val request = WmsKvp.parseGetMap(params)
        if (request == null) {
            record(call, 400, "GetMap: missing or malformed parameters")
            respondException(call, version, HttpStatusCode.BadRequest, "Malformed GetMap request")
            return
        }

        val source = request.layers.firstOrNull()?.let { id -> sources.firstOrNull { it.id == id } }
        if (source == null) {
            val note = "GetMap: unknown layer ${request.layers}"
            record(call, 400, note)
            respondException(call, version, HttpStatusCode.BadRequest, note)
            return
        }

        // Multi-layer would mean compositing, which is image work this proxy does not do.
        if (request.layers.size > 1) {
            val note = "GetMap: ${request.layers.size} layers requested; only one is supported"
            record(call, 400, note)
            respondException(call, version, HttpStatusCode.BadRequest, note)
            return
        }

        if (request.crs != "EPSG:3857") {
            val note = "GetMap: CRS ${request.crs} not served (tile layers are EPSG:3857)"
            record(call, 400, note)
            respondException(call, version, HttpStatusCode.BadRequest, note)
            return
        }

        val tile = TileMath.solveTile(request.bbox, request.width, request.height)
        if (tile == null) {
            // The answer to the question this build exists to settle. Recorded with the
            // offending extent so an unaligned client is diagnosable from the log alone.
            val note = "GetMap: NOT tile-aligned " +
                "bbox=${request.bbox.minX},${request.bbox.minY}," +
                "${request.bbox.maxX},${request.bbox.maxY} " +
                "size=${request.width}x${request.height}"
            record(call, 400, note)
            respondException(call, version, HttpStatusCode.BadRequest, note)
            return
        }

        relay(call, source, tile, "GetMap")
    }

    private suspend fun handleTile(call: ApplicationCall) {
        val layerId = call.parameters["layer"].orEmpty()
        val source = sources.firstOrNull { it.id == layerId }
        if (source == null) {
            record(call, 404, "unknown layer '$layerId'")
            call.respondText("Unknown layer: $layerId", status = HttpStatusCode.NotFound)
            return
        }

        val z = call.parameters["z"]?.toIntOrNull()
        val x = call.parameters["x"]?.toIntOrNull()
        // The path carries an extension the client chose; it is not part of the index.
        val y = call.parameters["y"]?.substringBefore('.')?.toIntOrNull()
        if (z == null || x == null || y == null) {
            record(call, 400, "malformed tile index")
            call.respondText("Malformed tile index", status = HttpStatusCode.BadRequest)
            return
        }

        val n = runCatching { TileMath.tilesPerAxis(z) }.getOrNull()
        if (n == null || x !in 0 until n || y !in 0 until n) {
            record(call, 400, "tile index out of range for zoom $z")
            call.respondText("Tile out of range", status = HttpStatusCode.BadRequest)
            return
        }

        relay(call, source, TileRef(z, x, y), "XYZ")
    }

    /**
     * Fetches the upstream tile and relays it unchanged.
     *
     * A failure is reported as a failure. Returning a blank tile instead would be cached
     * by the client as though it were real data and would persist as a hole in the map.
     */
    private suspend fun relay(
        call: ApplicationCall,
        source: TileSource,
        tile: TileRef,
        via: String,
    ) {
        val url = source.urlFor(tile)
        val upstream = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .apply { source.referer?.let { header("Referer", it) } }
            .build()

        try {
            client.newCall(upstream).execute().use { response ->
                val body = response.body
                if (!response.isSuccessful || body == null) {
                    val note = "$via z${tile.zoom}/${tile.x}/${tile.y} -> HTTP ${response.code} $url"
                    record(call, HttpStatusCode.BadGateway.value, note)
                    call.respondText(
                        "Upstream returned HTTP ${response.code}",
                        status = HttpStatusCode.BadGateway,
                    )
                    return
                }

                val contentType = body.contentType()?.toString() ?: "application/octet-stream"
                // An upstream that answers 200 with an HTML error page is a failure, not
                // a tile. Relaying it would put markup in the client's tile cache.
                if (contentType.startsWith("text/") || contentType.contains("html")) {
                    val note = "$via z${tile.zoom}/${tile.x}/${tile.y} -> non-image $contentType $url"
                    record(call, HttpStatusCode.BadGateway.value, note)
                    call.respondText(
                        "Upstream returned $contentType, not an image",
                        status = HttpStatusCode.BadGateway,
                    )
                    return
                }

                val bytes = body.bytes()
                record(call, 200, "$via z${tile.zoom}/${tile.x}/${tile.y} -> ${bytes.size}B $contentType")
                val parsed = runCatching { ContentType.parse(contentType) }
                    .getOrDefault(ContentType.Application.OctetStream)
                call.respondBytes(bytes, parsed)
            }
        } catch (e: Exception) {
            val note = "$via z${tile.zoom}/${tile.x}/${tile.y} -> ${e.javaClass.simpleName}: ${e.message}"
            record(call, HttpStatusCode.BadGateway.value, note)
            call.respondText("Upstream request failed", status = HttpStatusCode.BadGateway)
        }
    }

    private suspend fun respondException(
        call: ApplicationCall,
        version: String,
        status: HttpStatusCode,
        message: String,
    ) = call.respondText(
        CapabilitiesWriter.exception(version, message),
        ContentType.Text.Xml,
        status,
    )

    private fun record(call: ApplicationCall, status: Int, note: String) {
        log.record(
            LoggedRequest(
                at = System.currentTimeMillis(),
                method = call.request.httpMethod.value,
                path = call.request.path(),
                query = call.request.queryString(),
                userAgent = call.request.header("User-Agent"),
                status = status,
                note = note,
            ),
        )
    }

    private companion object {
        /** Loopback only: the proxy serves upstream credentials without asking for any. */
        const val HOST = "127.0.0.1"
        const val USER_AGENT = "WMSproxy"
    }
}
