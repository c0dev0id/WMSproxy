package de.codevoid.wmsproxy.core

/**
 * A bounding box in the units of some CRS. Axis order is always x-then-y (easting/
 * longitude first) regardless of what the wire format used — callers normalize WMS
 * 1.3.0's lat/lon ordering before constructing one of these, so nothing downstream has
 * to remember which version a request arrived as.
 */
data class Bbox(
    val minX: Double,
    val minY: Double,
    val maxX: Double,
    val maxY: Double,
)

/** A tile in the standard XYZ scheme. */
data class TileRef(val zoom: Int, val x: Int, val y: Int)

/**
 * WebMercator (EPSG:3857) tile arithmetic.
 *
 * This is the only geometry in the project. There is deliberately no coordinate
 * transformation anywhere: a CRS an upstream cannot serve is an error, not a
 * conversion, because rewriting a bbox between projections returns an image rendered in
 * one projection but labelled as another — an error that ranges from millimetres to
 * hundreds of metres with no way for the rider to tell which they got.
 */
object TileMath {

    /** Half the circumference of the WebMercator world, in metres. */
    const val ORIGIN_SHIFT: Double = 20037508.342789244

    /** The tile size every common WebMercator grid uses. */
    const val DEFAULT_TILE_SIZE: Int = 256

    private const val WORLD_SIZE: Double = 2 * ORIGIN_SHIFT

    /** Number of tiles along one axis at [zoom]. */
    fun tilesPerAxis(zoom: Int): Int {
        require(zoom in 0..30) { "zoom out of range: $zoom" }
        return 1 shl zoom
    }

    /** The exact EPSG:3857 extent of tile [x]/[y] at [zoom], in the standard XYZ scheme. */
    fun tileBbox(zoom: Int, x: Int, y: Int): Bbox {
        val n = tilesPerAxis(zoom)
        require(x in 0 until n) { "x out of range for zoom $zoom: $x" }
        require(y in 0 until n) { "y out of range for zoom $zoom: $y" }

        val tileSpan = WORLD_SIZE / n
        val minX = -ORIGIN_SHIFT + x * tileSpan
        val maxY = ORIGIN_SHIFT - y * tileSpan
        return Bbox(
            minX = minX,
            minY = maxY - tileSpan,
            maxX = minX + tileSpan,
            maxY = maxY,
        )
    }

    /**
     * Converts between the XYZ row order (y increasing southward, what DMD2 and most
     * tile servers use) and the OSGeo TMS order (y increasing northward). The mapping is
     * its own inverse, so one function serves both directions.
     */
    fun flipY(zoom: Int, y: Int): Int {
        val n = tilesPerAxis(zoom)
        require(y in 0 until n) { "y out of range for zoom $zoom: $y" }
        return n - 1 - y
    }

    /**
     * Solves a WMS GetMap extent back to the tile it is, or null when it is not one.
     *
     * A tile-pyramid upstream has no image for an arbitrary extent, so a GetMap can only
     * be answered by rewriting when it lands exactly on a tile. This is that test, and
     * it is pure integer-ish arithmetic — no pixels are involved either way.
     *
     * Returns null when the extent is not square, spans a non-integral zoom, does not
     * start on a tile boundary, or was asked for at a pixel size other than the grid's.
     */
    fun solveTile(
        bbox: Bbox,
        widthPx: Int,
        heightPx: Int,
        tileSize: Int = DEFAULT_TILE_SIZE,
    ): TileRef? {
        if (widthPx != tileSize || heightPx != tileSize) return null

        val spanX = bbox.maxX - bbox.minX
        val spanY = bbox.maxY - bbox.minY
        if (spanX <= 0 || spanY <= 0) return null

        // Tolerance is a quarter of one pixel of the requested extent. Expressed in
        // ground units so it scales with zoom rather than being a fixed epsilon that is
        // far too loose at z0 and far too tight at z20.
        val tolerance = spanX / widthPx / 4.0
        if (kotlin.math.abs(spanX - spanY) > tolerance) return null

        val zoomExact = kotlin.math.log2(WORLD_SIZE / spanX)
        val zoom = kotlin.math.round(zoomExact).toInt()
        if (zoom !in 0..30) return null
        // Compare in ground units: a zoom that is off by a fraction still has to place
        // the edges within tolerance to count.
        if (kotlin.math.abs(WORLD_SIZE / tilesPerAxis(zoom) - spanX) > tolerance) return null

        val tileSpan = WORLD_SIZE / tilesPerAxis(zoom)
        val xExact = (bbox.minX + ORIGIN_SHIFT) / tileSpan
        val yExact = (ORIGIN_SHIFT - bbox.maxY) / tileSpan
        val x = kotlin.math.round(xExact).toInt()
        val y = kotlin.math.round(yExact).toInt()

        if (kotlin.math.abs(xExact - x) * tileSpan > tolerance) return null
        if (kotlin.math.abs(yExact - y) * tileSpan > tolerance) return null

        val n = tilesPerAxis(zoom)
        if (x !in 0 until n || y !in 0 until n) return null

        return TileRef(zoom, x, y)
    }

    /**
     * Bing Maps quadkey for a tile, used by servers that index tiles by a single
     * base-4 string rather than separate x/y/z.
     */
    fun quadKey(zoom: Int, x: Int, y: Int): String {
        val n = tilesPerAxis(zoom)
        require(x in 0 until n) { "x out of range for zoom $zoom: $x" }
        require(y in 0 until n) { "y out of range for zoom $zoom: $y" }

        return buildString(zoom) {
            for (bit in zoom downTo 1) {
                val mask = 1 shl (bit - 1)
                var digit = 0
                if (x and mask != 0) digit += 1
                if (y and mask != 0) digit += 2
                append('0' + digit)
            }
        }
    }
}
