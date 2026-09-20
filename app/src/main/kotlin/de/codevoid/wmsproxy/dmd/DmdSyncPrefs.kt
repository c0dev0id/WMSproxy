package de.codevoid.wmsproxy.dmd

import android.content.Context
import android.content.SharedPreferences
import de.codevoid.wmsproxy.core.DmdSync
import de.codevoid.wmsproxy.core.DmdSyncChoice
import de.codevoid.wmsproxy.core.choiceFor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    private lateinit var prefs: SharedPreferences

    private val _choices = MutableStateFlow<Map<String, DmdSyncChoice>>(emptyMap())
    val choices: StateFlow<Map<String, DmdSyncChoice>> = _choices.asStateFlow()

    /** Called once from [de.codevoid.wmsproxy.WmsProxyApp]. */
    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _choices.value = DmdSync.decodeChoices(prefs.getString(KEY, null).orEmpty())
    }

    fun choiceFor(path: String): DmdSyncChoice = _choices.value.choiceFor(path)

    fun setEnabled(path: String, enabled: Boolean) = update(path) { it.copy(enabled = enabled) }

    fun setDirect(path: String, direct: Boolean) = update(path) { it.copy(direct = direct) }

    private fun update(path: String, change: (DmdSyncChoice) -> DmdSyncChoice) {
        _choices.value = _choices.value + (path to change(choiceFor(path)))
        if (!::prefs.isInitialized) return
        runCatching { prefs.edit().putString(KEY, DmdSync.encodeChoices(_choices.value)).apply() }
    }
}
