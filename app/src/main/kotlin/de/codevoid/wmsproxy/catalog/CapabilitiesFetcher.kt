package de.codevoid.wmsproxy.catalog

import de.codevoid.wmsproxy.core.CapabilitiesCandidates
import de.codevoid.wmsproxy.core.CapabilitiesParser
import de.codevoid.wmsproxy.core.CapabilitiesResult
import de.codevoid.wmsproxy.core.PlainZoom
import de.codevoid.wmsproxy.core.TileMediaType
import de.codevoid.wmsproxy.proxy.ProxyServer
import de.codevoid.wmsproxy.proxy.Upstream
import okhttp3.Request

/**
 * Reads a service's document, trying the address as given and then the well-known
 * variants — ArcGIS `?f=json`, WMS and WMTS GetCapabilities — so a bare service URL
 * works as often as a full capabilities URL, and asks a server with padded zoom levels
 * once whether it takes the plain form. Blocking; call it from IO.
 */
object CapabilitiesFetcher {

    sealed interface FetchResult {
        /** The document, and the candidate address that answered with it. */
        data class Document(val document: CapabilitiesResult.Success, val from: String) : FetchResult
        data class Failed(val message: String) : FetchResult
    }

    fun fetch(url: String): FetchResult {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return FetchResult.Failed("Enter a URL")
        var lastFailure: FetchResult = FetchResult.Failed("No response from server")
        for (candidate in CapabilitiesCandidates.candidatesFor(trimmed)) {
            when (val result = tryFetch(candidate)) {
                is FetchResult.Document -> return result.copy(document = withPlainZoom(result.document))
                is FetchResult.Failed -> lastFailure = result
            }
        }
        return lastFailure
    }

    /**
     * The document with its padded zooms made plain when the server answers one such
     * tile with an image; the document as read when it does not, or when nothing in it
     * pads its zoom and there is nothing to ask.
     */
    private fun withPlainZoom(document: CapabilitiesResult.Success): CapabilitiesResult.Success {
        val sample = PlainZoom.sample(document) ?: return document
        val answered = runCatching {
            Upstream.capabilitiesClient.newCall(
                Request.Builder().url(sample).header("User-Agent", ProxyServer.USER_AGENT).build(),
            ).execute().use { response ->
                response.isSuccessful && TileMediaType.isRasterImage(response.body?.contentType()?.toString())
            }
        }.getOrDefault(false)
        return if (answered) PlainZoom.plainForm(document) else document
    }

    /**
     * One round-trip on the capabilities client: a document can be megabytes and the
     * user is watching a spinner, not waiting on a tile.
     */
    private fun tryFetch(url: String): FetchResult = try {
        Upstream.capabilitiesClient.newCall(
            Request.Builder().url(url).header("User-Agent", ProxyServer.USER_AGENT).build(),
        ).execute().use { response ->
            if (!response.isSuccessful) return FetchResult.Failed("Server returned HTTP ${response.code}")
            val body = response.body ?: return FetchResult.Failed("Server returned an empty response")
            when (val parsed = CapabilitiesParser.parse(body.byteStream(), url)) {
                is CapabilitiesResult.Success ->
                    if (parsed.layers.isEmpty() && parsed.skipped.isEmpty()) {
                        FetchResult.Failed("No layers in that document")
                    } else {
                        FetchResult.Document(parsed, url)
                    }
                is CapabilitiesResult.Failure -> FetchResult.Failed(parsed.message)
            }
        }
    } catch (e: Exception) {
        FetchResult.Failed("${e.javaClass.simpleName}: ${e.message}")
    }
}
