package de.codevoid.wmsproxy.core

import java.util.Locale

/**
 * Finds the library entry an address typed by hand already reaches, however it is
 * spelled.
 *
 * The same service turns up under many addresses: `http` or `https`, host and path in any
 * case, the capabilities query in any order and with or without `VERSION`, and an ArcGIS
 * service as its REST description, its WMS or its WMTS. Each is reduced to one key; two
 * addresses with the same key are the same service. The key errs towards keeping
 * services apart, so anything that may pick a different map — another path, a `map=` or
 * `layers=` parameter — still tells two addresses apart.
 */
object LibraryMatch {

    /** Parameters every capabilities request carries, which say nothing about the service. */
    private val REQUEST_PARAMETERS = setOf("service", "request", "version", "acceptversions", "f")

    /** How an ArcGIS service publishes the same map other than as its REST description. */
    private val ARCGIS_SUFFIXES = listOf("/wmts/1.0.0/wmtscapabilities.xml", "/wmts/1.0.0", "/wmts", "/wmsserver")

    /** The entry with this exact address, else the one that reaches the same service. */
    fun find(url: String, entries: List<LibraryEntry>): LibraryEntry? {
        entries.firstOrNull { it.url == url }?.let { return it }
        val key = keyOf(url)
        return entries.firstOrNull { keyOf(it.url) == key }
    }

    internal fun keyOf(url: String): String {
        val (template, _) = XyzTemplate.normalise(Urls.fromInput(url))
        val bare = template.substringAfter("://", template)
        val path = bare.substringBefore('?').lowercase(Locale.ROOT).trimEnd('/')
            .replace("/arcgis/services/", "/arcgis/rest/services/")
            .let { p -> ARCGIS_SUFFIXES.firstOrNull { p.endsWith(it) }?.let { p.removeSuffix(it) } ?: p }
            .let { p -> if (p.endsWith("/wms")) p.removeSuffix("/wms") + "/ows" else p }
        val query = bare.substringAfter('?', "").split('&')
            .filter { it.isNotBlank() }
            .map { it.substringBefore('=').lowercase(Locale.ROOT) to it.substringAfter('=', "") }
            .filter { (name, _) -> name !in REQUEST_PARAMETERS }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .joinToString("&") { (name, value) -> "$name=$value" }
        return if (query.isEmpty()) path else "$path?$query"
    }
}
