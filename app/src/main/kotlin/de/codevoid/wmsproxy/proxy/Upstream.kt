package de.codevoid.wmsproxy.proxy

import android.annotation.SuppressLint
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * The one HTTP client that talks to the outside world.
 *
 * Shared by the tile relay and the capabilities importer deliberately: they speak to the
 * same servers under the same conditions, and two clients would mean two places to undo
 * the certificate bypass below — which is exactly the kind of thing that gets undone in
 * one place only.
 */
object Upstream {
    /**
     * How long a tile may take before it is abandoned.
     *
     * Short on purpose. A worker is blocked for the whole of an upstream request, and a
     * tile that arrives after five seconds is a tile the rider has already scrolled past
     * — the client has given up and the connection was held for nothing. The same value
     * decides which zoom levels a source is recorded as serving, so a request that gets
     * through to the network is one that had a real chance of being answered.
     */
    val TILE_TIMEOUT_SECONDS = 5L

    /**
     * Wide, because this one measures rather than serves.
     *
     * Establishing where a source becomes too slow means letting the slow case finish and
     * timing it. Cutting it off at the tile timeout would record "failed" where the truth
     * is "took thirty-one seconds", and the difference is the whole point of measuring.
     */
    private val PROBE_TIMEOUT_SECONDS = 60L

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(TILE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .sslSocketFactory(InsecureTls.socketFactory, InsecureTls.trustManager)
        .hostnameVerifier(InsecureTls.hostnameVerifier)
        .build()

    /** Shares the connection pool and the trust settings; only the patience differs. */
    val probeClient: OkHttpClient = client.newBuilder()
        .readTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /**
     * How many requests may be in flight to one server at a time.
     *
     * A server that renders on demand gets slower the more it is asked at once, and one
     * measured for this project doubled its response time between eight concurrent
     * requests and sixteen. Past some point the extra parallelism buys nothing and costs
     * everyone: every request slows until they all miss the budget together, which reads
     * as random tiles failing rather than as overload.
     *
     * OkHttp's own dispatcher would do this, but only for asynchronous calls — a
     * synchronous `execute()` bypasses `maxRequestsPerHost` entirely. With a worker per
     * connection and thirty-two workers, that is thirty-two concurrent renders aimed at
     * one server. Six matches OkHttp's own default closely enough and leaves the
     * remaining workers free for other hosts.
     */
    private const val PER_HOST_LIMIT = 6

    private val hostLimits = ConcurrentHashMap<String, Semaphore>()

    /**
     * Runs [block] with a permit for [url]'s host, or returns null when none came free
     * within [waitSeconds].
     *
     * Bounded rather than queued indefinitely: a tile that waits its turn for longer than
     * the budget allows has already lost, and admitting that immediately frees the worker
     * for a request that can still be served. Permits are per host, so one slow server
     * cannot stall requests to a different one.
     */
    fun <T> withHostPermit(url: String, waitSeconds: Long, block: () -> T): T? {
        val host = url.toHttpUrlOrNull()?.host ?: url
        val permits = hostLimits.computeIfAbsent(host) { Semaphore(PER_HOST_LIMIT, true) }
        if (!permits.tryAcquire(waitSeconds, TimeUnit.SECONDS)) return null
        return try {
            block()
        } finally {
            permits.release()
        }
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
