package de.codevoid.wmsproxy.core.wms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WmsKvpTest {

    @Test
    fun `parameter names are case-insensitive`() {
        val params = WmsKvp.parseQuery("service=WMS&Request=GetMap&VERSION=1.3.0")
        assertEquals("GetMap", params["REQUEST"])
        assertEquals("WMS", params["SERVICE"])
        assertEquals(WmsOperation.GET_MAP, WmsKvp.operationOf(params))
    }

    @Test
    fun `recognises capabilities regardless of spelling`() {
        assertEquals(
            WmsOperation.GET_CAPABILITIES,
            WmsKvp.operationOf(WmsKvp.parseQuery("request=getcapabilities")),
        )
        assertEquals(
            WmsOperation.GET_CAPABILITIES,
            WmsKvp.operationOf(WmsKvp.parseQuery("REQUEST=GetCapabilities")),
        )
    }

    @Test
    fun `decodes percent escapes and plus as space`() {
        val params = WmsKvp.parseQuery("FORMAT=image%2Fpng&LAYERS=a+b")
        assertEquals("image/png", params["FORMAT"])
        assertEquals("a b", params["LAYERS"])
    }

    @Test
    fun `normalizes web mercator aliases`() {
        assertEquals("EPSG:3857", WmsKvp.normalizeCrs("EPSG:900913"))
        assertEquals("EPSG:3857", WmsKvp.normalizeCrs("epsg:102100"))
        assertEquals("EPSG:3857", WmsKvp.normalizeCrs("EPSG:3857"))
        assertEquals("EPSG:25832", WmsKvp.normalizeCrs("epsg:25832"))
    }

    @Test
    fun `reads a 1_1_1 request with SRS and lon-lat ordering`() {
        val request = WmsKvp.parseGetMap(
            WmsKvp.parseQuery(
                "REQUEST=GetMap&VERSION=1.1.1&SRS=EPSG:4326" +
                    "&BBOX=7.0,50.0,8.0,51.0&WIDTH=256&HEIGHT=256&LAYERS=probe",
            ),
        )!!

        assertEquals("EPSG:4326", request.crs)
        assertEquals(7.0, request.bbox.minX, 1e-9)
        assertEquals(50.0, request.bbox.minY, 1e-9)
        assertEquals(8.0, request.bbox.maxX, 1e-9)
        assertEquals(51.0, request.bbox.maxY, 1e-9)
        assertEquals(listOf("probe"), request.layers)
    }

    /**
     * The trap that produces a map which renders perfectly in the wrong place: WMS 1.3.0
     * orders a geographic CRS latitude-first, the reverse of 1.1.1.
     */
    @Test
    fun `reads a 1_3_0 geographic request as latitude-first`() {
        val request = WmsKvp.parseGetMap(
            WmsKvp.parseQuery(
                "REQUEST=GetMap&VERSION=1.3.0&CRS=EPSG:4326" +
                    "&BBOX=50.0,7.0,51.0,8.0&WIDTH=256&HEIGHT=256&LAYERS=probe",
            ),
        )!!

        assertEquals(7.0, request.bbox.minX, 1e-9)
        assertEquals(50.0, request.bbox.minY, 1e-9)
        assertEquals(8.0, request.bbox.maxX, 1e-9)
        assertEquals(51.0, request.bbox.maxY, 1e-9)
    }

    /** A projected CRS keeps easting-first even in 1.3.0. */
    @Test
    fun `does not swap axes for a projected crs in 1_3_0`() {
        val request = WmsKvp.parseGetMap(
            WmsKvp.parseQuery(
                "REQUEST=GetMap&VERSION=1.3.0&CRS=EPSG:3857" +
                    "&BBOX=1.0,2.0,3.0,4.0&WIDTH=256&HEIGHT=256&LAYERS=probe",
            ),
        )!!

        assertEquals(1.0, request.bbox.minX, 1e-9)
        assertEquals(2.0, request.bbox.minY, 1e-9)
    }

    @Test
    fun `returns null when a required parameter is missing or malformed`() {
        assertNull(WmsKvp.parseGetMap(WmsKvp.parseQuery("REQUEST=GetMap&VERSION=1.3.0")))
        assertNull(
            WmsKvp.parseGetMap(
                WmsKvp.parseQuery("REQUEST=GetMap&CRS=EPSG:3857&BBOX=1,2,3&WIDTH=256&HEIGHT=256"),
            ),
        )
        assertNull(
            WmsKvp.parseGetMap(
                WmsKvp.parseQuery("REQUEST=GetMap&CRS=EPSG:3857&BBOX=1,2,3,4&WIDTH=0&HEIGHT=256"),
            ),
        )
    }

    @Test
    fun `identifies geographic systems`() {
        assertTrue(WmsKvp.isGeographic("EPSG:4326"))
        assertTrue(WmsKvp.isGeographic("epsg:4258"))
        assertEquals(false, WmsKvp.isGeographic("EPSG:3857"))
        assertEquals(false, WmsKvp.isGeographic("EPSG:25832"))
    }
}
