package de.codevoid.wmsproxy.proxy

import android.content.Context
import de.codevoid.wmsproxy.core.SourceCodec
import de.codevoid.wmsproxy.core.SourceConfig
import de.codevoid.wmsproxy.core.TileLayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * What a fresh install starts with.
 *
 * Placeholders, not defaults worth defending: both are courtesy hosts that answer to
 * anyone and could stop at any time, as the previous built-in did. They exist so the app
 * does something the moment it is installed, and so the two interesting routes — a plain
 * source, and one with a layer segment and `{s}` rotation — are exercised without the
 * user having to type them in first.
 */
object BuiltInSources {
    val all: List<TileLayer> = listOf(
        TileLayer(
            source = "osm",
            title = "OpenStreetMap",
            urlTemplate = "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
        ),
        TileLayer(
            source = "carto",
            layer = "light",
            title = "CARTO Positron",
            urlTemplate = "https://{s}.basemaps.cartocdn.com/light_all/{z}/{x}/{y}.png",
            subdomains = listOf("a", "b", "c", "d"),
        ),
    )
}

/**
 * The configured sources, held for the life of the process and written through to disk.
 *
 * A single object rather than an injected dependency because the server runs on its own
 * threads inside a service while the editor runs in the activity, and both need the same
 * list without either owning the other. JSON in the app files dir, no database and no
 * migrations — the file is small, and pre-1.0 a schema change is cheaper to absorb by
 * letting an unreadable field fall back to its default.
 */
object Sources {

    private lateinit var file: File

    private val _layers = MutableStateFlow(BuiltInSources.all)

    /** The live list. The server reads it per request, so an edit takes effect at once. */
    val layers: StateFlow<List<TileLayer>> = _layers.asStateFlow()

    /** Called once from [de.codevoid.wmsproxy.WmsProxyApp]. */
    fun init(context: Context) {
        file = File(context.filesDir, FILE_NAME)
        if (file.exists()) {
            // An empty stored list is a real state — the user deleted everything — and
            // must not be mistaken for a missing file and refilled with the built-ins.
            _layers.value = SourceCodec.decode(runCatching { file.readText() }.getOrDefault("")).layers
        } else {
            persist()
        }
    }

    fun add(layer: TileLayer) = mutate { it + layer }

    fun replace(old: TileLayer, new: TileLayer) = mutate { list ->
        list.map { if (it == old) new else it }
    }

    fun remove(layer: TileLayer) = mutate { list -> list.filterNot { it == layer } }

    /** Puts the starting set back, for when experimenting has left nothing that works. */
    fun restoreDefaults() = mutate { BuiltInSources.all }

    private fun mutate(change: (List<TileLayer>) -> List<TileLayer>) {
        _layers.value = change(_layers.value)
        persist()
    }

    private fun persist() {
        if (!::file.isInitialized) return
        // A failed write loses the edit on next launch but must not take the app with it;
        // the list in memory is already correct and the user can retry.
        runCatching { file.writeText(SourceCodec.encode(SourceConfig(_layers.value))) }
    }

    private const val FILE_NAME = "sources.json"
}
