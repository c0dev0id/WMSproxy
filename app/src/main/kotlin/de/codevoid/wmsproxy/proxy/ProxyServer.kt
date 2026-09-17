package de.codevoid.wmsproxy.proxy

import de.codevoid.wmsproxy.core.LoggedRequest
import de.codevoid.wmsproxy.core.RequestLog
import de.codevoid.wmsproxy.core.TileMath
import de.codevoid.wmsproxy.core.TileRef
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
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
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * The embedded HTTP server, bound to loopback.
 *
 * It speaks exactly one protocol to the client: XYZ tiles at
 * `/t/<source>/<layer>/{z}/{x}/{y}`. Everything the upstream world does differently —
 * WMS, WMTS, flipped rows, quadkeys, subdomains, authentication — is absorbed on the
 * way out.
 *
 * Serving WMS northbound was considered and dropped. A tile request carries an integer
 * z/x/y, so there is no extent to interpret, no axis order to get wrong, and no
 * arbitrary bbox that might not correspond to a tile. Accepting GetMap would have
 * reintroduced all three for no gain, since the client can express a tile template
 * directly.
 *
 * Requests are rewritten, never re-rendered. The upstream response body is relayed as
 * it arrives; nothing here decodes an image.
 */
class ProxyServer(
    private val port: Int,
    private val log: RequestLog,
    private val layers: List<TileLayer> = BuiltInSources.all,
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private var server: EmbeddedServer<*, *>? = null

    val baseUrl: String get() = "http://$HOST:$port"

    /** The template to paste into a client's custom-layer field. */
    fun templateFor(layer: TileLayer): String =
        "$baseUrl/t/${layer.source}/${layer.layer}/{z}/{x}/{y}.png"

    fun start() {
        if (server != null) return
        server = embeddedServer(CIO, port = port, host = HOST) {
            routing {
                get("/t/{source}/{layer}/{z}/{x}/{y}") { handleTile(call) }
                get("/") {
                    call.respondText(
                        "WMSproxy\n\n" + layers.joinToString("\n") { templateFor(it) },
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

    private suspend fun handleTile(call: ApplicationCall) {
        val source = call.parameters["source"].orEmpty()
        val layerId = call.parameters["layer"].orEmpty()
        val layer = layers.firstOrNull { it.source == source && it.layer == layerId }
        if (layer == null) {
            record(call, 404, "unknown layer '$source/$layerId'")
            call.respondText("Unknown layer: $source/$layerId", status = HttpStatusCode.NotFound)
            return
        }

        val z = call.parameters["z"]?.toIntOrNull()
        val x = call.parameters["x"]?.toIntOrNull()
        // The path carries whatever extension the client chose; it is not part of the index.
        val y = call.parameters["y"]?.substringBefore('.')?.toIntOrNull()
        if (z == null || x == null || y == null) {
            record(call, 400, "malformed tile index")
            call.respondText("Malformed tile index", status = HttpStatusCode.BadRequest)
            return
        }

        val perAxis = runCatching { TileMath.tilesPerAxis(z) }.getOrNull()
        if (perAxis == null || x !in 0 until perAxis || y !in 0 until perAxis) {
            record(call, 400, "tile index out of range for zoom $z")
            call.respondText("Tile out of range", status = HttpStatusCode.BadRequest)
            return
        }

        relay(call, layer, TileRef(z, x, y))
    }

    /**
     * Fetches the upstream tile and relays it unchanged.
     *
     * A failure is reported as a failure. Returning a blank tile instead would be cached
     * by the client as though it were real data and would persist as a hole in the map.
     */
    private suspend fun relay(call: ApplicationCall, layer: TileLayer, tile: TileRef) {
        val url = layer.urlFor(tile)
        val tileRef = "z${tile.zoom}/${tile.x}/${tile.y}"
        val upstream = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .apply { layer.referer?.let { header("Referer", it) } }
            .build()

        try {
            client.newCall(upstream).execute().use { response ->
                val body = response.body
                if (!response.isSuccessful || body == null) {
                    record(call, 502, "$tileRef -> HTTP ${response.code} $url")
                    call.respondText(
                        "Upstream returned HTTP ${response.code}",
                        status = HttpStatusCode.BadGateway,
                    )
                    return
                }

                val contentType = body.contentType()?.toString() ?: "application/octet-stream"
                // An upstream answering 200 with an HTML error page is a failure, not a
                // tile. Relaying it would put markup in the client's tile cache.
                if (contentType.startsWith("text/") || contentType.contains("html")) {
                    record(call, 502, "$tileRef -> non-image $contentType $url")
                    call.respondText(
                        "Upstream returned $contentType, not an image",
                        status = HttpStatusCode.BadGateway,
                    )
                    return
                }

                val bytes = body.bytes()
                record(call, 200, "$tileRef -> ${bytes.size}B $contentType")
                val parsed = runCatching { ContentType.parse(contentType) }
                    .getOrDefault(ContentType.Application.OctetStream)
                call.respondBytes(bytes, parsed)
            }
        } catch (e: Exception) {
            record(call, 502, "$tileRef -> ${e.javaClass.simpleName}: ${e.message}")
            call.respondText("Upstream request failed", status = HttpStatusCode.BadGateway)
        }
    }

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
