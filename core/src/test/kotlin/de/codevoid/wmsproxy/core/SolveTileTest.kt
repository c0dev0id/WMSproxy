package de.codevoid.wmsproxy.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SolveTileTest {

    /** Every tile must solve back to itself, or the alignment gate is worthless. */
    @Test
    fun `round-trips every tile at low zooms`() {
        for (zoom in 0..6) {
            val n = TileMath.tilesPerAxis(zoom)
            for (x in 0 until n) {
                for (y in 0 until n) {
                    val bbox = TileMath.tileBbox(zoom, x, y)
                    assertEquals(
                        "z$zoom/$x/$y",
                        TileRef(zoom, x, y),
                        TileMath.solveTile(bbox, 256, 256),
                    )
                }
            }
        }
    }

    @Test
    fun `round-trips a deep tile`() {
        val bbox = TileMath.tileBbox(18, 137152, 90112)
        assertEquals(TileRef(18, 137152, 90112), TileMath.solveTile(bbox, 256, 256))
    }

    @Test
    fun `refuses an extent shifted off the grid`() {
        val tile = TileMath.tileBbox(12, 2074, 1409)
        val span = tile.maxX - tile.minX
        val shifted = Bbox(
            minX = tile.minX + span / 2,
            minY = tile.minY,
            maxX = tile.maxX + span / 2,
            maxY = tile.maxY,
        )
        assertNull(TileMath.solveTile(shifted, 256, 256))
    }

    @Test
    fun `refuses an extent between zoom levels`() {
        val tile = TileMath.tileBbox(12, 2074, 1409)
        val span = (tile.maxX - tile.minX) * 1.5
        assertNull(
            TileMath.solveTile(
                Bbox(tile.minX, tile.maxY - span, tile.minX + span, tile.maxY),
                256,
                256,
            ),
        )
    }

    @Test
    fun `refuses a non-square extent`() {
        val tile = TileMath.tileBbox(12, 2074, 1409)
        assertNull(
            TileMath.solveTile(
                Bbox(tile.minX, tile.minY, tile.maxX, tile.maxY - 1000.0),
                256,
                256,
            ),
        )
    }

    @Test
    fun `refuses a pixel size the grid does not use`() {
        val bbox = TileMath.tileBbox(12, 2074, 1409)
        assertNull(TileMath.solveTile(bbox, 512, 512))
        assertNull(TileMath.solveTile(bbox, 256, 200))
    }

    /**
     * Clients send coordinates rounded to a handful of decimals. A tile whose edges are
     * printed to 2dp must still resolve, or real requests would be rejected as unaligned.
     */
    @Test
    fun `tolerates coordinates rounded by the client`() {
        val tile = TileMath.tileBbox(14, 8555, 5677)
        val rounded = Bbox(
            minX = Math.round(tile.minX * 100.0) / 100.0,
            minY = Math.round(tile.minY * 100.0) / 100.0,
            maxX = Math.round(tile.maxX * 100.0) / 100.0,
            maxY = Math.round(tile.maxY * 100.0) / 100.0,
        )
        assertEquals(TileRef(14, 8555, 5677), TileMath.solveTile(rounded, 256, 256))
    }

    @Test
    fun `refuses a degenerate extent`() {
        assertNull(TileMath.solveTile(Bbox(0.0, 0.0, 0.0, 0.0), 256, 256))
        assertNull(TileMath.solveTile(Bbox(10.0, 10.0, 0.0, 0.0), 256, 256))
    }
}
