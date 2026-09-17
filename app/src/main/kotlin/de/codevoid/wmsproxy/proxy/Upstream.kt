package de.codevoid.wmsproxy.proxy

import android.annotation.SuppressLint
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
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
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .sslSocketFactory(InsecureTls.socketFactory, InsecureTls.trustManager)
        .hostnameVerifier(InsecureTls.hostnameVerifier)
        .build()
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
