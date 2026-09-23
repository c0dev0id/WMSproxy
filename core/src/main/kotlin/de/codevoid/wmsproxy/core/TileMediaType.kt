package de.codevoid.wmsproxy.core

/**
 * Decides whether an upstream response is an image this proxy will relay.
 *
 * One line, drawn at raster image or not. Any raster type goes through byte for byte,
 * including formats the client may fail to decode: judging which ones it can draw would
 * mean modelling its decoder here on guesswork, refusing tiles that would have rendered.
 * When a format turns out not to work, a rule for that one format is added on evidence.
 *
 * Anything that is not an image is refused. A tile request answered with features, a
 * document or an error page is a failure however it is dressed, and the client caches
 * what it is handed, so relaying it would leave a permanent hole in the map. None of it
 * is a tile in an awkward wrapper — making it usable would mean rendering it.
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
