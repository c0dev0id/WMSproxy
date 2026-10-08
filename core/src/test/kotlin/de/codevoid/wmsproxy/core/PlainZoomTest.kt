package de.codevoid.wmsproxy.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlainZoomTest {

    private fun wmts(name: String, template: String, extent: LonLatBox? = null) = DiscoveredLayer(
        name = name,
        title = name,
        service = ServiceKind.WMTS,
        format = "image/png",
        template = template,
        extent = extent,
    )

    private fun document(vararg layers: DiscoveredLayer) =
        CapabilitiesResult.Success(ServiceKind.WMTS, "T", layers.toList(), emptyList())

    @Test
    fun `the sample is one tile of the first padded layer, in plain form, over its extent`() {
        val padded = document(
            wmts("plain", "https://s/plain/{z}/{x}/{y}.png"),
            wmts("web", "https://s/web/{z:02}/{x}/{y}.png", extent = LonLatBox(9.0, 49.0, 11.0, 51.0)),
            wmts("other", "https://s/other/{z:03}/{x}/{y}.png"),
        )
        val tile = TileMath.tileFor(10.0, 50.0, PlainZoom.SAMPLE_ZOOM)
        assertEquals("https://s/web/8/${tile.x}/${tile.y}.png", PlainZoom.sample(padded))
        // The yes applies to every padded layer, and leaves the plain one alone.
        assertEquals(
            listOf("https://s/plain/{z}/{x}/{y}.png", "https://s/web/{z}/{x}/{y}.png", "https://s/other/{z}/{x}/{y}.png"),
            PlainZoom.plainForm(padded).layers.map { it.template },
        )
    }

    @Test
    fun `a document without a padded zoom has nothing to ask and nothing to change`() {
        val plain = document(wmts("a", "https://s/{z}/{x}/{y}.png"), wmts("b", "https://s/{q}.png"))
        assertNull(PlainZoom.sample(plain))
        assertEquals(plain, PlainZoom.plainForm(plain))
    }

    @Test
    fun `without an extent the sample is aimed at the world centre`() {
        val padded = document(wmts("w", "https://s/{z:02}/{x}/{y}.png"))
        assertEquals("https://s/8/128/128.png", PlainZoom.sample(padded))
    }
}

class CapabilitiesCandidatesTest {

    @Test
    fun `a bare address is tried as given, then as WMS, WMTS and ArcGIS`() {
        assertEquals(
            listOf(
                "https://h/ows",
                "https://h/ows?SERVICE=WMS&REQUEST=GetCapabilities",
                "https://h/ows?SERVICE=WMTS&REQUEST=GetCapabilities",
                "https://h/ows?f=json",
            ),
            CapabilitiesCandidates.candidatesFor("https://h/ows"),
        )
    }

    @Test
    fun `an ArcGIS REST path asks for its description first after the address itself`() {
        assertEquals(
            listOf(
                "https://h/arcgis/rest/services/x/MapServer",
                "https://h/arcgis/rest/services/x/MapServer?f=json",
                "https://h/arcgis/rest/services/x/MapServer?SERVICE=WMS&REQUEST=GetCapabilities",
                "https://h/arcgis/rest/services/x/MapServer?SERVICE=WMTS&REQUEST=GetCapabilities",
            ),
            CapabilitiesCandidates.candidatesFor("https://h/arcgis/rest/services/x/MapServer"),
        )
    }

    @Test
    fun `a capabilities address is not reshaped, only given the ArcGIS fallback`() {
        assertEquals(
            listOf("https://h/wms?SERVICE=WMS&REQUEST=GetCapabilities", "https://h/wms?f=json"),
            CapabilitiesCandidates.candidatesFor("https://h/wms?SERVICE=WMS&REQUEST=GetCapabilities"),
        )
        assertEquals(
            listOf("https://h/wms?SERVICE=WMS", "https://h/wms?SERVICE=WMS&REQUEST=GetCapabilities", "https://h/wms?f=json"),
            CapabilitiesCandidates.candidatesFor("https://h/wms?SERVICE=WMS"),
        )
    }
}
