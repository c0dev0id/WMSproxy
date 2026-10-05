package de.codevoid.wmsproxy.library

import android.content.Context
import android.content.SharedPreferences
import de.codevoid.wmsproxy.core.CatalogFilter
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
    private const val KEY_FAVORITES = "favorites"
    private const val KEY_LOADED = "loaded"

    private lateinit var prefs: SharedPreferences

    /**
     * The list's whole filter, persisted field by field except the search text, which is
     * typed for the moment and starts blank on every launch.
     */
    private val _filter = MutableStateFlow(CatalogFilter())
    val filter: StateFlow<CatalogFilter> = _filter.asStateFlow()

    private val _region = MutableStateFlow<String?>(null)
    val region: StateFlow<String?> = _region.asStateFlow()

    private val _category = MutableStateFlow<String?>(null)
    val category: StateFlow<String?> = _category.asStateFlow()

    /** Called once from [de.codevoid.wmsproxy.WmsProxyApp]. */
    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _region.value = prefs.getString(KEY_REGION, null)
        _category.value = prefs.getString(KEY_CATEGORY, null)
        _filter.value = CatalogFilter(
            favorites = prefs.getBoolean(KEY_FAVORITES, false),
            loaded = prefs.getBoolean(KEY_LOADED, false),
            region = _region.value,
            category = _category.value,
        )
    }

    fun setFilter(change: (CatalogFilter) -> CatalogFilter) {
        val next = change(_filter.value)
        _filter.value = next
        _region.value = next.region
        _category.value = next.category
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
