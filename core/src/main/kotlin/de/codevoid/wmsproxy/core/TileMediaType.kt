package de.codevoid.wmsproxy.core

/**
 * Decides whether an upstream response is something the client can draw as a tile.
 *
 * This is an allowlist, and deliberately so. The obvious shape is a denylist — refuse
 * HTML, refuse XML — but it fails open: anything unanticipated is relayed. Real servers
 * offer far more than raster. One GeoServer surveyed for this project advertises
 * GeoJSON, TopoJSON, UTFGrid, PDF, KML, KMZ, SVG, GeoTIFF and Mapbox vector tiles from
 * the same endpoint as PNG, and its tile caches serve *only* vector tiles. None of that
 * is an image a raster client can draw, and none of it contains the string `html`.
 *
 * Relaying it would be the blank-tile mistake wearing a different hat: the client caches
 * what it is given, so undrawable bytes accepted once become a permanent hole in the map
 * exactly like a placeholder would. Refusing is the honest answer, and the request log
 * records the type that was refused so the cause is visible rather than guessed at.
 *
 * Vector tiles are not an oversight. Serving them would mean rendering them, and this
 * proxy does not decode, draw or re-encode anything.
 */
object TileMediaType {

    /**
     * Subtypes a tile client can decode. `svg+xml` is absent because it is vector, and
     * `tiff`/`geotiff` because Android decodes neither — both would arrive as an image
     * media type and still be undrawable.
     */
    private val DRAWABLE = setOf("png", "jpeg", "jpg", "webp", "gif", "bmp")

    /**
     * True when [contentType] names a raster image this proxy is willing to relay.
     *
     * Parameters are ignored, so GeoServer's `image/png; mode=8bit` is accepted as PNG.
     * A null or blank type is refused: a response that does not say what it is cannot be
     * shown to be a tile, and guessing is how undrawable bytes reach the cache.
     */
    fun isDrawableTile(contentType: String?): Boolean {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return false
        val subtype = type.substringAfter('/', missingDelimiterValue = "")
        return type.startsWith("image/") && subtype in DRAWABLE
    }
}
