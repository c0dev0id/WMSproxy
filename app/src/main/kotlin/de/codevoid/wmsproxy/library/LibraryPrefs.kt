package de.codevoid.wmsproxy.library

import android.content.Context
import android.content.SharedPreferences
import de.codevoid.wmsproxy.core.CatalogFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The list's filter, persisted so the chips survive a restart. The search text is
 * typed for the moment and lives in the screen; it starts blank on every launch.
 *
 * Follows the same pattern as [de.codevoid.wmsproxy.dmd.DmdSyncPrefs]: initialised once
 * from [de.codevoid.wmsproxy.WmsProxyApp], read via [StateFlow] in the view model.
 */
object LibraryPrefs {

    private const val PREFS = "library-filter"
    private const val KEY_REGION = "region"
    private const val KEY_CATEGORY = "category"
    private const val KEY_FAVORITES = "favorites"
    private const val KEY_LOADED = "loaded"

    private lateinit var prefs: SharedPreferences

    private val _filter = MutableStateFlow(CatalogFilter())
    val filter: StateFlow<CatalogFilter> = _filter.asStateFlow()

    /** Called once from [de.codevoid.wmsproxy.WmsProxyApp]. */
    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _filter.value = CatalogFilter(
            favorites = prefs.getBoolean(KEY_FAVORITES, false),
            loaded = prefs.getBoolean(KEY_LOADED, false),
            region = prefs.getString(KEY_REGION, null),
            category = prefs.getString(KEY_CATEGORY, null),
        )
    }

    fun setFilter(change: (CatalogFilter) -> CatalogFilter) {
        val next = change(_filter.value)
        _filter.value = next
        if (!::prefs.isInitialized) return
        runCatching {
            prefs.edit().also { e ->
                if (next.region != null) e.putString(KEY_REGION, next.region) else e.remove(KEY_REGION)
                if (next.category != null) e.putString(KEY_CATEGORY, next.category) else e.remove(KEY_CATEGORY)
                e.putBoolean(KEY_FAVORITES, next.favorites)
                e.putBoolean(KEY_LOADED, next.loaded)
            }.apply()
        }
    }
}
