package de.codevoid.wmsproxy.catalog

import android.content.Context
import de.codevoid.wmsproxy.core.CatalogCodec
import de.codevoid.wmsproxy.core.UserCatalog
import de.codevoid.wmsproxy.writeAtomically
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

/**
 * The user's own services and shortlist: a small file read at start, like the sources,
 * and kept apart from the cache so a re-read never rewrites a setting.
 */
object UserServices {

    private const val FILE_NAME = "services.json"

    private lateinit var file: File

    private val _state = MutableStateFlow(UserCatalog())
    val state: StateFlow<UserCatalog> = _state.asStateFlow()

    /** Called once from [de.codevoid.wmsproxy.WmsProxyApp]. */
    fun init(context: Context) {
        file = File(context.filesDir, FILE_NAME)
        if (file.exists()) {
            _state.value = CatalogCodec.decodeUser(runCatching { file.readText() }.getOrDefault(""))
        }
    }

    fun toggleFavorite(key: String) = mutate { it.toggleFavorite(key) }

    fun addOwn(url: String) = mutate { it.withOwn(url, System.currentTimeMillis()) }

    fun removeOwn(url: String) = mutate { it.withoutOwn(url) }

    private fun mutate(change: (UserCatalog) -> UserCatalog) {
        _state.update(change)
        persist()
    }

    @Synchronized
    private fun persist() {
        if (!::file.isInitialized) return
        runCatching { file.writeAtomically(CatalogCodec.encodeUser(_state.value).toByteArray()) }
    }
}
