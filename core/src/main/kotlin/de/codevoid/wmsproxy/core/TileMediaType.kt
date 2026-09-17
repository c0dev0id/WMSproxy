package de.codevoid.wmsproxy.core

/**
 * Decides whether an upstream response is an image this proxy will relay.
 *
 * The line is drawn at *raster image or not*, and deliberately no finer. Anything that
 * arrives as a raster image is handed to the client byte for byte, including formats the
 * client may well fail to decode. Judging which of those it can actually draw would mean
 * maintaining a model of the client's decoder, guessing wrong in both directions, and
 * refusing tiles that would have rendered.
 *
 * So the client decides. When a format turns out not to work, that is the point at which
 * a rule for that one format is added — on evidence, not on an assumption. Over time the
 * set of what works is learned rather than predicted.
 *
 * What stays refused is everything that is not an image at all: vector tiles, GeoJSON,
 * UTFGrid, PDF, KML, an HTML error page returned as 200, a `ServiceExceptionReport`. One
 * GeoServer surveyed for this project offers all of those from the endpoint that serves
 * PNG, and its tile caches serve vector tiles exclusively. Relaying them would be the
 * blank-tile mistake by another route — the client caches what it is handed, so bytes it
 * can never draw become a permanent hole in the map. They are not tiles in an awkward
 * wrapper; making them usable would mean rendering them, and this proxy renders nothing.
 */
object TileMediaType {

    /**
     * Image media types that are not raster. `svg+xml` is a vector document: passing it
     * on cannot come good later, because the fix would be to render it.
     */
    private val NOT_RASTER = setOf("svg+xml")

    /**
     * True when [contentType] names a raster image.
     *
     * Parameters are ignored, so GeoServer's `image/png; mode=8bit` is accepted. A null
     * or blank type is refused: a response that does not say what it is cannot be shown
     * to be an image, and guessing is how undrawable bytes reach the cache.
     */
    fun isRasterImage(contentType: String?): Boolean {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return false
        if (!type.startsWith("image/")) return false
        val subtype = type.substringAfter('/')
        return subtype.isNotEmpty() && subtype !in NOT_RASTER
    }
}
