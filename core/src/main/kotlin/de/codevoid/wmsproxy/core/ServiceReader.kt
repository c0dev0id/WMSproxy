package de.codevoid.wmsproxy.core

import java.io.InputStream

/**
 * Reads a service into a [CachedService] the one way the app and the catalogue tool
 * share: a tile template becomes a service of one implicit layer without a request;
 * anything else is tried at the addresses [CapabilitiesCandidates] lists, parsed, and
 * asked once whether a padded zoom may be plain ([PlainZoom]). The HTTP GET is the
 * caller's, so each side brings its own client; what a service is, is decided here and
 * nowhere else, so the shipped catalogue and a read on the phone cannot disagree.
 */
object ServiceReader {

    /** What one GET answered. The body is closed here; null when the caller sent none. */
    class Reply(val status: Int, val contentType: String?, val body: InputStream?) {
        val isSuccess: Boolean get() = status in 200..299
    }

    sealed interface Read {
        data class Service(val service: CachedService) : Read
        data class Failed(val message: String) : Read
    }

    fun read(url: String, now: Long, get: (String) -> Reply): Read {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return Read.Failed("Enter a URL")
        if (XyzTemplate.isTemplate(trimmed)) return Read.Service(CachedService.forTemplate(url, now))
        var lastFailure = "No response from server"
        for (candidate in CapabilitiesCandidates.candidatesFor(trimmed)) {
            when (val attempt = fetch(candidate, get)) {
                is Fetched.Document ->
                    return Read.Service(CachedService.of(url, candidate, withPlainZoom(attempt.document, get), now))
                is Fetched.Failed -> lastFailure = attempt.message
            }
        }
        return Read.Failed(lastFailure)
    }

    private sealed interface Fetched {
        class Document(val document: CapabilitiesResult.Success) : Fetched
        class Failed(val message: String) : Fetched
    }

    private fun fetch(url: String, get: (String) -> Reply): Fetched = try {
        val reply = get(url)
        val body = reply.body
        when {
            !reply.isSuccess -> {
                body?.close()
                Fetched.Failed("Server returned HTTP ${reply.status}")
            }
            body == null -> Fetched.Failed("Server returned an empty response")
            else -> body.use { stream ->
                when (val parsed = CapabilitiesParser.parse(stream, url)) {
                    is CapabilitiesResult.Success ->
                        if (parsed.layers.isEmpty() && parsed.skipped.isEmpty()) {
                            Fetched.Failed("No layers in that document")
                        } else {
                            Fetched.Document(parsed)
                        }
                    is CapabilitiesResult.Failure -> Fetched.Failed(parsed.message)
                }
            }
        }
    } catch (e: Exception) {
        Fetched.Failed("${e.javaClass.simpleName}: ${e.message}")
    }

    /**
     * The document with its padded zooms made plain when the server answers one such
     * tile with an image; the document as read when it does not, or when nothing in it
     * pads its zoom and there is nothing to ask.
     */
    private fun withPlainZoom(document: CapabilitiesResult.Success, get: (String) -> Reply): CapabilitiesResult.Success {
        val sample = PlainZoom.sample(document) ?: return document
        val answered = runCatching {
            val reply = get(sample)
            reply.body?.close()
            reply.isSuccess && TileMediaType.isRasterImage(reply.contentType)
        }.getOrDefault(false)
        return if (answered) PlainZoom.plainForm(document) else document
    }
}
