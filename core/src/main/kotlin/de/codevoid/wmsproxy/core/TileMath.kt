package de.codevoid.wmsproxy.core

import java.util.Locale

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
) {
    /**
     * The `BBOX` parameter form a WMS request carries: `minx,miny,maxx,maxy`.
     *
     * Formatted under [Locale.ROOT] and never the default locale. On a German-locale
     * device the default would render the decimal mark as a comma, which is also the
     * separator between the four values — producing eight fields where the server
     * expects four, and a request that fails or, worse, parses into nonsense.
     *
     * Six decimals is a micrometre in WebMercator, far below the resolution of any tile
     * this will ever be asked for, so the rounding cannot move the image.
     */
    fun asWmsParameter(): String = listOf(minX, minY, maxX, maxY)
        .joinToString(",") { String.format(Locale.ROOT, "%.6f", it) }
}

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

    /**
     * The tile size every common WebMercator grid uses. Needed when an upstream is a
     * WMS server: a tile becomes a GetMap of exactly this many pixels square.
     */
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
     * The tile containing a geographic position, for picking somewhere to probe.
     *
     * WebMercator arithmetic, not a transformation between projections: longitude is
     * linear in x, and y is the Mercator latitude formula. Latitude is clamped to the
     * grid's own limit (±85.051…°, where the projection reaches the square) rather than
     * failing, because a layer's declared extent legitimately runs to the pole and the
     * grid simply stops before it.
     */
    fun tileFor(longitude: Double, latitude: Double, zoom: Int): TileRef {
        val n = tilesPerAxis(zoom)
        val lat = latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)
        val lon = longitude.coerceIn(-180.0, 180.0)

        val x = ((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
        val sinLat = Math.sin(Math.toRadians(lat))
        val yFraction = 0.5 - Math.log((1 + sinLat) / (1 - sinLat)) / (4 * Math.PI)
        val y = (yFraction * n).toInt().coerceIn(0, n - 1)
        return TileRef(zoom, x, y)
    }

    /**
     * The inverse of what [tileFor] applies: metres on the WebMercator plane back to
     * degrees. It aims a measurement at a declared extent; it never moves a tile.
     */
    fun lonLatOf(x: Double, y: Double): LonLat = LonLat(
        longitude = x / ORIGIN_SHIFT * 180.0,
        latitude = Math.toDegrees(Math.atan(Math.sinh(y / ORIGIN_SHIFT * Math.PI))),
    )

    /** Where the WebMercator square stops, north and south. */
    const val MAX_LATITUDE: Double = 85.05112877980659

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
