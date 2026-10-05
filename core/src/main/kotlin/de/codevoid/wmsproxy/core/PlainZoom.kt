package de.codevoid.wmsproxy.core

/**
 * Whether a server that names its zoom levels `00`, `01` … also answers `0`, `1` …
 *
 * The padded form is the one thing in a template DMD cannot substitute, so a layer
 * carrying it goes through the proxy. Many such servers take the plain form just the
 * same (TopPlusOpen does), and then the layer can go direct. The question is asked once
 * per document, with one tile, when the document is read: every layer of a service sits
 * on the same server and matrix set, so one answer stands for all of them. The request
 * itself is the caller's; this only says what to ask and what to store on a yes.
 */
object PlainZoom {

    /** Deep enough to have a tile anywhere, shallow enough that `08` and `8` differ. */
    const val SAMPLE_ZOOM = 8

    /**
     * The address of one tile in plain-zoom form, taken from the first layer whose
     * template pads its zoom and aimed at that layer's extent; null when no layer does.
     */
    fun sample(document: CapabilitiesResult.Success): String? {
        val padded = document.layers.firstOrNull { TileLayer.PADDED_ZOOM.containsMatchIn(it.template) } ?: return null
        val plain = padded.toTileLayer(SAMPLE_SOURCE).withPlainZoom() ?: return null
        val centre = padded.centre
        return plain.urlFor(TileMath.tileFor(centre?.longitude ?: 0.0, centre?.latitude ?: 0.0, SAMPLE_ZOOM))
    }

    /** The same document with every padded zoom written as `{z}`, for a server that said yes. */
    fun plainForm(document: CapabilitiesResult.Success): CapabilitiesResult.Success =
        document.copy(
            layers = document.layers.map { layer ->
                layer.copy(template = TileLayer.PADDED_ZOOM.replace(layer.template, "{z}"))
            },
        )

    private const val SAMPLE_SOURCE = "sample"
}
