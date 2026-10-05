package de.codevoid.wmsproxy.catalog

import de.codevoid.wmsproxy.core.CapabilitiesParser
import de.codevoid.wmsproxy.core.CapabilitiesResult
import de.codevoid.wmsproxy.proxy.ProxyServer
import de.codevoid.wmsproxy.proxy.Upstream
import okhttp3.Request

/**
 * Reads a service's document, trying the address as given and then the well-known
 * variants — ArcGIS `?f=json`, WMS and WMTS GetCapabilities — so a bare service URL
 * works as often as a full capabilities URL. Blocking; call it from IO.
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
        for (candidate in candidatesFor(trimmed)) {
            when (val result = tryFetch(candidate)) {
                is FetchResult.Document -> return result
                is FetchResult.Failed -> lastFailure = result
            }
        }
        return lastFailure
    }

    /**
     * Candidates to try, verbatim first. ArcGIS REST paths get `?f=json` before the
     * WMS/WMTS attempts because the REST description is the canonical ArcGIS import;
     * WMS before WMTS because it is more common; `?f=json` last for every address, since
     * some ArcGIS servers sit at paths that do not follow the convention.
     */
    private fun candidatesFor(url: String): List<String> {
        val upper = url.uppercase()
        val base = url.substringBefore('?')
        val candidates = mutableListOf(url)

        if ("/rest/services/" in url || "/mapserver" in url.lowercase()) {
            candidates.addIfNew("$base?f=json")
        }
        if ("REQUEST=GETCAPABILITIES" !in upper) {
            if ("SERVICE=" !in upper) {
                candidates.addIfNew(appendQuery(url, "SERVICE=WMS&REQUEST=GetCapabilities"))
                candidates.addIfNew(appendQuery(url, "SERVICE=WMTS&REQUEST=GetCapabilities"))
            } else {
                candidates.addIfNew(appendQuery(url, "REQUEST=GetCapabilities"))
            }
        }
        candidates.addIfNew("$base?f=json")
        return candidates
    }

    private fun appendQuery(url: String, params: String) =
        if ('?' in url) "$url&$params" else "$url?$params"

    private fun MutableList<String>.addIfNew(url: String) {
        if (url !in this) add(url)
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
