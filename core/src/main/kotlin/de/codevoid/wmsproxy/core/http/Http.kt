package de.codevoid.wmsproxy.core.http

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/** A parsed request. Header names are lowercased, since HTTP treats them case-insensitively. */
data class HttpRequest(
    val method: String,
    /**
     * The path exactly as received, still percent-encoded. Only for logging — routing
     * uses [segments], which is the decoded form.
     */
    val path: String,
    /** Raw query string without the `?`, empty when absent. */
    val query: String,
    /**
     * Decoded, non-empty path segments, and the authoritative form for routing.
     *
     * Splitting happens before decoding and the result is never rejoined, so a `%2F`
     * inside a segment stays inside it. Deriving this by decoding the whole path and
     * splitting afterwards would let an encoded separator reach a route it was never
     * meant to.
     */
    val segments: List<String>,
    val headers: Map<String, String>,
) {
    fun header(name: String): String? = headers[name.lowercase()]
}

data class HttpResponse(
    val status: Int,
    val reason: String,
    val contentType: String,
    val body: ByteArray,
) {
    fun writeTo(output: OutputStream) {
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            // Every response closes its connection. Keep-alive would mean tracking
            // connection state and reading pipelined requests for no benefit: this
            // serves one client over loopback, where a fresh connection is nearly free.
            append("Connection: close\r\n")
            append("\r\n")
        }
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        output.write(body)
        output.flush()
    }

    // Generated equals/hashCode would compare the body array by identity.
    override fun equals(other: Any?): Boolean =
        this === other || (
            other is HttpResponse &&
                status == other.status &&
                reason == other.reason &&
                contentType == other.contentType &&
                body.contentEquals(other.body)
            )

    override fun hashCode(): Int =
        (((status * 31 + reason.hashCode()) * 31) + contentType.hashCode()) * 31 +
            body.contentHashCode()

    companion object {
        fun text(status: Int, reason: String, message: String): HttpResponse =
            HttpResponse(status, reason, "text/plain; charset=utf-8", message.toByteArray())

        fun ok(contentType: String, body: ByteArray): HttpResponse =
            HttpResponse(200, "OK", contentType, body)

        fun notFound(message: String): HttpResponse = text(404, "Not Found", message)
        fun badRequest(message: String): HttpResponse = text(400, "Bad Request", message)
        fun badGateway(message: String): HttpResponse = text(502, "Bad Gateway", message)
    }
}

/**
 * Parses just enough HTTP/1.1 to serve tile requests.
 *
 * The client is one app on loopback issuing GETs with no body, so this deliberately does
 * not implement chunked transfer, request bodies, pipelining, keep-alive or header
 * folding. Anything it does not understand becomes a 400 rather than a guess.
 *
 * Both limits below exist to stop a malformed or hostile local client from making the
 * server allocate without bound.
 */
object HttpParser {

    private const val MAX_LINE_BYTES = 8 * 1024
    private const val MAX_HEADERS = 64

    /** Returns null when the request is malformed or exceeds the limits. */
    fun parse(input: InputStream): HttpRequest? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size != 3) return null

        val method = parts[0]
        val target = parts[1]
        if (!parts[2].startsWith("HTTP/")) return null
        if (method.isEmpty() || !target.startsWith("/")) return null

        val queryStart = target.indexOf('?')
        val rawPath = if (queryStart < 0) target else target.substring(0, queryStart)
        val query = if (queryStart < 0) "" else target.substring(queryStart + 1)

        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            if (headers.size >= MAX_HEADERS) return null
            val colon = line.indexOf(':')
            if (colon <= 0) return null
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }

        return HttpRequest(
            method = method,
            path = rawPath,
            query = query,
            segments = decodeSegments(rawPath),
            headers = headers,
        )
    }

    /**
     * Splits first, then decodes each segment, and never rejoins. That order is the whole
     * point: decoding before splitting turns `%2F` into a separator, and rejoining after
     * decoding loses the boundary again just as surely.
     */
    private fun decodeSegments(rawPath: String): List<String> =
        rawPath.split('/')
            .filter { it.isNotEmpty() }
            .map { it.percentDecodedOrSelf() }

    /** Reads a CRLF- or LF-terminated line as ISO-8859-1, or null at EOF or over the limit. */
    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) return if (buffer.size() == 0) null else buffer.toString("ISO-8859-1")
            if (b == '\n'.code) {
                val bytes = buffer.toByteArray()
                val end = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) {
                    bytes.size - 1
                } else {
                    bytes.size
                }
                return String(bytes, 0, end, Charsets.ISO_8859_1)
            }
            if (buffer.size() >= MAX_LINE_BYTES) return null
            buffer.write(b)
        }
    }
}

/** Percent-decoded, or unchanged when an escape is malformed: a bad `%` is data, not a fault. */
internal fun String.percentDecodedOrSelf(): String =
    runCatching { java.net.URLDecoder.decode(this, Charsets.UTF_8.name()) }.getOrDefault(this)

/** This URL's query parameters by upper-cased name, percent-decoded. Empty without a `?`. */
internal fun String.queryParameters(): Map<String, String> =
    substringAfter('?', "")
        .split('&')
        .filter { it.isNotEmpty() }
        .associate { pair ->
            val key = pair.substringBefore('=').uppercase(java.util.Locale.ROOT)
            key to pair.substringAfter('=', "").percentDecodedOrSelf()
        }
