package de.codevoid.wmsproxy.proxy

import android.content.Context
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.net.ServerSocketFactory
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * Supplies the TLS socket factory for the HTTPS listener from a certificate fetched at
 * runtime, not bundled in the APK.
 *
 * The listener presents a publicly-issued certificate for [HOST], a name that resolves to
 * 127.0.0.1, so a client validating TLS accepts it with no manual trust step. That
 * certificate is short-lived: a copy baked into the APK expires between releases and takes
 * the HTTPS listener down with it. So it is fetched from [CERT_URL] once, cached in the
 * app's files dir, and refreshed in the background as it nears expiry.
 *
 * The fetch credentials and the keystore password are the same trivial string and the
 * URL is effectively public — but the key only authenticates a loopback listener that
 * already serves upstream credentials to any local app, so publishing it grants nothing
 * that local access does not already give.
 *
 * PKCS12, not JKS — Android does not support the JKS keystore type.
 */
object Tls {

    /** The host the certificate is issued for; the HTTPS URL must name it, not the IP. */
    const val HOST = "local.codevoid.de"

    private const val CERT_URL = "https://dns.codevoid.de/cert/local.codevoid.de.p12"
    private const val BASIC_USER = "wmsproxy"
    private const val BASIC_PASSWORD = "wmsproxy"
    private val PASSWORD = "wmsproxy".toCharArray()
    private const val CACHE_NAME = "tls-cert.p12"

    // Let's Encrypt renews well before expiry, so a cached certificate inside this window
    // can expect the server to already hold a newer one. Refreshing earlier only refetches
    // the same bytes.
    private val REFRESH_BEFORE_MS = TimeUnit.DAYS.toMillis(21)

    // Validating, deliberately: this fetch is from a real host and must not reuse the
    // certificate-blind Upstream client.
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun cacheFile(context: Context): File = File(context.filesDir, CACHE_NAME)

    /** Null when no usable cached certificate exists, so the caller serves plain HTTP only. */
    fun serverSocketFactory(context: Context): ServerSocketFactory? = runCatching {
        val keyStore = loadKeyStore(readCache(context)) ?: return null

        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(keyStore, PASSWORD) }
            .keyManagers

        SSLContext.getInstance("TLS")
            .apply { init(keyManagers, null, null) }
            .serverSocketFactory
    }.getOrNull()

    /**
     * Fetches a fresh certificate when the cached one is missing or nearing expiry, and
     * replaces the cache only when the fetched certificate outlives it.
     *
     * The newer-than gate is the point: the trigger is the cached certificate nearing
     * expiry, and if the server has not renewed yet the fetch returns the very same
     * certificate. Writing it back would leave the trigger armed and refetch on every
     * start — a renew loop. Replacing only on a later notAfter breaks it.
     *
     * Returns true when the cache was written, so the caller can bring up a listener that
     * had none. Any network or parse failure is swallowed and reported as false; the plain
     * listener keeps serving.
     */
    fun refreshIfNeeded(context: Context): Boolean {
        val currentExpiry = loadKeyStore(readCache(context))?.let(::notAfter)

        if (currentExpiry != null &&
            currentExpiry.time - System.currentTimeMillis() > REFRESH_BEFORE_MS
        ) {
            return false
        }

        val fetched = fetch() ?: return false
        val fetchedExpiry = loadKeyStore(fetched)?.let(::notAfter) ?: return false

        // Nothing cached yet, or the fetched certificate genuinely outlives the cache.
        if (currentExpiry != null && !fetchedExpiry.after(currentExpiry)) return false

        return writeCache(context, fetched)
    }

    private fun fetch(): ByteArray? = runCatching {
        val request = Request.Builder()
            .url(CERT_URL)
            .header("Authorization", Credentials.basic(BASIC_USER, BASIC_PASSWORD))
            .build()
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body?.bytes() else null
        }
    }.getOrNull()

    private fun readCache(context: Context): ByteArray? {
        val file = cacheFile(context)
        return if (file.exists()) runCatching { file.readBytes() }.getOrNull() else null
    }

    private fun writeCache(context: Context, bytes: ByteArray): Boolean = runCatching {
        val file = cacheFile(context)
        val tmp = File(file.parentFile, "$CACHE_NAME.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) {
            file.writeBytes(bytes)
            tmp.delete()
        }
        true
    }.getOrDefault(false)

    private fun loadKeyStore(bytes: ByteArray?): KeyStore? {
        if (bytes == null) return null
        return runCatching {
            KeyStore.getInstance("PKCS12").apply {
                bytes.inputStream().use { load(it, PASSWORD) }
            }
        }.getOrNull()
    }

    private fun notAfter(keyStore: KeyStore): Date? = runCatching {
        keyStore.aliases().toList().firstNotNullOfOrNull { alias ->
            (keyStore.getCertificate(alias) as? X509Certificate)?.notAfter
        }
    }.getOrNull()
}
