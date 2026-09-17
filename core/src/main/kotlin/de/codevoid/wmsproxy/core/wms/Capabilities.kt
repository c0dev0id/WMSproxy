package de.codevoid.wmsproxy.core.wms

/** A layer the proxy offers, as advertised to the client. */
data class ProxyLayer(
    val id: String,
    val title: String,
    val abstract: String = "",
)

/**
 * Generates the GetCapabilities document the proxy serves.
 *
 * Every advertised property is something the proxy can actually deliver. Tile-backed
 * layers are WebMercator only, because that is the grid their tiles are cut on, and a
 * request in any other CRS is refused rather than reprojected.
 *
 * Both versions are produced because clients differ in what they ask for, and the two
 * are not cosmetic variants of each other: 1.1.1 uses SRS, LatLonBoundingBox and a
 * DOCTYPE, while 1.3.0 uses CRS, EX_GeographicBoundingBox, a namespace, and orders
 * geographic coordinates latitude-first.
 */
object CapabilitiesWriter {

    private const val CRS_CODE = "EPSG:3857"

    /** The full WebMercator extent, which every tile-backed layer covers. */
    private const val MIN_LON = -180.0
    private const val MAX_LON = 180.0
    private const val MIN_LAT = -85.051129
    private const val MAX_LAT = 85.051129

    fun write(version: String, baseUrl: String, layers: List<ProxyLayer>): String =
        if (version.startsWith("1.1")) write111(baseUrl, layers) else write130(baseUrl, layers)

    private fun write130(baseUrl: String, layers: List<ProxyLayer>): String = """
<?xml version="1.0" encoding="UTF-8"?>
<WMS_Capabilities version="1.3.0" xmlns="http://www.opengis.net/wms"
                  xmlns:xlink="http://www.w3.org/1999/xlink"
                  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                  xsi:schemaLocation="http://www.opengis.net/wms http://schemas.opengis.net/wms/1.3.0/capabilities_1_3_0.xsd">
  <Service>
    <Name>WMS</Name>
    <Title>WMSproxy</Title>
    <Abstract>Local normalization gateway</Abstract>
    <OnlineResource xlink:href="${xml(baseUrl)}"/>
  </Service>
  <Capability>
    <Request>
      <GetCapabilities>
        <Format>text/xml</Format>
        <DCPType><HTTP><Get><OnlineResource xlink:href="${xml(baseUrl)}?"/></Get></HTTP></DCPType>
      </GetCapabilities>
      <GetMap>
        <Format>image/png</Format>
        <Format>image/jpeg</Format>
        <DCPType><HTTP><Get><OnlineResource xlink:href="${xml(baseUrl)}?"/></Get></HTTP></DCPType>
      </GetMap>
    </Request>
    <Exception><Format>XML</Format></Exception>
    <Layer>
      <Title>WMSproxy</Title>
      <CRS>$CRS_CODE</CRS>
${layers.joinToString("\n") { layer130(it) }}
    </Layer>
  </Capability>
</WMS_Capabilities>
""".trimStart()

    private fun layer130(layer: ProxyLayer): String = """
      <Layer queryable="0">
        <Name>${xml(layer.id)}</Name>
        <Title>${xml(layer.title)}</Title>
        <Abstract>${xml(layer.abstract)}</Abstract>
        <CRS>$CRS_CODE</CRS>
        <EX_GeographicBoundingBox>
          <westBoundLongitude>$MIN_LON</westBoundLongitude>
          <eastBoundLongitude>$MAX_LON</eastBoundLongitude>
          <southBoundLatitude>$MIN_LAT</southBoundLatitude>
          <northBoundLatitude>$MAX_LAT</northBoundLatitude>
        </EX_GeographicBoundingBox>
        <BoundingBox CRS="$CRS_CODE" minx="-20037508.342789244" miny="-20037508.342789244" maxx="20037508.342789244" maxy="20037508.342789244"/>
      </Layer>
""".trim('\n')

    private fun write111(baseUrl: String, layers: List<ProxyLayer>): String = """
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE WMT_MS_Capabilities SYSTEM "http://schemas.opengis.net/wms/1.1.1/WMS_MS_Capabilities.dtd">
<WMT_MS_Capabilities version="1.1.1" xmlns:xlink="http://www.w3.org/1999/xlink">
  <Service>
    <Name>OGC:WMS</Name>
    <Title>WMSproxy</Title>
    <Abstract>Local normalization gateway</Abstract>
    <OnlineResource xlink:href="${xml(baseUrl)}"/>
  </Service>
  <Capability>
    <Request>
      <GetCapabilities>
        <Format>application/vnd.ogc.wms_xml</Format>
        <DCPType><HTTP><Get><OnlineResource xlink:href="${xml(baseUrl)}?"/></Get></HTTP></DCPType>
      </GetCapabilities>
      <GetMap>
        <Format>image/png</Format>
        <Format>image/jpeg</Format>
        <DCPType><HTTP><Get><OnlineResource xlink:href="${xml(baseUrl)}?"/></Get></HTTP></DCPType>
      </GetMap>
    </Request>
    <Exception><Format>application/vnd.ogc.se_xml</Format></Exception>
    <Layer>
      <Title>WMSproxy</Title>
      <SRS>$CRS_CODE</SRS>
${layers.joinToString("\n") { layer111(it) }}
    </Layer>
  </Capability>
</WMT_MS_Capabilities>
""".trimStart()

    private fun layer111(layer: ProxyLayer): String = """
      <Layer queryable="0">
        <Name>${xml(layer.id)}</Name>
        <Title>${xml(layer.title)}</Title>
        <Abstract>${xml(layer.abstract)}</Abstract>
        <SRS>$CRS_CODE</SRS>
        <LatLonBoundingBox minx="$MIN_LON" miny="$MIN_LAT" maxx="$MAX_LON" maxy="$MAX_LAT"/>
        <BoundingBox SRS="$CRS_CODE" minx="-20037508.342789244" miny="-20037508.342789244" maxx="20037508.342789244" maxy="20037508.342789244"/>
      </Layer>
""".trim('\n')

    /** A service exception in the form the requested version expects. */
    fun exception(version: String, message: String): String =
        if (version.startsWith("1.1")) {
            """
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE ServiceExceptionReport SYSTEM "http://schemas.opengis.net/wms/1.1.1/exception_1_1_1.dtd">
<ServiceExceptionReport version="1.1.1">
  <ServiceException>${xml(message)}</ServiceException>
</ServiceExceptionReport>
""".trimStart()
        } else {
            """
<?xml version="1.0" encoding="UTF-8"?>
<ServiceExceptionReport version="1.3.0" xmlns="http://www.opengis.net/ogc">
  <ServiceException>${xml(message)}</ServiceException>
</ServiceExceptionReport>
""".trimStart()
        }

    private fun xml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
