package de.codevoid.wmsproxy.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TileMediaTypeTest {

    @Test
    fun `accepts the raster formats tile servers emit`() {
        for (type in listOf("image/png", "image/jpeg", "image/webp", "image/gif", "image/bmp")) {
            assertTrue(type, TileMediaType.isRasterImage(type))
        }
    }

    @Test
    fun `accepts raster formats the client may not decode, and lets it decide`() {
        // Advertised by GeoServer. Whether the client draws these is its own business;
        // if one proves unusable, a rule is added for that format on the evidence.
        assertTrue(TileMediaType.isRasterImage("image/tiff"))
        assertTrue(TileMediaType.isRasterImage("image/geotiff"))
        assertTrue(TileMediaType.isRasterImage("image/vnd.jpeg-png"))
        assertTrue(TileMediaType.isRasterImage("image/avif"))
        assertTrue(TileMediaType.isRasterImage("image/heic"))
    }

    @Test
    fun `ignores parameters and case`() {
        // GeoServer answers with these verbatim.
        assertTrue(TileMediaType.isRasterImage("image/png; mode=8bit"))
        assertTrue(TileMediaType.isRasterImage("image/jpeg;charset=binary"))
        assertTrue(TileMediaType.isRasterImage("IMAGE/PNG"))
        assertTrue(TileMediaType.isRasterImage("  image/png  "))
    }

    @Test
    fun `refuses vector tiles rather than relaying bytes that would need rendering`() {
        assertFalse(TileMediaType.isRasterImage("application/vnd.mapbox-vector-tile"))
        assertFalse(TileMediaType.isRasterImage("application/x-protobuf"))
        // An image media type, but a vector document — relaying it cannot come good.
        assertFalse(TileMediaType.isRasterImage("image/svg+xml"))
        assertFalse(TileMediaType.isRasterImage("IMAGE/SVG+XML; charset=utf-8"))
    }

    @Test
    fun `refuses the data formats a WMS endpoint offers beside images`() {
        for (type in listOf(
            "application/json;type=geojson",
            "application/json;type=topojson",
            "application/json;type=utfgrid",
            "application/pdf",
            "application/vnd.google-earth.kml+xml",
            "application/vnd.google-earth.kmz",
            "application/atom+xml",
        )) {
            assertFalse(type, TileMediaType.isRasterImage(type))
        }
    }

    @Test
    fun `refuses error documents served in place of a tile`() {
        // The common auth-failure shape: HTTP 200 carrying an error page.
        assertFalse(TileMediaType.isRasterImage("text/html"))
        assertFalse(TileMediaType.isRasterImage("text/html; charset=utf-8"))
        assertFalse(TileMediaType.isRasterImage("text/plain"))
        // A WMS ServiceExceptionReport.
        assertFalse(TileMediaType.isRasterImage("application/vnd.ogc.se_xml"))
        assertFalse(TileMediaType.isRasterImage("text/xml"))
    }

    @Test
    fun `refuses an unstated or unusable type instead of guessing`() {
        assertFalse(TileMediaType.isRasterImage(null))
        assertFalse(TileMediaType.isRasterImage(""))
        assertFalse(TileMediaType.isRasterImage("   "))
        assertFalse(TileMediaType.isRasterImage("application/octet-stream"))
        // Malformed, and a prefix match alone must not carry it.
        assertFalse(TileMediaType.isRasterImage("image"))
        assertFalse(TileMediaType.isRasterImage("image/"))
        assertFalse(TileMediaType.isRasterImage("notimage/png"))
    }
}
