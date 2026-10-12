package de.codevoid.wmsproxy.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryMatchTest {

    private val transportation = LibraryEntry(
        name = "USGS — transportation",
        url = "https://carto.nationalmap.gov/arcgis/services/transportation/MapServer/WMSServer?SERVICE=WMS&REQUEST=GetCapabilities",
    )
    private val tiles = LibraryEntry(name = "OpenRailwayMap", url = "https://tiles.openrailwaymap.org/standard/{z}/{x}/{y}.png")
    private val library = listOf(transportation, tiles)

    private fun same(a: String, b: String) = assertEquals(LibraryMatch.keyOf(a), LibraryMatch.keyOf(b))
    private fun different(a: String, b: String) = assertNotEquals(LibraryMatch.keyOf(a), LibraryMatch.keyOf(b))

    @Test
    fun `the exact address finds its entry`() {
        assertEquals(transportation, LibraryMatch.find(transportation.url, library))
    }

    @Test
    fun `scheme, case and the capabilities query do not tell services apart`() {
        assertEquals(
            transportation,
            LibraryMatch.find("http://CARTO.nationalmap.gov/arcgis/services/transportation/MapServer/WMSServer?request=getcapabilities&service=wms&version=1.3.0", library),
        )
        same("https://example.org/geoserver/ows?SERVICE=WMS&REQUEST=GetCapabilities", "https://example.org/geoserver/ows/")
    }

    @Test
    fun `an ArcGIS service is one service as REST description, WMS and WMTS`() {
        assertEquals(transportation, LibraryMatch.find("https://carto.nationalmap.gov/arcgis/rest/services/transportation/MapServer?f=json", library))
        same(
            "https://basemap.nationalmap.gov/arcgis/rest/services/USGSTopo/MapServer?f=pjson",
            "https://basemap.nationalmap.gov/arcgis/rest/services/USGSTopo/MapServer/WMTS/1.0.0/WMTSCapabilities.xml",
        )
    }

    @Test
    fun `a GeoServer answers the same on its wms and its ows path`() {
        same("https://www.mrlc.gov/geoserver/mrlc_display/wms?SERVICE=WMS&REQUEST=GetCapabilities", "https://www.mrlc.gov/geoserver/mrlc_display/ows")
    }

    @Test
    fun `a template pasted encoded or in capitals finds its entry`() {
        assertEquals(tiles, LibraryMatch.find("https://tiles.openrailwaymap.org/standard/%7Bz%7D/%7Bx%7D/%7By%7D.png", library))
        assertEquals(tiles, LibraryMatch.find("http://tiles.openrailwaymap.org/standard/{Z}/{X}/{Y}.png", library))
    }

    @Test
    fun `anything that may pick another map keeps services apart`() {
        assertNull(LibraryMatch.find("https://carto.nationalmap.gov/arcgis/services/structures/MapServer/WMSServer", library))
        assertNull(LibraryMatch.find("https://tiles.openrailwaymap.org/maxspeed/{z}/{x}/{y}.png", library))
        different("https://s.example/cgi-bin/mapserv?map=/roads.map&SERVICE=WMS", "https://s.example/cgi-bin/mapserv?map=/rivers.map&SERVICE=WMS")
        different("https://s.example/MapServer/export?layers=show:1", "https://s.example/MapServer/export?layers=show:2")
    }
}
