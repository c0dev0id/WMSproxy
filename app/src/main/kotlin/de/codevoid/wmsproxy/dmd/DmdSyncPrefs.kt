package de.codevoid.wmsproxy.dmd

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the sync remembers between runs: only whether it is a full one. Which layers go
 * to DMD is what is loaded, and whether each goes direct is what its address allows, so
 * neither is a setting any more.
 */
object DmdSyncPrefs {

    private const val PREFS = "dmd-sync"
    private const val FULL_SYNC_KEY = "full_sync"

    /** The per-source choices earlier builds kept here; nothing reads them now. */
    private const val OLD_CHOICES_KEY = "choices"

    private lateinit var prefs: SharedPreferences

    private val _fullSync = MutableStateFlow(false)
    val fullSync: StateFlow<Boolean> = _fullSync.asStateFlow()

    /** Called once from [de.codevoid.wmsproxy.WmsProxyApp]. */
    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _fullSync.value = prefs.getBoolean(FULL_SYNC_KEY, false)
        if (prefs.contains(OLD_CHOICES_KEY)) runCatching { prefs.edit().remove(OLD_CHOICES_KEY).apply() }
    }

    fun setFullSync(enabled: Boolean) {
        _fullSync.value = enabled
        if (!::prefs.isInitialized) return
        runCatching { prefs.edit().putBoolean(FULL_SYNC_KEY, enabled).apply() }
    }
}
