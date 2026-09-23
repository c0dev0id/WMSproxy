package de.codevoid.wmsproxy.proxy

import android.content.Context
import de.codevoid.wmsproxy.core.SourceCodec
import de.codevoid.wmsproxy.core.SourceConfig
import de.codevoid.wmsproxy.core.TileLayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

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

    // Empty on a fresh install: the Library tab is now the way in, so the app no longer
    // ships placeholder sources on courtesy hosts that could stop answering.
    private val _config = MutableStateFlow(SourceConfig())

    /**
     * The whole configuration, live.
     *
     * One flow rather than one per field: deriving them would need a scope to collect in,
     * and the only scope available to an object with no lifecycle is GlobalScope. A
     * reader that wants the layers reads `config.value.layers`.
     */
    val config: StateFlow<SourceConfig> = _config.asStateFlow()

    /** Called once from [de.codevoid.wmsproxy.WmsProxyApp]. */
    fun init(context: Context) {
        file = File(context.filesDir, FILE_NAME)
        if (file.exists()) {
            // An empty stored list is a real state — the user deleted everything — and
            // must not be mistaken for a missing file and refilled with the built-ins.
            _config.value = SourceCodec.decode(runCatching { file.readText() }.getOrDefault(""))
        } else {
            persist()
        }
    }

    fun add(layer: TileLayer) = mutate { it + layer }

    fun replace(old: TileLayer, new: TileLayer) = mutate { list ->
        list.map { if (it == old) new else it }
    }

    fun remove(layer: TileLayer) = mutate { list -> list.filterNot { it == layer } }

    /** Records that the user wants the proxy running, or no longer does. */
    fun setStartOnBoot(value: Boolean) {
        if (_config.value.startOnBoot == value) return
        _config.value = _config.value.copy(startOnBoot = value)
        persist()
    }

    // An atomic update rather than a read-modify-write: an import batch adds from an IO
    // thread while the editor may remove on the main one, and two plain assignments could
    // lose one of them.
    private fun mutate(change: (List<TileLayer>) -> List<TileLayer>) {
        _config.update { it.copy(layers = change(it.layers)) }
        persist()
    }

    // Serialised so that two writers cannot interleave partial files; the last one in
    // writes the newest state, since each encodes whatever the flow holds when it runs.
    @Synchronized
    private fun persist() {
        if (!::file.isInitialized) return
        // A failed write loses the edit on next launch but must not take the app with it;
        // the state in memory is already correct and the user can retry.
        runCatching { file.writeText(SourceCodec.encode(_config.value)) }
    }

    private const val FILE_NAME = "sources.json"
}
