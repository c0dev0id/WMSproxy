package de.codevoid.wmsproxy.proxy

import android.content.Context
import java.security.KeyStore
import javax.net.ServerSocketFactory
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * Builds the TLS socket factory the HTTPS listener uses.
 *
 * The keystore ships in assets rather than being generated on device. Generating would
 * need a certificate-building library, and would buy nothing: the key only ever
 * authenticates 127.0.0.1, and any app on the device can already reach the proxy, so
 * keeping it secret protects nothing. Regenerate with tools/generate-localhost-cert.sh.
 *
 * PKCS12, not JKS — Android does not support the JKS keystore type.
 */
object Tls {

    private const val ASSET = "localhost.p12"
    private val PASSWORD = "wmsproxy".toCharArray()

    /** Returns null when the keystore cannot be loaded, so the caller can serve plain HTTP only. */
    fun serverSocketFactory(context: Context): ServerSocketFactory? = runCatching {
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            context.assets.open(ASSET).use { load(it, PASSWORD) }
        }

        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(keyStore, PASSWORD) }
            .keyManagers

        SSLContext.getInstance("TLS")
            .apply { init(keyManagers, null, null) }
            .serverSocketFactory
    }.getOrNull()

    /** The certificate itself, for installing on a device willing to trust it. */
    fun certificateBytes(context: Context): ByteArray =
        context.assets.open("localhost.crt").use { it.readBytes() }
}
