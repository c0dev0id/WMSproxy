package de.codevoid.wmsproxy.core

/**
 * The addresses to try for a service URL, verbatim first, so a bare service address
 * works as often as a full capabilities one. ArcGIS REST paths get `?f=json` before the
 * WMS and WMTS attempts, because the REST description is the canonical ArcGIS form; WMS
 * before WMTS because it is more common; `?f=json` last for every address, since some
 * ArcGIS servers sit at paths that do not follow the convention. Shared by the app and
 * the catalogue tool, so both read a service the same way.
 */
object CapabilitiesCandidates {

    fun candidatesFor(url: String): List<String> {
        val upper = url.uppercase()
        val base = url.substringBefore('?')
        val candidates = mutableListOf(url)

        if ("/rest/services/" in url || "/mapserver" in url.lowercase()) {
            candidates.addIfNew("$base?f=json")
        }
        if ("REQUEST=GETCAPABILITIES" !in upper) {
            if ("SERVICE=" !in upper) {
                candidates.addIfNew(appendQuery(url, "SERVICE=WMS&REQUEST=GetCapabilities"))
                candidates.addIfNew(appendQuery(url, "SERVICE=WMTS&REQUEST=GetCapabilities"))
            } else {
                candidates.addIfNew(appendQuery(url, "REQUEST=GetCapabilities"))
            }
        }
        candidates.addIfNew("$base?f=json")
        return candidates
    }

    private fun appendQuery(url: String, params: String) =
        if ('?' in url) "$url&$params" else "$url?$params"

    private fun MutableList<String>.addIfNew(url: String) {
        if (url !in this) add(url)
    }
}
