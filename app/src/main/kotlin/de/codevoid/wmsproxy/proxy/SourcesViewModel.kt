package de.codevoid.wmsproxy.proxy

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.codevoid.wmsproxy.core.CapabilitiesParser
import de.codevoid.wmsproxy.core.CapabilitiesResult
import de.codevoid.wmsproxy.core.LonLat
import de.codevoid.wmsproxy.core.SourceValidator
import de.codevoid.wmsproxy.core.TileLayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

sealed interface ImportState {
    data object Idle : ImportState
    data object Fetching : ImportState
    data class Loaded(val document: CapabilitiesResult.Success) : ImportState
    /** Measuring the chosen layers, one zoom level at a time. */
    data class Probing(val layer: String, val zoom: Int) : ImportState
    data class Failed(val message: String) : ImportState
    /** A tile template detected by its {z}/{x}/{y} placeholders — no fetch needed. */
    data class Template(val url: String) : ImportState

    val busy: Boolean get() = this is Fetching || this is Probing
}

/**
 * Adds sources: probing a URL to identify the service type, and measuring whatever is stored.
 *
 * The URL is tried verbatim first. When that produces nothing recognisable, a short
 * sequence of well-known variants is attempted — ArcGIS `?f=json`, WMS and WMTS
 * GetCapabilities — so a bare service base URL works as often as a full capabilities URL.
 * A URL carrying XYZ placeholders is a tile template already and skips the network.
 */
class SourcesViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow<ImportState>(ImportState.Idle)
    val state: StateFlow<ImportState> = _state.asStateFlow()

    fun fetch(url: String) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.value = ImportState.Fetching
            _state.value = withContext(Dispatchers.IO) { load(url.trim()) }
        }
    }

    fun reset() {
        _state.value = ImportState.Idle
    }

    /**
     * Measures each chosen layer, then stores it.
     *
     * Done here rather than in the dialog because it is network work that must survive a
     * recomposition, and because a layer is only worth storing once it is known where it
     * answers. Each layer is validated against what is stored at that moment, which
     * includes what this batch has already added, so two layers cannot claim the same
     * route. The whole batch runs off the main thread: the store is thread-safe, and a
     * hop back per layer bought nothing.
     */
    fun addAll(candidates: List<Pair<TileLayer, LonLat?>>) {
        if (_state.value.busy) return
        viewModelScope.launch(Dispatchers.IO) {
            for ((candidate, centre) in candidates) {
                if (SourceValidator.validate(candidate, Sources.config.value.layers) != null) continue
                val report = ZoomProbeRunner.probe(candidate, centre) { zoom ->
                    _state.value = ImportState.Probing(candidate.displayName, zoom)
                }
                Sources.add(
                    candidate.copy(
                        minZoom = report.minZoom,
                        maxZoom = report.maxZoom,
                        urlTemplate = report.urlTemplate,
                    ),
                )
            }
            _state.value = ImportState.Idle
        }
    }

    private fun load(url: String): ImportState {
        if (url.isBlank()) return ImportState.Failed("Enter a URL")
        if (isXyzTemplate(url)) return ImportState.Template(url)

        var lastFailure: ImportState = ImportState.Failed("No response from server")
        for (candidate in candidatesFor(url)) {
            val result = tryFetch(candidate)
            if (result is ImportState.Loaded) return result
            if (result is ImportState.Failed) lastFailure = result
        }
        return lastFailure
    }

    /** A tile template carries zoom, x, and y placeholders in any capitalisation. */
    private fun isXyzTemplate(url: String): Boolean =
        TileLayer.hasZoomPlaceholder(url) &&
            ("{x}" in url || "{X}" in url) &&
            ("{y}" in url || "{Y}" in url || "{-y}" in url)

    /**
     * Candidates to try, verbatim first.
     *
     * ArcGIS REST paths (`/rest/services/`, `/MapServer`) get `?f=json` before the
     * WMS/WMTS attempts because the REST JSON document is the canonical ArcGIS import
     * path. WMS before WMTS because it is more common in the wild. `?f=json` is always
     * added as a last resort even without a path hint, since some ArcGIS servers sit
     * at paths that do not follow the convention.
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

    private fun MutableList<String>.addIfNew(url: String) { if (url !in this) add(url) }

    /**
     * One HTTP round-trip. Not the tile client: its five-second budget is for something
     * the rider is waiting on mid-ride, and a capabilities document can be megabytes.
     */
    private fun tryFetch(url: String): ImportState = try {
        Upstream.capabilitiesClient.newCall(
            Request.Builder().url(url).header("User-Agent", ProxyServer.USER_AGENT).build(),
        ).execute().use { response ->
            if (!response.isSuccessful) return ImportState.Failed("Server returned HTTP ${response.code}")
            val body = response.body ?: return ImportState.Failed("Server returned an empty response")
            when (val parsed = CapabilitiesParser.parse(body.byteStream(), url)) {
                is CapabilitiesResult.Success ->
                    if (parsed.layers.isEmpty() && parsed.skipped.isEmpty())
                        ImportState.Failed("No layers in that document")
                    else
                        ImportState.Loaded(parsed)
                is CapabilitiesResult.Failure -> ImportState.Failed(parsed.message)
            }
        }
    } catch (e: Exception) {
        ImportState.Failed("${e.javaClass.simpleName}: ${e.message}")
    }
}
