package de.codevoid.wmsproxy.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
