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

    val busy: Boolean get() = this is Fetching || this is Probing
}

/**
 * Adds sources: fetching a capabilities document, and measuring whatever is stored.
 *
 * **The URL is used exactly as typed.** An earlier version appended
 * `SERVICE=…&REQUEST=GetCapabilities` when it looked absent, which is the sort of
 * convenience that costs more than it saves: the service kind cannot be told from a path
 * — `/gwc/service/wmts` is a convention, not a rule — and a real endpoint may need
 * `acceptVersions`, a `map=` file, or another vendor parameter that only the person
 * pasting it knows about. Rewriting their URL would then fail against a server that was
 * working, and the message would blame the server. Whoever has the capabilities URL has
 * it in full.
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

    /** [url] is sent exactly as given; see the note on the class. */
    private fun load(url: String): ImportState {
        if (url.isBlank()) return ImportState.Failed("Enter a capabilities URL")

        return try {
            // Not the tile client: its five-second budget is for something the rider is
            // waiting on mid-ride, and a capabilities document can be megabytes. NASA
            // GIBS publishes 5.8 MB, which the tile budget cut off every time.
            Upstream.capabilitiesClient.newCall(
                Request.Builder().url(url).header("User-Agent", ProxyServer.USER_AGENT).build(),
            ).execute().use { response ->
                if (!response.isSuccessful) {
                    return ImportState.Failed("Server returned HTTP ${response.code}")
                }
                // Streamed into the parser rather than read into a String first, which
                // for a document that size would hold it three times over before the
                // tree is built. Parsed and discarded; only the chosen layers are kept.
                val body = response.body
                    ?: return ImportState.Failed("Server returned an empty response")

                when (val parsed = CapabilitiesParser.parse(body.byteStream(), url)) {
                    is CapabilitiesResult.Success ->
                        if (parsed.layers.isEmpty() && parsed.skipped.isEmpty()) {
                            ImportState.Failed("No layers in that document")
                        } else {
                            ImportState.Loaded(parsed)
                        }

                    is CapabilitiesResult.Failure -> ImportState.Failed(parsed.message)
                }
            }
        } catch (e: Exception) {
            ImportState.Failed("${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
