package de.codevoid.wmsproxy.dmd

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * How one source should be pushed to DMD.
 *
 * [enabled] is inclusion, not a DMD flag: DMD ignores the `enabled` field it is sent and
 * tracks on/off in a device-local pref of its own, so the only way to turn a layer off
 * over the wire is to leave it out of the pushed set. [direct] sends the upstream URL
 * instead of the proxy's, for sources DMD can serve without the proxy — it is honoured
 * only when the source is actually compatible, and ignored otherwise.
 */
@Serializable
data class DmdSyncChoice(val enabled: Boolean = true, val direct: Boolean = false)

/**
 * The per-source sync choices, kept out of `sources.json` so a proxy source stays a pure
 * proxy concept and editing a source does not disturb them.
 *
 * Keyed by source path (`source[/layer]`), which is stable across edits that keep the
 * name. An absent entry is the default choice, so a new source syncs through the proxy
 * without the user touching anything. A stale key for a deleted source simply never
 * matches; it is not worth pruning.
 */
object DmdSyncPrefs {

    private const val PREFS = "dmd-sync"
    private const val KEY = "choices"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mapSerializer = MapSerializer(String.serializer(), DmdSyncChoice.serializer())

    private lateinit var prefs: android.content.SharedPreferences

    private val _choices = MutableStateFlow<Map<String, DmdSyncChoice>>(emptyMap())
    val choices: StateFlow<Map<String, DmdSyncChoice>> = _choices.asStateFlow()

    /** Called once from [de.codevoid.wmsproxy.WmsProxyApp]. */
    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _choices.value = load()
    }

    fun choiceFor(path: String): DmdSyncChoice = _choices.value[path] ?: DmdSyncChoice()

    fun setEnabled(path: String, enabled: Boolean) = update(path) { it.copy(enabled = enabled) }

    fun setDirect(path: String, direct: Boolean) = update(path) { it.copy(direct = direct) }

    private fun update(path: String, change: (DmdSyncChoice) -> DmdSyncChoice) {
        _choices.value = _choices.value + (path to change(choiceFor(path)))
        persist()
    }

    private fun load(): Map<String, DmdSyncChoice> = runCatching {
        prefs.getString(KEY, null)?.let { json.decodeFromString(mapSerializer, it) }
    }.getOrNull() ?: emptyMap()

    private fun persist() {
        if (!::prefs.isInitialized) return
        runCatching {
            prefs.edit().putString(KEY, json.encodeToString(mapSerializer, _choices.value)).apply()
        }
    }
}
