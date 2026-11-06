package de.codevoid.wmsproxy.dmd

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * What the sync remembers between runs: whether it is a full sync, and the id DMD Hub
 * assigned each layer the last time it was pushed.
 *
 * The id registry maps [de.codevoid.wmsproxy.core.TileLayer.path] to the server-minted
 * id the Hub returned when the layer was first imported. It is the only way to reach a
 * previously-pushed layer: the Hub refuses a push of any id it has tombstoned from an
 * earlier DELETE, so the sync cannot regenerate ids on its own. The sync writes through
 * incrementally after each push, and bulk-prunes once up front.
 */
object DmdSyncPrefs {

    private const val PREFS = "dmd-sync"
    private const val FULL_SYNC_KEY = "full_sync"
    private const val LAYER_IDS_KEY = "layer_ids"

    /** The per-source choices earlier builds kept here; nothing reads them now. */
    private const val OLD_CHOICES_KEY = "choices"

    private val json = Json { ignoreUnknownKeys = true }
    private val idMapSerializer = MapSerializer(String.serializer(), String.serializer())

    private lateinit var prefs: SharedPreferences

    private val _fullSync = MutableStateFlow(false)
    val fullSync: StateFlow<Boolean> = _fullSync.asStateFlow()

    private var ids: Map<String, String> = emptyMap()
    val layerIds: Map<String, String> get() = ids

    /** Called once from [de.codevoid.wmsproxy.WmsProxyApp]. */
    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _fullSync.value = prefs.getBoolean(FULL_SYNC_KEY, false)
        ids = readLayerIds()
        if (prefs.contains(OLD_CHOICES_KEY)) runCatching { prefs.edit().remove(OLD_CHOICES_KEY).apply() }
    }

    fun setFullSync(enabled: Boolean) {
        _fullSync.value = enabled
        if (!::prefs.isInitialized) return
        runCatching { prefs.edit().putBoolean(FULL_SYNC_KEY, enabled).apply() }
    }

    /** Bulk-replaces the registry. The sync uses this to prune orphans up front. */
    fun setLayerIds(newIds: Map<String, String>) {
        if (ids == newIds) return
        ids = newIds
        persistIds()
    }

    /** Records one path→id mapping, write-through. */
    fun putLayerId(path: String, id: String) {
        if (ids[path] == id) return
        ids = ids + (path to id)
        persistIds()
    }

    /** Drops one path from the registry, write-through. */
    fun removeLayerId(path: String) {
        if (path !in ids) return
        ids = ids - path
        persistIds()
    }

    private fun persistIds() {
        if (!::prefs.isInitialized) return
        runCatching { prefs.edit().putString(LAYER_IDS_KEY, json.encodeToString(idMapSerializer, ids)).apply() }
    }

    private fun readLayerIds(): Map<String, String> {
        val raw = prefs.getString(LAYER_IDS_KEY, null) ?: return emptyMap()
        return runCatching { json.decodeFromString(idMapSerializer, raw) }.getOrDefault(emptyMap())
    }
}
