package de.codevoid.wmsproxy.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class ServiceReaderTest {

    private val wms = """
        <?xml version="1.0"?>
        <WMS_Capabilities version="1.3.0" xmlns="http://www.opengis.net/wms" xmlns:xlink="http://www.w3.org/1999/xlink">
          <Service><Title>Roads</Title></Service>
          <Capability>
            <Request><GetMap><Format>image/png</Format>
              <DCPType><HTTP><Get><OnlineResource xlink:href="https://h/ows?"/></Get></HTTP></DCPType>
            </GetMap></Request>
            <Layer><CRS>EPSG:3857</CRS><Layer><Name>roads</Name><Title>Road network</Title></Layer></Layer>
          </Capability>
        </WMS_Capabilities>
    """.trimIndent()

    /** A WMTS whose levels are numbered 00, 01, 02: the parser gives it a padded zoom. */
    private val paddedWmts = """
        <Capabilities version="1.0.0" xmlns="http://www.opengis.net/wmts/1.0" xmlns:ows="http://www.opengis.net/ows/1.1" xmlns:xlink="http://www.w3.org/1999/xlink">
          <ows:ServiceIdentification><ows:Title>Tiles</ows:Title></ows:ServiceIdentification>
          <ows:OperationsMetadata><ows:Operation name="GetTile"><ows:DCP><ows:HTTP>
            <ows:Get xlink:href="https://t/wmts"><ows:Constraint name="GetEncoding"><ows:AllowedValues><ows:Value>KVP</ows:Value></ows:AllowedValues></ows:Constraint></ows:Get>
          </ows:HTTP></ows:DCP></ows:Operation></ows:OperationsMetadata>
          <Contents>
            <Layer>
              <ows:Identifier>topo</ows:Identifier><ows:Title>Topographic</ows:Title>
              <Style isDefault="true"><ows:Identifier>default</ows:Identifier></Style>
              <Format>image/png</Format>
              <TileMatrixSetLink><TileMatrixSet>web</TileMatrixSet></TileMatrixSetLink>
            </Layer>
            <TileMatrixSet>
              <ows:Identifier>web</ows:Identifier>
              <ows:SupportedCRS>urn:ogc:def:crs:EPSG::3857</ows:SupportedCRS>
              <TileMatrix><ows:Identifier>00</ows:Identifier></TileMatrix>
              <TileMatrix><ows:Identifier>01</ows:Identifier></TileMatrix>
              <TileMatrix><ows:Identifier>02</ows:Identifier></TileMatrix>
            </TileMatrixSet>
          </Contents>
        </Capabilities>
    """.trimIndent()

    private fun reply(status: Int, type: String?, body: String? = null) =
        ServiceReader.Reply(status, type, body?.let { ByteArrayInputStream(it.toByteArray()) })

    /** A server that answers only the addresses in [answers]; everything else is a 404. */
    private fun server(answers: Map<String, ServiceReader.Reply>, asked: MutableList<String> = mutableListOf()) =
        { url: String -> asked += url; answers[url] ?: reply(404, "text/html", "no") }

    private fun service(read: ServiceReader.Read): CachedService {
        assertTrue("expected a service, got $read", read is ServiceReader.Read.Service)
        return (read as ServiceReader.Read.Service).service
    }

    @Test
    fun `a tile template is a one-layer service and asks the server nothing`() {
        val asked = mutableListOf<String>()
        val read = service(ServiceReader.read("https://t/{z}/{x}/{y}.png", now = 5, get = server(emptyMap(), asked)))
        assertEquals(ServiceKind.XYZ, read.service)
        assertEquals(5L, read.fetchedAt)
        assertEquals(XyzTemplate.LAYER_NAME, read.layers.single().name)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `the addresses are tried in order and the one that answered is remembered`() {
        val asked = mutableListOf<String>()
        val caps = "https://h/ows?SERVICE=WMS&REQUEST=GetCapabilities"
        val read = service(ServiceReader.read("https://h/ows", now = 1, get = server(mapOf(caps to reply(200, "text/xml", wms)), asked)))
        assertEquals(listOf("https://h/ows", caps), asked)
        assertEquals("https://h/ows", read.url)
        assertEquals(caps, read.fetchedFrom)
        assertEquals("Roads", read.title)
        assertEquals(listOf("roads"), read.layers.map { it.name })
    }

    @Test
    fun `a padded zoom is stored plain when the server answers the plain form with an image`() {
        val caps = "https://t/wmts?SERVICE=WMTS&REQUEST=GetCapabilities"
        val padded = service(ServiceReader.read("https://t/wmts", now = 1, get = server(mapOf(caps to reply(200, "text/xml", paddedWmts)))))
        val sample = PlainZoom.sample(CapabilitiesResult.Success(ServiceKind.WMTS, "", padded.layers, emptyList()))!!
        assertTrue(sample, "TILEMATRIX=8" in sample)
        assertTrue(padded.layers.single().template.contains("{z:02}"))
        val plain = service(ServiceReader.read("https://t/wmts", now = 1, get = server(mapOf(caps to reply(200, "text/xml", paddedWmts), sample to reply(200, "image/png", "png")))))
        assertTrue(plain.layers.single().template.contains("TILEMATRIX={z}&"))
        // An answer that is not an image, or an error, leaves the padded form alone.
        val refused = service(ServiceReader.read("https://t/wmts", now = 1, get = server(mapOf(caps to reply(200, "text/xml", paddedWmts), sample to reply(200, "text/html", "<html/>")))))
        assertTrue(refused.layers.single().template.contains("{z:02}"))
    }

    @Test
    fun `the last failure is reported when no address answers with a document`() {
        val failed = ServiceReader.read("https://h/ows", now = 1, get = server(emptyMap()))
        assertEquals(ServiceReader.Read.Failed("Server returned HTTP 404"), failed)
        assertEquals(ServiceReader.Read.Failed("Enter a URL"), ServiceReader.read("  ", now = 1, get = server(emptyMap())))
        val thrown = ServiceReader.read("https://h/ows", now = 1) { throw IllegalStateException("no route") }
        assertEquals(ServiceReader.Read.Failed("IllegalStateException: no route"), thrown)
    }
}
