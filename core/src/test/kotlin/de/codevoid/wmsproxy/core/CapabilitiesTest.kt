package de.codevoid.wmsproxy.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun success(xml: String): CapabilitiesResult.Success {
    val result = CapabilitiesParser.parse(xml)
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
              <Layer>
                <Title>Web group</Title>
                <CRS>EPSG:3857</CRS>
                <Layer queryable="1">
                  <Name>roads</Name>
                  <Title>Road network</Title>
                </Layer>
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
        // 'roads' declares no CRS of its own and is serveable only through the group
        // above it. This is the shape GeoServer and MapServer actually emit: the CRS list
        // is declared once high in the tree and the leaves carry none.
        assertEquals(listOf("roads"), success(wms130).layers.map { it.name })
    }

    @Test
    fun `an inherited CRS is added to the layer's own, not replaced by it`() {
        // WMS 1.3.0 Annex E makes CRS additive down the tree. A layer declaring only a
        // national grid is still serveable when an ancestor offered WebMercator, and
        // treating the child's own list as the whole truth would reject it wrongly —
        // which would reject almost every real GeoServer layer, since those declare the
        // CRS list on the root and nothing below it.
        val xml = """
            <?xml version="1.0"?>
            <WMS_Capabilities version="1.3.0" xmlns:xlink="http://www.w3.org/1999/xlink">
              <Capability>
                <Request>
                  <GetMap>
                    <Format>image/png</Format>
                    <DCPType><HTTP><Get>
                      <OnlineResource xlink:href="https://example.org/wms"/>
                    </Get></HTTP></DCPType>
                  </GetMap>
                </Request>
                <Layer>
                  <CRS>EPSG:3857</CRS>
                  <Layer>
                    <Name>local</Name>
                    <CRS>EPSG:25832</CRS>
                  </Layer>
                </Layer>
              </Capability>
            </WMS_Capabilities>
        """.trimIndent()
        val parsed = success(xml)
        assertEquals(listOf("local"), parsed.layers.map { it.name })
        assertTrue(parsed.skipped.toString(), parsed.skipped.isEmpty())
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
    fun `asks for the format the server actually advertised`() {
        // A server offering only "image/png; mode=8bit" means that exact value; asking
        // for bare image/png is asking for something it did not offer.
        val xml = wms130.replace("<Format>image/png</Format>", "<Format>image/png; mode=8bit</Format>")
        val template = success(xml).layers.single().template
        // Semicolon, space and equals are all encoded; the slash is not, since it is
        // legal in a query value and some servers match FORMAT literally.
        assertTrue(template, template.contains("&FORMAT=image/png%3B%20mode%3D8bit"))
    }

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
    fun `reports a document that publishes no endpoint instead of guessing one`() {
        // The URL that fetched the document is not the endpoint: a service behind a proxy
        // or an alias names its real GetMap address here, and OnlineResource is mandatory
        // in both WMS schemas. Missing it is a broken document, and inventing an address
        // would fail later and somewhere less obvious.
        val xml = """
            <?xml version="1.0"?>
            <WMS_Capabilities version="1.3.0">
              <Capability>
                <Request><GetMap><Format>image/png</Format></GetMap></Request>
                <Layer><CRS>EPSG:3857</CRS><Layer><Name>only</Name></Layer></Layer>
              </Capability>
            </WMS_Capabilities>
        """.trimIndent()
        val result = CapabilitiesParser.parse(xml)
        assertTrue(result.toString(), result is CapabilitiesResult.Failure)
        assertTrue((result as CapabilitiesResult.Failure).message.contains("GetMap endpoint"))
    }

    @Test
    fun `proposes a source name from the service title, not the url`() {
        assertEquals("Example_SDI", success(wms130).suggestedSourceId())
    }

    @Test
    fun `parsing from a stream gives the same answer as parsing a string`() {
        // The app streams the response straight in; fixtures come as strings. The two
        // must not drift, because only one of them is covered by every other test here.
        assertEquals(
            CapabilitiesParser.parse(wms130),
            CapabilitiesParser.parse(wms130.byteInputStream()),
        )
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
    fun `keeps a padded matrix set and encodes around the placeholder`() {
        val parsed = success(wmts(listOf("00", "01", "02")))
        val template = parsed.layers.single().template
        // The braces and the colon must survive: encoding them would leave a placeholder
        // nothing substitutes, and the server would be asked for a level named "{z:02}".
        assertTrue(template, template.contains("&TILEMATRIX={z:02}"))
    }

    @Test
    fun `accepts a format no preference list predicts, if it is a raster image`() {
        // ArcGIS advertises this, meaning PNG or JPEG depending on the tile.
        val parsed = success(wmts(listOf("0", "1"), format = "image/jpgpng"))
        assertEquals("image/jpgpng", parsed.layers.single().format)
        assertTrue(parsed.layers.single().template.contains("&FORMAT=image/jpgpng"))
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

    // REST-style WMTS (no OperationsMetadata): BKG TopPlusOpen is the real-world case.
    private val wmtsRest = """
        <Capabilities version="1.0.0" xmlns="http://www.opengis.net/wmts/1.0"
                      xmlns:ows="http://www.opengis.net/ows/1.1"
                      xmlns:xlink="http://www.w3.org/1999/xlink">
          <ows:ServiceIdentification><ows:Title>TopPlusOpen</ows:Title></ows:ServiceIdentification>
          <Contents>
            <Layer>
              <ows:Title>TopPlusOpen</ows:Title>
              <ows:Identifier>web</ows:Identifier>
              <Style isDefault="true"><ows:Identifier>default</ows:Identifier></Style>
              <Format>image/png</Format>
              <TileMatrixSetLink><TileMatrixSet>WEBMERCATOR</TileMatrixSet></TileMatrixSetLink>
              <ResourceURL format="image/png" resourceType="tile"
                template="https://sgx.geodatenzentrum.de/wmts_topplus_open/tile/1.0.0/web/{Style}/{TileMatrixSet}/{TileMatrix}/{TileRow}/{TileCol}.png"/>
            </Layer>
            <TileMatrixSet>
              <ows:Identifier>WEBMERCATOR</ows:Identifier>
              <ows:SupportedCRS>urn:ogc:def:crs:EPSG::3857</ows:SupportedCRS>
              <TileMatrix><ows:Identifier>00</ows:Identifier></TileMatrix>
              <TileMatrix><ows:Identifier>01</ows:Identifier></TileMatrix>
              <TileMatrix><ows:Identifier>02</ows:Identifier></TileMatrix>
            </TileMatrixSet>
          </Contents>
        </Capabilities>
    """.trimIndent()

    @Test
    fun `parses a REST-only WMTS document with no OperationsMetadata`() {
        val parsed = success(wmtsRest)
        assertEquals(1, parsed.layers.size)
        val layer = parsed.layers.single()
        assertEquals("web", layer.name)
        assertEquals("TopPlusOpen", layer.title)
    }

    @Test
    fun `REST WMTS template substitutes style, matrix set, and zoom placeholder`() {
        val template = success(wmtsRest).layers.single().template
        assertEquals(
            "https://sgx.geodatenzentrum.de/wmts_topplus_open/tile/1.0.0/web/default/WEBMERCATOR/{z:02}/{y}/{x}.png",
            template,
        )
    }

    /**
     * A matrix set whose first level is really [firstZoom], whatever it is called.
     *
     * The scale denominator is the only thing that says so, which is the point of the
     * tests below: an offset set looks identical until you read it.
     */
    private fun matrixSet(id: String, ids: List<String>, firstZoom: Int): String {
        val matrices = ids.mapIndexed { i, name ->
            val denominator = 559082264.0287178 / (1L shl (firstZoom + i))
            "<TileMatrix><ows:Identifier>$name</ows:Identifier>" +
                "<ScaleDenominator>$denominator</ScaleDenominator></TileMatrix>"
        }
        return "<TileMatrixSet><ows:Identifier>$id</ows:Identifier>" +
            "<ows:SupportedCRS>urn:ogc:def:crs:EPSG::3857</ows:SupportedCRS>" +
            matrices.joinToString("") + "</TileMatrixSet>"
    }

    private fun twoMatrixSets(links: List<String>, sets: List<String>) = """
        <Capabilities version="1.0.0" xmlns="http://www.opengis.net/wmts/1.0"
                      xmlns:ows="http://www.opengis.net/ows/1.1"
                      xmlns:xlink="http://www.w3.org/1999/xlink">
          <ows:ServiceIdentification><ows:Title>Tiles</ows:Title></ows:ServiceIdentification>
          <ows:OperationsMetadata>
            <ows:Operation name="GetTile">
              <ows:DCP><ows:HTTP>
                <ows:Get xlink:href="https://example.org/wmts">
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
              <Format>image/png</Format>
              ${links.joinToString("") { "<TileMatrixSetLink><TileMatrixSet>$it</TileMatrixSet></TileMatrixSetLink>" }}
            </Layer>
            ${sets.joinToString("")}
          </Contents>
        </Capabilities>
    """.trimIndent()

    @Test
    fun `refuses a matrix set whose levels are not the zoom they are named after`() {
        // basemap.de's DE_EPSG_3857_ADV shape: level 00 is really zoom 5. Substituting
        // the zoom would ask for a level five steps away and draw the wrong ground.
        val parsed = CapabilitiesParser.parse(
            twoMatrixSets(
                links = listOf("OFFSET"),
                sets = listOf(matrixSet("OFFSET", listOf("00", "01", "02", "03"), firstZoom = 5)),
            ),
        )
        val success = parsed as CapabilitiesResult.Success
        assertTrue(success.layers.toString(), success.layers.isEmpty())
        assertTrue(success.skipped.single().reason, success.skipped.single().reason.contains("zoom level"))
    }

    @Test
    fun `prefers the deepest matrix set that names its levels correctly`() {
        // The offset set is linked first, as basemap.de links it. Taking the first
        // WebMercator set would pick it and cap the source four levels short besides.
        val parsed = success(
            twoMatrixSets(
                links = listOf("OFFSET", "GLOBAL"),
                sets = listOf(
                    matrixSet("OFFSET", listOf("00", "01", "02", "03"), firstZoom = 5),
                    matrixSet("GLOBAL", listOf("00", "01", "02", "03", "04", "05"), firstZoom = 0),
                ),
            ),
        )
        val template = parsed.layers.single().template
        assertTrue(template, template.contains("&TILEMATRIXSET=GLOBAL"))
        assertTrue(template, template.contains("&TILEMATRIX={z:02}"))
    }

    @Test
    fun `a set that declares no scale denominators is taken at its word`() {
        // The existing fixtures omit the field; they must keep working, because absence
        // is a loose server rather than evidence of an offset.
        val parsed = success(wmts(listOf("0", "1", "2")))
        assertTrue(parsed.layers.single().template.contains("&TILEMATRIX={z}"))
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
    fun `refuses scale denominators dressed as a prefix and an integer`() {
        // "1:" + 500000 parses as prefix-and-integer and would yield "1:{z}", a template
        // addressing a level no server has. The bound on real zoom levels rejects it.
        assertNull(CapabilitiesParser.zoomTemplateFor(listOf("1:500000", "1:250000")))
        assertNull(CapabilitiesParser.zoomTemplateFor(listOf("scale1000", "scale2000")))
    }

    @Test
    fun `refuses levels listed coarse to fine, since the order carries the meaning`() {
        assertNull(CapabilitiesParser.zoomTemplateFor(listOf("18", "17", "16")))
        assertNull(CapabilitiesParser.zoomTemplateFor(listOf("L3", "L2", "L1")))
    }

    @Test
    fun `accepts a set that does not start at zero`() {
        // The identifier is the zoom level whatever the set begins at, so the template
        // holds; only levels beyond what can be served are out of bounds.
        assertEquals("{z}", CapabilitiesParser.zoomTemplateFor(listOf("5", "6", "7")))
    }

    @Test
    fun `expresses zero-padded levels rather than refusing them`() {
        // How TopPlusOpen, Kartverket and pdok index their levels. Asking such a server
        // for level 0 gets nothing: the identifier it published is "00".
        assertEquals("{z:02}", CapabilitiesParser.zoomTemplateFor(listOf("00", "01", "02", "18")))
        assertEquals("L{z:02}", CapabilitiesParser.zoomTemplateFor(listOf("L00", "L01", "L02")))
        assertEquals(
            "EPSG:3857:{z:03}",
            CapabilitiesParser.zoomTemplateFor(listOf("EPSG:3857:000", "EPSG:3857:001")),
        )
    }

    @Test
    fun `refuses levels of mixed width, which are neither plain nor padded`() {
        // "0", "1", ... "10" is plain; "00", "01", ... "10" is padded; a set mixing the
        // two spellings cannot be reproduced by either template.
        assertNull(CapabilitiesParser.zoomTemplateFor(listOf("0", "01", "02")))
        assertNull(CapabilitiesParser.zoomTemplateFor(listOf("00", "1", "02")))
    }

    @Test
    fun `refuses what has no template form rather than inventing levels`() {
        assertNull(CapabilitiesParser.zoomTemplateFor(emptyList()))
        assertNull(CapabilitiesParser.zoomTemplateFor(listOf("L0", "M1")))
        assertNull(CapabilitiesParser.zoomTemplateFor(listOf("tiny", "small")))
        assertNull(CapabilitiesParser.zoomTemplateFor(listOf("-1", "0", "1")))
    }
}

class CapabilitiesFailureTest {

    @Test
    fun `reports unparseable input rather than throwing`() {
        assertTrue(CapabilitiesParser.parse("not xml at all") is CapabilitiesResult.Failure)
        assertTrue(CapabilitiesParser.parse("") is CapabilitiesResult.Failure)
    }

    @Test
    fun `surfaces a service exception instead of calling it an unknown document`() {
        val xml = """<?xml version="1.0"?>
            <ServiceExceptionReport><ServiceException code="InvalidFormat">
            Layer not defined</ServiceException></ServiceExceptionReport>"""
        val result = CapabilitiesParser.parse(xml)
        assertTrue(result is CapabilitiesResult.Failure)
        assertTrue((result as CapabilitiesResult.Failure).message.contains("Layer not defined"))
    }

    @Test
    fun `rejects a document that is valid XML but not capabilities`() {
        val result = CapabilitiesParser.parse("<html><body>Hello</body></html>")
        assertTrue(result is CapabilitiesResult.Failure)
        assertTrue((result as CapabilitiesResult.Failure).message.contains("not a WMS or WMTS", true))
    }
}
