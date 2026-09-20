package de.codevoid.wmsproxy.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DmdSyncTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun xyz(source: String, template: String) =
        TileLayer(source = source, urlTemplate = template)

    @Test
    fun `splits a proxy template into origin and tile path with uppercase placeholders`() {
        val split = DmdSync.splitTemplate(
            "https://local.codevoid.de:8443/tileproxy/dwd/radar/{z}/{x}/{y}.png",
        )
        assertEquals("https://local.codevoid.de:8443/tileproxy/dwd/radar", split.url)
        assertEquals("/{Z}/{X}/{Y}.png", split.tilePath)
        assertFalse(split.isWms)
    }

    @Test
    fun `splits a query-style xyz template at the segment before the zoom`() {
        // The live Google form: the split lands on the last slash before {Z}.
        val split = DmdSync.splitTemplate("https://mt1.google.com/vt/lyrs=y&x={x}&y={y}&z={z}")
        assertEquals("https://mt1.google.com/vt", split.url)
        assertEquals("/lyrs=y&x={X}&y={Y}&z={Z}", split.tilePath)
    }

    @Test
    fun `a bbox template splits at the query and is flagged wms`() {
        val split = DmdSync.splitTemplate(
            "https://example.com/geoserver/ows?SERVICE=WMS&BBOX={bbox}",
        )
        assertEquals("https://example.com/geoserver/ows", split.url)
        assertEquals("?SERVICE=WMS&BBOX={BBOX}", split.tilePath)
        assertTrue(split.isWms)
    }

    @Test
    fun `a template with no zoom placeholder gets the default tile path`() {
        val split = DmdSync.splitTemplate("https://example.com/tiles")
        assertEquals("https://example.com/tiles", split.url)
        assertEquals("/{Z}/{X}/{Y}.png", split.tilePath)
    }

    @Test
    fun `layer id is stable and escapes the path separator`() {
        assertEquals("cl_wmsproxy_dwd_radar", DmdSync.layerId("dwd/radar"))
        assertEquals("cl_wmsproxy_osm", DmdSync.layerId("osm"))
    }

    @Test
    fun `a plain xyz source is direct-compatible`() = with(DmdSync) {
        assertTrue(xyz("osm", "https://a.tile.osm.org/{z}/{x}/{y}.png").directCompatible())
    }

    @Test
    fun `sources needing a rewrite are not direct-compatible, and say why`() = with(DmdSync) {
        assertEquals(DirectBlocker.QUADKEY, xyz("q", "https://s/{q}").directBlocker())
        assertEquals(DirectBlocker.PADDED_ZOOM, xyz("pad", "https://s/{z:02}/{x}/{y}.png").directBlocker())
        assertEquals(
            DirectBlocker.SUBDOMAINS,
            xyz("sub", "https://{s}.s/{z}/{x}/{y}.png").copy(subdomains = listOf("a", "b")).directBlocker(),
        )
        assertEquals(
            DirectBlocker.FLIPPED_ROWS,
            xyz("tms", "https://s/{z}/{x}/{y}.png").copy(flipY = true).directBlocker(),
        )
        assertEquals(
            DirectBlocker.REFERER,
            xyz("ref", "https://s/{z}/{x}/{y}.png").copy(referer = "https://r").directBlocker(),
        )
        assertEquals(DirectBlocker.NO_TILE_INDEX, xyz("odd", "https://s/{z}/{x}").directBlocker())
        assertFalse(xyz("pad", "https://s/{z:02}/{x}/{y}.png").directCompatible())
    }

    @Test
    fun `a wms template is direct-compatible, since DMD only ever asks for WebMercator`() = with(DmdSync) {
        assertTrue(xyz("wms", "https://s?VERSION=1.3.0&CRS=EPSG:3857&BBOX={bbox}").directCompatible())
    }

    @Test
    fun `a wms layer carries its layer name and version for DMD`() {
        val layer = DmdSync.toDmdLayer(
            "Charging",
            "mobidata/charge_points",
            "https://api.mobidata-bw.de/geoserver/ows?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap" +
                "&LAYERS=MobiData-BW%3Acharge_points&STYLES=&CRS=EPSG:3857&BBOX={bbox}" +
                "&WIDTH=256&HEIGHT=256&FORMAT=image%2Fpng&TRANSPARENT=TRUE",
        )
        assertTrue(layer.isWms)
        assertEquals("https://api.mobidata-bw.de/geoserver/ows", layer.url)
        assertTrue(layer.tilePath.startsWith("?SERVICE=WMS"))
        assertTrue(layer.tilePath.contains("BBOX={BBOX}"))
        assertEquals("MobiData-BW:charge_points", layer.wmsLayer)
        assertEquals("1.3.0", layer.wmsVersion)
    }

    @Test
    fun `an xyz layer leaves the wms fields at DMD's defaults`() {
        val layer = DmdSync.toDmdLayer("OSM", "osm", "https://a.tile.osm.org/{z}/{x}/{y}.png")
        assertFalse(layer.isWms)
        assertEquals("", layer.wmsLayer)
        assertEquals("1.1.1", layer.wmsVersion)
    }

    @Test
    fun `query parameters are read by name whatever their case, and a bad escape is kept raw`() {
        val params = DmdSync.queryParameters("?layers=a%20b&Version=1.1.1&odd=%zz&BBOX={BBOX}")
        assertEquals("a b", params["LAYERS"])
        assertEquals("1.1.1", params["VERSION"])
        assertEquals("%zz", params["ODD"])
        assertEquals("{BBOX}", params["BBOX"])
    }

    @Test
    fun `merge keeps foreign layers and appends ours`() {
        val server = """{"success":true,"layers":[{"id":"other","name":"Their Map","url":"u","tilePath":"/p"}]}"""
        val ours = listOf(DmdSync.toDmdLayer("Radar", "dwd/radar", "https://p/{z}/{x}/{y}.png"))

        val layers = json.parseToJsonElement(DmdSync.mergeForPush(server, ours)).jsonObject["layers"]!!.jsonArray
        assertEquals(2, layers.size)
        assertEquals("Their Map", layers[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("Radar", layers[1].jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `merge overwrites a foreign layer sharing our name`() {
        val server = """{"layers":[{"id":"stale","name":"Radar","url":"old","tilePath":"/old"}]}"""
        val ours = listOf(DmdSync.toDmdLayer("Radar", "dwd/radar", "https://p/{z}/{x}/{y}.png"))

        val layers = json.parseToJsonElement(DmdSync.mergeForPush(server, ours)).jsonObject["layers"]!!.jsonArray
        assertEquals(1, layers.size)
        assertEquals("cl_wmsproxy_dwd_radar", layers[0].jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `merge overwrites a layer sharing our id even under a new name`() {
        // A source renamed since the last sync: same id, different name — still ours.
        val server = """{"layers":[{"id":"cl_wmsproxy_dwd_radar","name":"Old Name","url":"o","tilePath":"/o"}]}"""
        val ours = listOf(DmdSync.toDmdLayer("New Name", "dwd/radar", "https://p/{z}/{x}/{y}.png"))

        val layers = json.parseToJsonElement(DmdSync.mergeForPush(server, ours)).jsonObject["layers"]!!.jsonArray
        assertEquals(1, layers.size)
        assertEquals("New Name", layers[0].jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `foreign entries are the account's own layers, verbatim, and never ours`() {
        val theirs = """{"id":"cl_abc","name":"Theirs","isWms":true,"wmsLayer":"x","extra":1}"""
        val server = """{"layers":[{"id":"cl_wmsproxy_osm","name":"OSM"},$theirs,{"name":"no id"}]}"""
        assertEquals(listOf(theirs, """{"name":"no id"}"""), DmdSync.foreignEntries(server))
        assertEquals(emptyList<String>(), DmdSync.foreignEntries("not json"))
    }

    @Test
    fun `a malformed server body yields just our layers`() {
        val ours = listOf(DmdSync.toDmdLayer("Radar", "dwd/radar", "https://p/{z}/{x}/{y}.png"))
        val layers = json.parseToJsonElement(DmdSync.mergeForPush("not json", ours)).jsonObject["layers"]!!.jsonArray
        assertEquals(1, layers.size)
    }
}
