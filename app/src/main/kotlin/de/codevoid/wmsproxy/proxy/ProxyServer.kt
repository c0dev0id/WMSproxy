package de.codevoid.wmsproxy.proxy

import android.annotation.SuppressLint
import de.codevoid.wmsproxy.BuildConfig
import de.codevoid.wmsproxy.core.LoggedRequest
import de.codevoid.wmsproxy.core.RequestLog
import de.codevoid.wmsproxy.core.TileMath
import de.codevoid.wmsproxy.core.TileMediaType
import de.codevoid.wmsproxy.core.TileRef
import de.codevoid.wmsproxy.core.http.HttpRequest
import de.codevoid.wmsproxy.core.http.HttpResponse
import de.codevoid.wmsproxy.core.http.HttpServer
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ServerSocketFactory
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * The proxy's listeners, bound to loopback.
 *
 * It speaks exactly one protocol to the client: XYZ tiles at
 * `/tileproxy/<source>[/<layer>]/{z}/{x}/{y}`. Everything the upstream world does
 * differently — WMS, WMTS, flipped rows, quadkeys, subdomains, authentication — is
 * absorbed on the way out.
 *
 * Both a plain and a TLS listener run, because a client may refuse cleartext to
 * loopback under its own network security policy while accepting HTTPS.
 *
 * Requests are rewritten, never re-rendered. Nothing here decodes an image.
 */
class ProxyServer(
    private val port: Int,
    private val securePort: Int,
    private val log: RequestLog,
    private val layers: List<TileLayer> = BuiltInSources.all,
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .sslSocketFactory(InsecureTls.socketFactory, InsecureTls.trustManager)
        .hostnameVerifier(InsecureTls.hostnameVerifier)
        .build()

    private var plain: HttpServer? = null
    private var secure: HttpServer? = null

    val baseUrl: String get() = "http://$HOST:$port"
    /**
     * Names the host the certificate was issued for, which is not necessarily the
     * address the listener binds. A certificate for a hostname does not validate when
     * the client connects to a bare IP, so the URL has to use the name and let DNS
     * resolve it back to loopback.
     */
    val secureBaseUrl: String get() = "https://${BuildConfig.TLS_HOST}:$securePort"

    /** True when the TLS listener came up; false when the keystore could not be loaded. */
    var secureAvailable: Boolean = false
        private set

    fun templateFor(layer: TileLayer): String = tileTemplate(baseUrl, layer)

    fun secureTemplateFor(layer: TileLayer): String = tileTemplate(secureBaseUrl, layer)

    private fun tileTemplate(base: String, layer: TileLayer): String =
        "$base/$PREFIX/${layer.path}/{z}/{x}/{y}.png"

    /** [tlsFactory] null serves plain HTTP only. */
    fun start(tlsFactory: ServerSocketFactory?) {
        if (plain == null) {
            plain = HttpServer(HOST, port, handler = ::handle).also { it.start() }
        }
        if (secure == null && tlsFactory != null) {
            secure = HttpServer(HOST, securePort, tlsFactory, ::handle).also { it.start() }
            secureAvailable = true
        }
    }

    fun stop() {
        plain?.stop()
        plain = null
        secure?.stop()
        secure = null
        secureAvailable = false
    }

    private fun handle(request: HttpRequest): HttpResponse {
        val segments = request.segments

        // /tileproxy/<source>[/<layer>]/{z}/{x}/{y}
        if (segments.size >= 5 && segments[0] == PREFIX) {
            return handleTile(request, segments)
        }
        if (segments.isEmpty()) {
            val body = "WMSproxy\n\n" + layers.joinToString("\n") { templateFor(it) }
            return HttpResponse.text(200, "OK", body)
        }
        return record(request, HttpResponse.notFound("Not found"), "no route")
    }

    private fun handleTile(request: HttpRequest, segments: List<String>): HttpResponse {
        // The last three segments are always the tile coordinates; whatever sits between
        // the prefix and them is the source, optionally followed by a layer.
        val coords = segments.takeLast(3)
        val name = segments.subList(1, segments.size - 3)
        if (name.isEmpty() || name.size > 2) {
            return record(request, HttpResponse.notFound("Not found"), "unrecognised path")
        }

        val source = name[0]
        val layerId = name.getOrNull(1)
        val layer = layers.firstOrNull { it.source == source && it.layer == layerId }
        if (layer == null) {
            val requested = name.joinToString("/")
            return record(
                request,
                HttpResponse.notFound("Unknown source: $requested"),
                "unknown source '$requested'",
            )
        }

        val z = coords[0].toIntOrNull()
        val x = coords[1].toIntOrNull()
        // The path carries whatever extension the client chose; it is not part of the index.
        val y = coords[2].substringBefore('.').toIntOrNull()
        if (z == null || x == null || y == null) {
            return record(request, HttpResponse.badRequest("Malformed tile index"), "malformed tile index")
        }

        val perAxis = runCatching { TileMath.tilesPerAxis(z) }.getOrNull()
        if (perAxis == null || x !in 0 until perAxis || y !in 0 until perAxis) {
            return record(
                request,
                HttpResponse.badRequest("Tile out of range"),
                "tile index out of range for zoom $z",
            )
        }

        return relay(request, layer, TileRef(z, x, y))
    }

    /**
     * Fetches the upstream tile and relays it unchanged.
     *
     * A failure is reported as a failure. Returning a blank tile instead would be cached
     * by the client as though it were real data and would persist as a hole in the map.
     */
    private fun relay(request: HttpRequest, layer: TileLayer, tile: TileRef): HttpResponse {
        val url = layer.urlFor(tile)
        val ref = "z${tile.zoom}/${tile.x}/${tile.y}"
        val upstream = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .apply { layer.referer?.let { header("Referer", it) } }
            .build()

        return try {
            client.newCall(upstream).execute().use { response ->
                val body = response.body
                if (!response.isSuccessful || body == null) {
                    return record(
                        request,
                        HttpResponse.badGateway("Upstream returned HTTP ${response.code}"),
                        "$ref -> HTTP ${response.code} $url",
                    )
                }

                val contentType = body.contentType()?.toString()
                // A 200 is not proof of an image. An HTML error page on failed auth, a
                // ServiceExceptionReport, a vector tile from a cache that serves nothing
                // else — all arrive as 200 with a body. Relaying any of them puts bytes
                // the client can never draw into its cache, which is the blank-tile
                // mistake by another route. A raster image goes through untouched,
                // whatever the format: what the client can decode is its own business.
                if (contentType == null || !TileMediaType.isRasterImage(contentType)) {
                    val named = contentType ?: "no content type"
                    return record(
                        request,
                        HttpResponse.badGateway("Upstream returned $named, not an image"),
                        "$ref -> not an image: $named $url",
                    )
                }

                val bytes = body.bytes()
                record(
                    request,
                    HttpResponse.ok(contentType, bytes),
                    "$ref -> ${bytes.size}B $contentType",
                )
            }
        } catch (e: Exception) {
            record(
                request,
                HttpResponse.badGateway("Upstream request failed"),
                "$ref -> ${e.javaClass.simpleName}: ${e.message}",
            )
        }
    }

    private fun record(request: HttpRequest, response: HttpResponse, note: String): HttpResponse {
        log.record(
            LoggedRequest(
                at = System.currentTimeMillis(),
                method = request.method,
                path = request.path,
                query = request.query,
                userAgent = request.header("User-Agent"),
                status = response.status,
                note = note,
            ),
        )
        return response
    }

    private companion object {
        /** Loopback only: the proxy serves upstream credentials without asking for any. */
        const val HOST = "127.0.0.1"
        const val PREFIX = "tileproxy"

        /**
         * Names the proxy, its build and where to complain.
         *
         * Not decoration: the OSM Foundation's tile usage policy requires a User-Agent
         * that identifies the application, and a generic or faked one is grounds for
         * being blocked. Other courtesy hosts apply the same rule. Carrying the build
         * also means a server operator and this project's own request log agree on which
         * version misbehaved.
         */
        val USER_AGENT =
            "WMSproxy/${BuildConfig.VERSION_NAME} (+https://github.com/c0dev0id/WMSproxy)"
    }
}

/**
 * Upstream TLS with certificate and hostname validation switched off.
 *
 * **Temporary. Revisit before authentication lands.**
 *
 * The first real upstream (`tiles.autobahn.de`) failed every request with
 * `CertPathValidatorException: Trust anchor for certification path not found`. The
 * device's trust store is demonstrably fine — the in-app updater reaches
 * `api.github.com` from the same process — so the likely cause is an upstream serving
 * an incomplete chain: a browser fetches the missing intermediate over AIA, Android and
 * OkHttp do not. Diagnosing that was deferred in favour of getting tiles on screen.
 *
 * What this costs: upstream tile traffic is no longer protected against interception.
 * Today that only risks wrong map imagery. It stops being acceptable as soon as
 * configurable sources carry credentials, because Basic auth and API keys would then be
 * sent over connections nobody verified. Before that milestone this must be narrowed —
 * shipping the missing intermediate, or pinning the one host that needs it — not kept
 * as a blanket opt-out.
 */
@SuppressLint("TrustAllX509TrustManager", "BadHostnameVerifier")
private object InsecureTls {

    val trustManager: X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    val socketFactory: SSLSocketFactory =
        SSLContext.getInstance("TLS")
            .apply { init(null, arrayOf<TrustManager>(trustManager), SecureRandom()) }
            .socketFactory

    val hostnameVerifier = HostnameVerifier { _: String?, _: SSLSession? -> true }
}
