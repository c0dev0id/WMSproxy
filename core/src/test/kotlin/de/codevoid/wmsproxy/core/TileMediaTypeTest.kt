package de.codevoid.wmsproxy.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TileMediaTypeTest {

    @Test
    fun `accepts the raster formats tile servers emit`() {
        for (type in listOf("image/png", "image/jpeg", "image/webp", "image/gif", "image/bmp")) {
            assertTrue(type, TileMediaType.isDrawableTile(type))
        }
    }

    @Test
    fun `ignores parameters and case`() {
        // GeoServer answers with these verbatim.
        assertTrue(TileMediaType.isDrawableTile("image/png; mode=8bit"))
        assertTrue(TileMediaType.isDrawableTile("image/jpeg;charset=binary"))
        assertTrue(TileMediaType.isDrawableTile("IMAGE/PNG"))
        assertTrue(TileMediaType.isDrawableTile("  image/png  "))
    }

    @Test
    fun `refuses vector tiles rather than relaying bytes nothing can draw`() {
        assertFalse(TileMediaType.isDrawableTile("application/vnd.mapbox-vector-tile"))
        assertFalse(TileMediaType.isDrawableTile("application/x-protobuf"))
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
            assertFalse(type, TileMediaType.isDrawableTile(type))
        }
    }

    @Test
    fun `refuses image media types that are not raster or not decodable`() {
        // Vector, and an XML document besides.
        assertFalse(TileMediaType.isDrawableTile("image/svg+xml"))
        // Advertised by GeoServer; Android decodes neither.
        assertFalse(TileMediaType.isDrawableTile("image/tiff"))
        assertFalse(TileMediaType.isDrawableTile("image/geotiff"))
    }

    @Test
    fun `refuses error documents served in place of a tile`() {
        // The common auth-failure shape: HTTP 200 carrying an error page.
        assertFalse(TileMediaType.isDrawableTile("text/html"))
        assertFalse(TileMediaType.isDrawableTile("text/html; charset=utf-8"))
        assertFalse(TileMediaType.isDrawableTile("text/plain"))
        // A WMS ServiceExceptionReport.
        assertFalse(TileMediaType.isDrawableTile("application/vnd.ogc.se_xml"))
        assertFalse(TileMediaType.isDrawableTile("text/xml"))
    }

    @Test
    fun `refuses an unstated or unusable type instead of guessing`() {
        assertFalse(TileMediaType.isDrawableTile(null))
        assertFalse(TileMediaType.isDrawableTile(""))
        assertFalse(TileMediaType.isDrawableTile("   "))
        assertFalse(TileMediaType.isDrawableTile("application/octet-stream"))
        // Malformed, and a prefix match alone must not carry it.
        assertFalse(TileMediaType.isDrawableTile("image"))
        assertFalse(TileMediaType.isDrawableTile("image/"))
        assertFalse(TileMediaType.isDrawableTile("notimage/png"))
    }
}
