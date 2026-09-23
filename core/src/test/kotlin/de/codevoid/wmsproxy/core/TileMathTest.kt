package de.codevoid.wmsproxy.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TileMathTest {

    private val eps = 1e-6

    @Test
    fun `zoom 0 tile covers the whole world`() {
        val bbox = TileMath.tileBbox(0, 0, 0)
        assertEquals(-TileMath.ORIGIN_SHIFT, bbox.minX, eps)
        assertEquals(-TileMath.ORIGIN_SHIFT, bbox.minY, eps)
        assertEquals(TileMath.ORIGIN_SHIFT, bbox.maxX, eps)
        assertEquals(TileMath.ORIGIN_SHIFT, bbox.maxY, eps)
    }

    /**
     * Pins the y direction. Getting this backwards is the classic flipped-map bug, and
     * it renders perfectly while being wrong, so it is asserted rather than eyeballed.
     */
    @Test
    fun `tile 0,0 is the north-west quadrant at zoom 1`() {
        val bbox = TileMath.tileBbox(1, 0, 0)
        assertEquals(-TileMath.ORIGIN_SHIFT, bbox.minX, eps)
        assertEquals(0.0, bbox.maxX, eps)
        assertEquals(0.0, bbox.minY, eps)
        assertEquals(TileMath.ORIGIN_SHIFT, bbox.maxY, eps)
    }

    @Test
    fun `tile 1,1 is the south-east quadrant at zoom 1`() {
        val bbox = TileMath.tileBbox(1, 1, 1)
        assertEquals(0.0, bbox.minX, eps)
        assertEquals(TileMath.ORIGIN_SHIFT, bbox.maxX, eps)
        assertEquals(-TileMath.ORIGIN_SHIFT, bbox.minY, eps)
        assertEquals(0.0, bbox.maxY, eps)
    }

    @Test
    fun `adjacent tiles share an edge exactly`() {
        val left = TileMath.tileBbox(12, 2074, 1409)
        val right = TileMath.tileBbox(12, 2075, 1409)
        val below = TileMath.tileBbox(12, 2074, 1410)

        assertEquals(left.maxX, right.minX, eps)
        assertEquals(left.minY, below.maxY, eps)
    }

    @Test
    fun `tiles are square`() {
        val bbox = TileMath.tileBbox(12, 2074, 1409)
        assertEquals(bbox.maxX - bbox.minX, bbox.maxY - bbox.minY, eps)
    }

    @Test
    fun `flipY converts between XYZ and TMS row order`() {
        assertEquals(1, TileMath.flipY(1, 0))
        assertEquals(0, TileMath.flipY(1, 1))
        assertEquals(4095, TileMath.flipY(12, 0))
    }

    @Test
    fun `flipY is its own inverse`() {
        val y = 1409
        assertEquals(y, TileMath.flipY(12, TileMath.flipY(12, y)))
    }

    /** The worked example from Microsoft's Bing Maps tile-system documentation. */
    @Test
    fun `quadKey matches the reference example`() {
        assertEquals("213", TileMath.quadKey(3, 3, 5))
    }

    @Test
    fun `quadKey has one digit per zoom level`() {
        assertEquals(12, TileMath.quadKey(12, 2074, 1409).length)
        assertEquals("", TileMath.quadKey(0, 0, 0))
    }

    @Test
    fun `out of range tile indices are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { TileMath.tileBbox(1, 2, 0) }
        assertThrows(IllegalArgumentException::class.java) { TileMath.tileBbox(1, 0, -1) }
        assertThrows(IllegalArgumentException::class.java) { TileMath.flipY(1, 2) }
    }
}

class TileForTest {

    @Test
    fun `finds the tile holding a known position`() {
        // Stuttgart, 48.7758N 9.1829E, at z12.
        assertEquals(TileRef(12, 2152, 1410), TileMath.tileFor(9.1829, 48.7758, 12))
    }

    @Test
    fun `the whole world is one tile at zoom zero`() {
        assertEquals(TileRef(0, 0, 0), TileMath.tileFor(0.0, 0.0, 0))
        assertEquals(TileRef(0, 0, 0), TileMath.tileFor(-179.0, 80.0, 0))
    }

    @Test
    fun `the origin sits at the corner of the four middle tiles`() {
        assertEquals(TileRef(1, 1, 1), TileMath.tileFor(0.0, 0.0, 1))
        assertEquals(TileRef(1, 0, 0), TileMath.tileFor(-1.0, 1.0, 1))
    }

    @Test
    fun `round trips against the tile's own extent`() {
        for (tile in listOf(TileRef(3, 4, 2), TileRef(10, 537, 352), TileRef(16, 34427, 22548))) {
            val box = TileMath.tileBbox(tile.zoom, tile.x, tile.y)
            // Convert the box centre back through WebMercator to degrees.
            val x = (box.minX + box.maxX) / 2
            val y = (box.minY + box.maxY) / 2
            val lon = x / TileMath.ORIGIN_SHIFT * 180.0
            val lat = Math.toDegrees(2 * Math.atan(Math.exp(Math.toRadians(y / TileMath.ORIGIN_SHIFT * 180.0))) - Math.PI / 2)
            assertEquals(tile, TileMath.tileFor(lon, lat, tile.zoom))
        }
    }

    @Test
    fun `clamps beyond the grid rather than producing a tile that does not exist`() {
        // A declared extent may run to the pole; the WebMercator square does not.
        val n = TileMath.tilesPerAxis(5)
        for (lat in listOf(90.0, -90.0, 89.9, -89.9)) {
            val tile = TileMath.tileFor(0.0, lat, 5)
            assertTrue("$lat -> $tile", tile.y in 0 until n)
        }
        assertEquals(TileRef(5, 0, 0), TileMath.tileFor(-200.0, 90.0, 5))
        assertEquals(TileRef(5, n - 1, n - 1), TileMath.tileFor(200.0, -90.0, 5))
    }
}
