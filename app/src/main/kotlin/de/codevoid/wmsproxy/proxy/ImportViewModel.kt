package de.codevoid.wmsproxy.proxy

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.codevoid.wmsproxy.core.CapabilitiesParser
import de.codevoid.wmsproxy.core.CapabilitiesResult
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
    data class Failed(val message: String) : ImportState

    val busy: Boolean get() = this is Fetching
}

class ImportViewModel(app: Application) : AndroidViewModel(app) {

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

    private fun load(raw: String): ImportState {
        if (raw.isBlank()) return ImportState.Failed("Enter a capabilities URL")
        val url = runCatching { capabilitiesUrl(raw) }
            .getOrElse { return ImportState.Failed("That is not a usable URL") }

        return try {
            Upstream.client.newCall(
                Request.Builder().url(url).header("User-Agent", ProxyServer.USER_AGENT).build(),
            ).execute().use { response ->
                if (!response.isSuccessful) {
                    return ImportState.Failed("Server returned HTTP ${response.code}")
                }
                // Capabilities documents run to hundreds of kilobytes, which is fine to
                // hold once. It is parsed and discarded; only the chosen layers are kept.
                val body = response.body?.string()
                    ?: return ImportState.Failed("Server returned an empty response")

                when (val parsed = CapabilitiesParser.parse(body, url)) {
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

    /**
     * Accepts what a user is likely to paste.
     *
     * People copy the service endpoint far more often than a full capabilities request,
     * so the query is completed when it is missing rather than refused. An existing
     * `REQUEST=` is left alone: it may carry vendor parameters that matter, and second
     * guessing it would break the very documents that need them.
     */
    internal fun capabilitiesUrl(raw: String): String {
        val url = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
        if (url.contains("REQUEST=", ignoreCase = true)) return url

        // A WMTS endpoint is conventionally spelled in the path; nothing else in the URL
        // distinguishes the two services before the document has been read.
        val service = if (url.contains("wmts", ignoreCase = true)) "WMTS" else "WMS"
        val separator = when {
            url.endsWith("?") || url.endsWith("&") -> ""
            url.contains("?") -> "&"
            else -> "?"
        }
        return "$url${separator}SERVICE=$service&REQUEST=GetCapabilities"
    }
}
