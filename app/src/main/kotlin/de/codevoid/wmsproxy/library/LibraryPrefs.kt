package de.codevoid.wmsproxy.library

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The library filter state, persisted so a region or category choice survives tab
 * switches and app restarts. Text search is ephemeral UI state and lives in the
 * composable.
 *
 * Follows the same pattern as [de.codevoid.wmsproxy.dmd.DmdSyncPrefs]: initialised once
 * from [de.codevoid.wmsproxy.WmsProxyApp], read via [StateFlow] in the composable.
 */
object LibraryPrefs {

    private const val PREFS = "library-filter"
    private const val KEY_REGION = "region"
    private const val KEY_CATEGORY = "category"

    private lateinit var prefs: SharedPreferences

    private val _region = MutableStateFlow<String?>(null)
    val region: StateFlow<String?> = _region.asStateFlow()

    private val _category = MutableStateFlow<String?>(null)
    val category: StateFlow<String?> = _category.asStateFlow()

    /** Called once from [de.codevoid.wmsproxy.WmsProxyApp]. */
    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _region.value = prefs.getString(KEY_REGION, null)
        _category.value = prefs.getString(KEY_CATEGORY, null)
    }

    fun setRegion(value: String?) {
        _region.value = value
        set(KEY_REGION, value)
    }

    fun setCategory(value: String?) {
        _category.value = value
        set(KEY_CATEGORY, value)
    }

    fun clear() {
        setRegion(null)
        setCategory(null)
    }

    private fun set(key: String, value: String?) {
        if (!::prefs.isInitialized) return
        runCatching {
            prefs.edit().also { e ->
                if (value != null) e.putString(key, value) else e.remove(key)
            }.apply()
        }
    }
}
