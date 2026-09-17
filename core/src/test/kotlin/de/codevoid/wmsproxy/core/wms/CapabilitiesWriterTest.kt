package de.codevoid.wmsproxy.core.wms

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilitiesWriterTest {

    private val layers = listOf(ProxyLayer("probe", "Probe layer", "For diagnostics"))
    private val baseUrl = "http://127.0.0.1:8088/wms"

    @Test
    fun `1_3_0 uses CRS, the namespace and the geographic bounding box element`() {
        val xml = CapabilitiesWriter.write("1.3.0", baseUrl, layers)

        assertTrue(xml.contains("""version="1.3.0""""))
        assertTrue(xml.contains("xmlns=\"http://www.opengis.net/wms\""))
        assertTrue(xml.contains("<CRS>EPSG:3857</CRS>"))
        assertTrue(xml.contains("<EX_GeographicBoundingBox>"))
        assertFalse(xml.contains("<SRS>"))
        assertFalse(xml.contains("LatLonBoundingBox"))
    }

    @Test
    fun `1_1_1 uses SRS, the DOCTYPE and LatLonBoundingBox`() {
        val xml = CapabilitiesWriter.write("1.1.1", baseUrl, layers)

        assertTrue(xml.contains("<!DOCTYPE WMT_MS_Capabilities"))
        assertTrue(xml.contains("<SRS>EPSG:3857</SRS>"))
        assertTrue(xml.contains("LatLonBoundingBox"))
        assertFalse(xml.contains("<CRS>"))
        assertFalse(xml.contains("EX_GeographicBoundingBox"))
    }

    @Test
    fun `advertises every configured layer by name`() {
        val many = listOf(ProxyLayer("a", "A"), ProxyLayer("b", "B"))
        val xml = CapabilitiesWriter.write("1.3.0", baseUrl, many)

        assertTrue(xml.contains("<Name>a</Name>"))
        assertTrue(xml.contains("<Name>b</Name>"))
    }

    /** A layer title from user config must not be able to break the document. */
    @Test
    fun `escapes markup in layer metadata`() {
        val xml = CapabilitiesWriter.write(
            "1.3.0",
            baseUrl,
            listOf(ProxyLayer("x", "Tom & <Jerry>")),
        )

        assertTrue(xml.contains("Tom &amp; &lt;Jerry&gt;"))
        assertFalse(xml.contains("<Jerry>"))
    }

    @Test
    fun `service exceptions match the requested version`() {
        assertTrue(CapabilitiesWriter.exception("1.1.1", "nope").contains("<!DOCTYPE"))
        assertTrue(
            CapabilitiesWriter.exception("1.3.0", "nope")
                .contains("xmlns=\"http://www.opengis.net/ogc\""),
        )
        assertTrue(CapabilitiesWriter.exception("1.3.0", "a & b").contains("a &amp; b"))
    }
}
