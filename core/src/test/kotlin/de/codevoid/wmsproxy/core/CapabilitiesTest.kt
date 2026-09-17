package de.codevoid.wmsproxy.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val ENDPOINT = "https://example.org/geoserver/ows"

private fun success(xml: String, url: String = ENDPOINT): CapabilitiesResult.Success {
    val result = CapabilitiesParser.parse(xml, url)
    assertTrue("expected success, got $result", result is CapabilitiesResult.Success)
    return result as CapabilitiesResult.Success
}

class WmsCapabilitiesTest {

    /** 1.3.0: `CRS`, and a nested layer that declares none of its own. */
    private val wms130 = """
        <?xml version="1.0"?>
        <WMS_Capabilities version="1.3.0" xmlns="http://www.opengis.net/wms"
                          xmlns:xlink="http://www.w3.org/1999/xlink">
          <Service><Title>Example SDI</Title></Service>
          <Capability>
            <Request>
              <GetMap>
                <Format>application/pdf</Format>
                <Format>image/jpeg</Format>
                <Format>image/png</Format>
                <DCPType><HTTP><Get>
                  <OnlineResource xlink:href="https://example.org/geoserver/ows?"/>
                </Get></HTTP></DCPType>
              </GetMap>
            </Request>
            <Layer>
              <Title>Root</Title>
              <CRS>EPSG:4326</CRS>
              <CRS>EPSG:3857</CRS>
              <Layer queryable="1">
                <Name>roads</Name>
                <Title>Road network</Title>
              </Layer>
              <Layer>
                <Name>parcels</Name>
                <Title>Parcels</Title>
                <CRS>EPSG:25832</CRS>
              </Layer>
            </Layer>
          </Capability>
        </WMS_Capabilities>
    """.trimIndent()

    @Test
    fun `reads the service and both named layers`() {
        val parsed = success(wms130)
        assertEquals(ServiceKind.WMS, parsed.service)
        assertEquals("Example SDI", parsed.title)
        // The unnamed root is a grouping, not something that can be requested.
        assertEquals(listOf("roads", "parcels"), parsed.layers.map { it.name } + parsed.skipped.map { it.name })
    }

    @Test
    fun `a child inherits the CRS list its parent declared`() {
        // 'roads' declares no CRS of its own and is only serveable through inheritance.
        assertEquals(listOf("roads"), success(wms130).layers.map { it.name })
    }

    @Test
    fun `a layer without WebMercator is skipped, not reprojected`() {
        val skipped = success(wms130).skipped.single()
        assertEquals("parcels", skipped.name)
        assertTrue(skipped.reason, skipped.reason.contains("3857"))
    }

    @Test
    fun `builds a 1_3_0 GetMap template with CRS and a tile-sized viewport`() {
        val template = success(wms130).layers.single().template
        assertTrue(template, template.startsWith("https://example.org/geoserver/ows?"))
        assertTrue(template, template.contains("SERVICE=WMS"))
        assertTrue(template, template.contains("VERSION=1.3.0"))
        assertTrue(template, template.contains("&CRS=EPSG:3857"))
        assertTrue(template, !template.contains("&SRS="))
        assertTrue(template, template.contains("&LAYERS=roads"))
        assertTrue(template, template.contains("&STYLES=&"))
        assertTrue(template, template.contains("&BBOX={bbox}"))
        assertTrue(template, template.contains("&WIDTH=256&HEIGHT=256"))
        // png is preferred over the jpeg and pdf also on offer.
        assertTrue(template, template.contains("&FORMAT=image/png"))
    }

    @Test
    fun `the generated template expands to a real request`() {
        val layer = TileLayer(
            source = "x",
            urlTemplate = success(wms130).layers.single().template,
        )
        val url = layer.urlFor(TileRef(12, 2152, 1410))
        val expected = TileMath.tileBbox(12, 2152, 1410).asWmsParameter()
        assertTrue(url, url.contains("BBOX=$expected"))
        assertTrue(url, !url.contains("{"))
    }

    /** 1.1.1: `SRS`, and an endpoint that already carries a parameter. */
    private val wms111 = """
        <?xml version="1.0"?>
        <WMT_MS_Capabilities version="1.1.1" xmlns:xlink="http://www.w3.org/1999/xlink">
          <Service><Title>Legacy</Title></Service>
          <Capability>
            <Request>
              <GetMap>
                <Format>image/png</Format>
                <DCPType><HTTP><Get>
                  <OnlineResource xlink:href="https://legacy.example.org/cgi-bin/mapserv?map=/data/x.map"/>
                </Get></HTTP></DCPType>
              </GetMap>
            </Request>
            <Layer>
              <SRS>EPSG:900913</SRS>
              <Layer><Name>ortho</Name><Title>Orthophoto</Title></Layer>
            </Layer>
          </Capability>
        </WMT_MS_Capabilities>
    """.trimIndent()

    @Test
    fun `uses SRS for 1_1_1 and accepts the 900913 spelling of WebMercator`() {
        val template = success(wms111).layers.single().template
        assertTrue(template, template.contains("VERSION=1.1.1"))
        assertTrue(template, template.contains("&SRS=EPSG:900913"))
        assertTrue(template, !template.contains("&CRS="))
    }

    @Test
    fun `keeps parameters the published endpoint already carries`() {
        val template = success(wms111).layers.single().template
        assertTrue(template, template.contains("map=/data/x.map&SERVICE=WMS"))
    }

    @Test
    fun `layer identifiers are encoded, so a colon or a space cannot break the query`() {
        val xml = wms130.replace("<Name>roads</Name>", "<Name>ws:road netz-ä</Name>")
        val template = success(xml).layers.single().template
        // Colon kept (legal, and identifiers are full of them), space and umlaut encoded.
        assertTrue(template, template.contains("&LAYERS=ws:road%20netz-%C3%A4&"))
    }

    @Test
    fun `falls back to the requested url when no OnlineResource is published`() {
        // Built from scratch rather than string-replaced out of the fixture above: a
        // replacement that silently fails to match would leave the OnlineResource in
        // place and the test would pass without exercising the fallback at all.
        val xml = """
            <?xml version="1.0"?>
            <WMS_Capabilities version="1.3.0">
              <Capability>
                <Request><GetMap><Format>image/png</Format></GetMap></Request>
                <Layer><CRS>EPSG:3857</CRS><Layer><Name>only</Name></Layer></Layer>
              </Capability>
            </WMS_Capabilities>
        """.trimIndent()
        val template = success(xml, "https://example.org/geoserver/ows?request=GetCapabilities")
            .layers.single().template
        assertTrue(template, template.startsWith("https://example.org/geoserver/ows?SERVICE=WMS"))
    }
}

class WmtsCapabilitiesTest {

    // No XML declaration: the interpolated TileMatrix lines below carry no indent, so
    // trimIndent() has nothing to strip, and a declaration would end up offset from the
    // start of the document — which is a parse error. Leading whitespace before the root
    // element is not.
    private fun wmts(matrixIds: List<String>, format: String = "image/png") = """
        <Capabilities version="1.0.0" xmlns="http://www.opengis.net/wmts/1.0"
                      xmlns:ows="http://www.opengis.net/ows/1.1"
                      xmlns:xlink="http://www.w3.org/1999/xlink">
          <ows:ServiceIdentification><ows:Title>Tiles</ows:Title></ows:ServiceIdentification>
          <ows:OperationsMetadata>
            <ows:Operation name="GetTile">
              <ows:DCP><ows:HTTP>
                <ows:Get xlink:href="https://example.org/gwc/service/wmts/rest">
                  <ows:Constraint name="GetEncoding">
                    <ows:AllowedValues><ows:Value>RESTful</ows:Value></ows:AllowedValues>
                  </ows:Constraint>
                </ows:Get>
                <ows:Get xlink:href="https://example.org/gwc/service/wmts">
                  <ows:Constraint name="GetEncoding">
                    <ows:AllowedValues><ows:Value>KVP</ows:Value></ows:AllowedValues>
                  </ows:Constraint>
                </ows:Get>
              </ows:HTTP></ows:DCP>
            </ows:Operation>
          </ows:OperationsMetadata>
          <Contents>
            <Layer>
              <ows:Identifier>topo</ows:Identifier>
              <ows:Title>Topographic</ows:Title>
              <Style isDefault="true"><ows:Identifier>default</ows:Identifier></Style>
              <Format>$format</Format>
              <TileMatrixSetLink><TileMatrixSet>WebMercatorQuad</TileMatrixSet></TileMatrixSetLink>
            </Layer>
            <TileMatrixSet>
              <ows:Identifier>WebMercatorQuad</ows:Identifier>
              <ows:SupportedCRS>urn:ogc:def:crs:EPSG::3857</ows:SupportedCRS>
              ${matrixIds.joinToString("\n") { "<TileMatrix><ows:Identifier>$it</ows:Identifier></TileMatrix>" }}
            </TileMatrixSet>
          </Contents>
        </Capabilities>
    """.trimIndent()

    @Test
    fun `builds a KVP GetTile template with bare integer matrix identifiers`() {
        val parsed = success(wmts(listOf("0", "1", "2")))
        assertEquals(ServiceKind.WMTS, parsed.service)
        val template = parsed.layers.single().template
        assertTrue(template, template.startsWith("https://example.org/gwc/service/wmts?"))
        assertTrue(template, template.contains("REQUEST=GetTile"))
        assertTrue(template, template.contains("&LAYER=topo"))
        assertTrue(template, template.contains("&STYLE=default"))
        assertTrue(template, template.contains("&TILEMATRIXSET=WebMercatorQuad"))
        assertTrue(template, template.contains("&TILEMATRIX={z}"))
        assertTrue(template, template.contains("&TILEROW={y}&TILECOL={x}"))
    }

    @Test
    fun `prefers the KVP endpoint over the RESTful one`() {
        // Appending KVP parameters to the /rest path would 404 on every tile.
        assertTrue(success(wmts(listOf("0", "1"))).layers.single().template.contains("/wmts?"))
    }

    @Test
    fun `expresses a prefixed matrix identifier as a template`() {
        val parsed = success(wmts(listOf("EPSG:900913:0", "EPSG:900913:1", "EPSG:900913:2")))
        assertTrue(parsed.layers.single().template.contains("&TILEMATRIX=EPSG:900913:{z}"))
    }

    @Test
    fun `skips a layer whose matrix identifiers have no template form`() {
        val parsed = success(wmts(listOf("1:500000", "1:250000")))
        assertTrue(parsed.layers.isEmpty())
        assertTrue(parsed.skipped.single().reason.contains("cannot be expressed"))
    }

    @Test
    fun `skips a vector-tile-only layer and says why`() {
        val parsed = success(wmts(listOf("0", "1"), format = "application/vnd.mapbox-vector-tile"))
        assertTrue(parsed.layers.isEmpty())
        val reason = parsed.skipped.single().reason
        assertTrue(reason, reason.contains("not a raster image"))
    }

    @Test
    fun `skips a layer with no WebMercator matrix set`() {
        val parsed = success(
            wmts(listOf("0", "1")).replace("urn:ogc:def:crs:EPSG::3857", "urn:ogc:def:crs:EPSG::25832"),
        )
        assertTrue(parsed.layers.isEmpty())
        assertTrue(parsed.skipped.single().reason.contains("WebMercator"))
    }
}

class CrsCodeTest {

    @Test
    fun `reduces every spelling of a CRS to its bare code`() {
        assertEquals("3857", CapabilitiesParser.crsCode("EPSG:3857"))
        assertEquals("3857", CapabilitiesParser.crsCode("urn:ogc:def:crs:EPSG::3857"))
        assertEquals("3857", CapabilitiesParser.crsCode("http://www.opengis.net/def/crs/EPSG/0/3857"))
        assertEquals("900913", CapabilitiesParser.crsCode(" EPSG:900913 "))
    }

    @Test
    fun `does not confuse a code that merely ends in another`() {
        // A suffix match would have called this WebMercator.
        assertEquals("103857", CapabilitiesParser.crsCode("EPSG:103857"))
    }
}

class ZoomTemplateTest {

    @Test
    fun `bare integers become the z placeholder`() {
        assertEquals("{z}", CapabilitiesParser.zoomTemplateFor(listOf("0", "1", "2", "18")))
    }

    @Test
    fun `a consistent prefix is kept literal`() {
        assertEquals("L{z}", CapabilitiesParser.zoomTemplateFor(listOf("L0", "L1", "L2")))
        assertEquals(
            "EPSG:3857:{z}",
            CapabilitiesParser.zoomTemplateFor(listOf("EPSG:3857:0", "EPSG:3857:11")),
        )
    }

    @Test
    fun `refuses what has no template form rather than inventing levels`() {
        assertNull(CapabilitiesParser.zoomTemplateFor(emptyList()))
        // Zero padding: "L07" is not "L" + 7, so the template would request a level that
        // does not exist.
        assertNull(CapabilitiesParser.zoomTemplateFor(listOf("L00", "L01", "L02")))
        assertNull(CapabilitiesParser.zoomTemplateFor(listOf("1:500000", "1:250000")))
        assertNull(CapabilitiesParser.zoomTemplateFor(listOf("L0", "M1")))
        assertNull(CapabilitiesParser.zoomTemplateFor(listOf("tiny", "small")))
    }
}

class CapabilitiesFailureTest {

    @Test
    fun `reports unparseable input rather than throwing`() {
        assertTrue(CapabilitiesParser.parse("not xml at all", ENDPOINT) is CapabilitiesResult.Failure)
        assertTrue(CapabilitiesParser.parse("", ENDPOINT) is CapabilitiesResult.Failure)
    }

    @Test
    fun `surfaces a service exception instead of calling it an unknown document`() {
        val xml = """<?xml version="1.0"?>
            <ServiceExceptionReport><ServiceException code="InvalidFormat">
            Layer not defined</ServiceException></ServiceExceptionReport>"""
        val result = CapabilitiesParser.parse(xml, ENDPOINT)
        assertTrue(result is CapabilitiesResult.Failure)
        assertTrue((result as CapabilitiesResult.Failure).message.contains("Layer not defined"))
    }

    @Test
    fun `rejects a document that is valid XML but not capabilities`() {
        val result = CapabilitiesParser.parse("<html><body>Hello</body></html>", ENDPOINT)
        assertTrue(result is CapabilitiesResult.Failure)
        assertTrue((result as CapabilitiesResult.Failure).message.contains("not a WMS or WMTS", true))
    }
}
