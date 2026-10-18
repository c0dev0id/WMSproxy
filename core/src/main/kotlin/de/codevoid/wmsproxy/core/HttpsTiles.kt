package de.codevoid.wmsproxy.core

/**
 * Whether a server whose document publishes its tiles over plain HTTP also serves them
 * over HTTPS. Asked once per document, with one tile, when the document is read; the
 * answer stands for every layer on the sampled host. The request itself is the caller's;
 * this only says what to ask and what to store on a yes.
 */
object HttpsTiles {

    private const val HTTP = "http://"
    private const val HTTPS = "https://"
    private const val SAMPLE_ZOOM = 8
    private const val SAMPLE_SOURCE = "sample"

    /**
     * The address of one tile over HTTPS, taken from the first layer whose template is
     * plain HTTP and aimed at that layer's extent; null when no layer is plain HTTP.
     */
    fun sample(document: CapabilitiesResult.Success): String? {
        val plain = firstPlain(document) ?: return null
        val secure = plain.toTileLayer(SAMPLE_SOURCE).copy(urlTemplate = secured(plain.template))
        val centre = plain.centre
        return secure.urlFor(TileMath.tileFor(centre?.longitude ?: 0.0, centre?.latitude ?: 0.0, SAMPLE_ZOOM))
    }

    /** The same document with every plain-HTTP template on the sampled host written as HTTPS. */
    fun secureForm(document: CapabilitiesResult.Success): CapabilitiesResult.Success {
        val host = firstPlain(document)?.let { Urls.hostOf(it.template) } ?: return document
        return document.copy(
            layers = document.layers.map { layer ->
                if (isPlain(layer.template) && Urls.hostOf(layer.template) == host) {
                    layer.copy(template = secured(layer.template))
                } else {
                    layer
                }
            },
        )
    }

    private fun firstPlain(document: CapabilitiesResult.Success): DiscoveredLayer? =
        document.layers.firstOrNull { isPlain(it.template) }

    private fun isPlain(template: String): Boolean = template.startsWith(HTTP, ignoreCase = true)

    private fun secured(template: String): String = HTTPS + template.substring(HTTP.length)
}
