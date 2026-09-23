package de.codevoid.wmsproxy.core

import de.codevoid.wmsproxy.core.http.queryParameters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DmdSyncTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun xyz(source: String, template: String) =
        TileLayer(source = source, urlTemplate = template)

    private val radar = DmdSync.toDmdLayer("Radar", "dwd/radar", "https://p/{z}/{x}/{y}.png")

    private fun merged(server: String, vararg ours: DmdLayer): JsonArray =
        json.parseToJsonElement(DmdSync.mergeForPush(server, ours.toList())).jsonObject["layers"]!!.jsonArray

    private fun JsonArray.field(index: Int, name: String) = this[index].jsonObject[name]!!.jsonPrimitive.content

    @Test
    fun `a tile layer is the whole template, as DMD's dialog stores a pasted address`() {
        // The address that failed split and worked pasted: row before column, no extension.
        val arcgis = "https://services.arcgis.com/v01gqwM5QqNysAAi/ArcGIS/rest/services/" +
            "PADUS3_0PublicAccess/MapServer/tile/{z}/{y}/{x}"
        val layer = DmdSync.toDmdLayer("PAD-US", "usgs/padus", arcgis)
        assertEquals(arcgis, layer.url)
        assertFalse(layer.isWms)
        // The query-style form and the proxy's own go the same way, placeholders as typed.
        val google = "https://mt1.google.com/vt/lyrs=y&x={x}&y={y}&z={z}"
        assertEquals(google, DmdSync.toDmdLayer("G", "g", google).url)
        val proxy = "https://local.codevoid.de:8443/tileproxy/dwd/radar/{z}/{x}/{y}.png"
        assertEquals(proxy, DmdSync.toDmdLayer("R", "dwd/radar", proxy).url)
    }

    @Test
    fun `a wms layer keeps the endpoint in url and the measured GetMap in tilePath`() {
        val layer = DmdSync.toDmdLayer(
            "Charging",
            "mobidata/charge_points",
            "https://api.mobidata-bw.de/geoserver/ows?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap" +
                "&LAYERS=MobiData-BW%3Acharge_points&STYLES=&CRS=EPSG:3857&BBOX={bbox}" +
                "&WIDTH=256&HEIGHT=256&FORMAT=image%2Fpng&TRANSPARENT=TRUE",
        )
        assertTrue(layer.isWms)
        assertEquals("https://api.mobidata-bw.de/geoserver/ows", layer.url)
        assertEquals(
            "?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=MobiData-BW%3Acharge_points&STYLES=" +
                "&CRS=EPSG:3857&BBOX={BBOX}&WIDTH=256&HEIGHT=256&FORMAT=image%2Fpng&TRANSPARENT=TRUE",
            layer.tilePath,
        )
        assertEquals("MobiData-BW:charge_points", layer.wmsLayer)
        assertEquals("1.3.0", layer.wmsVersion)
    }

    @Test
    fun `a tile layer is encoded without a tilePath key, in DMD's field order`() {
        val wms = DmdSync.toDmdLayer("C", "m/c", "https://h/ows?REQUEST=GetMap&VERSION=1.3.0&LAYERS=a&BBOX={bbox}")
        val layers = merged("{}", radar, wms)
        assertEquals(
            listOf("id", "name", "url", "keyName", "apiKey", "isWms", "wmsLayer", "wmsVersion", "enabled", "maxZoom"),
            layers[0].jsonObject.keys.toList(),
        )
        assertEquals("?REQUEST=GetMap&VERSION=1.3.0&LAYERS=a&BBOX={BBOX}", layers.field(1, "tilePath"))
    }

    @Test
    fun `an export template carries the bbox mark too, with nothing for the planner's fields`() {
        val export = "https://h/arcgis/rest/services/x/MapServer/export?bbox={bbox}&bboxSR=3857&imageSR=3857" +
            "&size=256,256&format=png32&transparent=true&layers=show:1&f=image"
        val layer = DmdSync.toDmdLayer("Roads", "usfs/1", export)
        assertEquals("https://h/arcgis/rest/services/x/MapServer/export", layer.url)
        assertEquals(
            "?bbox={BBOX}&bboxSR=3857&imageSR=3857&size=256,256&format=png32&transparent=true&layers=show:1&f=image",
            layer.tilePath,
        )
        assertTrue(layer.isWms)
        assertEquals("", layer.wmsLayer)
        assertEquals("1.1.1", layer.wmsVersion)
    }

    @Test
    fun `layer id is stable and escapes the path separator`() {
        assertEquals("cl_wmsproxy_dwd_radar", DmdSync.layerId("dwd/radar"))
        assertEquals("cl_wmsproxy_osm", DmdSync.layerId("osm"))
    }

    @Test
    fun `a plain xyz source has nothing blocking direct`() {
        assertNull(xyz("osm", "https://a.tile.osm.org/{z}/{x}/{y}.png").directBlocker())
    }

    @Test
    fun `sources needing a rewrite are blocked, and say why`() {
        assertEquals(Rewrite.QUADKEY, xyz("q", "https://s/{q}").directBlocker())
        assertEquals(Rewrite.PADDED_ZOOM, xyz("pad", "https://s/{z:02}/{x}/{y}.png").directBlocker())
        assertEquals(
            Rewrite.SUBDOMAINS,
            xyz("sub", "https://{s}.s/{z}/{x}/{y}.png").copy(subdomains = listOf("a", "b")).directBlocker(),
        )
        assertEquals(
            Rewrite.FLIPPED_ROWS,
            xyz("tms", "https://s/{z}/{x}/{y}.png").copy(flipY = true).directBlocker(),
        )
        assertEquals(
            Rewrite.REFERER,
            xyz("ref", "https://s/{z}/{x}/{y}.png").copy(referer = "https://r").directBlocker(),
        )
    }

    @Test
    fun `a plain-http source is blocked, since both readers refuse cleartext`() {
        // The proxy's certificate is what makes such a source reachable at all; without
        // it DMD's network security policy and the planner's mixed-content rule both bite.
        assertEquals(Rewrite.CLEARTEXT, xyz("plain", "http://tile.example/{z}/{x}/{y}.png").directBlocker())
        assertEquals(Rewrite.CLEARTEXT, xyz("wms", "http://s?VERSION=1.3.0&CRS=EPSG:3857&BBOX={bbox}").directBlocker())
    }

    @Test
    fun `a wms template is not blocked, since DMD only ever asks for WebMercator`() {
        assertNull(xyz("wms", "https://s?VERSION=1.3.0&CRS=EPSG:3857&BBOX={bbox}").directBlocker())
    }

    @Test
    fun `direct is granted only when asked for and nothing blocks it`() {
        val plain = xyz("osm", "https://a.tile.osm.org/{z}/{x}/{y}.png")
        val padded = xyz("pad", "https://s/{z:02}/{x}/{y}.png")
        assertFalse(plain.sendsDirect(DmdSyncChoice()))
        assertTrue(plain.sendsDirect(DmdSyncChoice(direct = true)))
        assertFalse(padded.sendsDirect(DmdSyncChoice(direct = true)))
    }

    @Test
    fun `a missing choice is the default, proxied and included`() {
        assertEquals(DmdSyncChoice(), emptyMap<String, DmdSyncChoice>().choiceFor("osm"))
        assertEquals(
            DmdSyncChoice(direct = true),
            mapOf("osm" to DmdSyncChoice(direct = true)).choiceFor("osm"),
        )
    }

    @Test
    fun `layers for the account follow each source's choice`() {
        val plain = xyz("osm", "https://a.tile.osm.org/{z}/{x}/{y}.png").copy(title = "OSM")
        val padded = xyz("pad", "https://s/{z:02}/{x}/{y}.png")
        val off = xyz("off", "https://o/{z}/{x}/{y}.png")
        val choices = mapOf(
            "osm" to DmdSyncChoice(direct = true),
            "pad" to DmdSyncChoice(direct = true),
            "off" to DmdSyncChoice(enabled = false),
        )

        val layers = DmdSync.layersFor(listOf(plain, padded, off), choices::choiceFor) { "https://proxy/${it.path}/{z}/{x}/{y}.png" }

        assertEquals(listOf("OSM", "pad"), layers.map { it.name })
        // Direct honoured where nothing blocks it; the padded source falls back to the proxy.
        assertEquals("https://a.tile.osm.org/{z}/{x}/{y}.png", layers[0].url)
        assertEquals("https://proxy/pad/{z}/{x}/{y}.png", layers[1].url)
    }

    @Test
    fun `an export source becomes two entries, one in each reader's form`() {
        val export = "https://h/arcgis/rest/services/x/MapServer/export?bbox={bbox}&bboxSR=3857&imageSR=3857" +
            "&size=256,256&format=png32&transparent=true&layers=show:1&f=image"
        val source = xyz("usfs", export).copy(layer = "1", title = "Roads")
        val pushed = DmdSync.layersFor(listOf(source), { DmdSyncChoice(direct = true) }) { "unused" }
        assertEquals(listOf("Roads (DMD App)", "Roads (Hub Planner)"), pushed.map { it.name })
        val phone = pushed[0]
        assertEquals("cl_wmsproxy_usfs_1", phone.id)
        assertTrue(phone.isWms)
        assertEquals("https://h/arcgis/rest/services/x/MapServer/export", phone.url)
        val planner = pushed[1]
        assertEquals("cl_wmsproxy_usfs_1_planner", planner.id)
        assertFalse(planner.isWms)
        assertNull(planner.tilePath)
        assertEquals(export.replace("{bbox}", "{bbox-epsg-3857}"), planner.url)
        // A WMS GetMap and a tile template are one entry each, unsuffixed: both readers render them.
        val wms = xyz("w", "https://h/ows?REQUEST=GetMap&LAYERS=a&BBOX={bbox}").copy(title = "W")
        val tile = xyz("t", "https://h/{z}/{x}/{y}.png").copy(title = "T")
        assertEquals(listOf("W", "T"), DmdSync.layersFor(listOf(wms, tile), { DmdSyncChoice(direct = true) }) { "unused" }.map { it.name })
        // Through the proxy the address is a tile template, so an export source is one entry there too.
        assertEquals(1, DmdSync.layersFor(listOf(source), { DmdSyncChoice() }) { "https://p/{z}/{x}/{y}.png" }.size)
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
        val params = "https://h/p?layers=a%20b&Version=1.1.1&odd=%zz&BBOX={BBOX}".queryParameters()
        assertEquals("a b", params["LAYERS"])
        assertEquals("1.1.1", params["VERSION"])
        assertEquals("%zz", params["ODD"])
        assertEquals("{BBOX}", params["BBOX"])
    }

    @Test
    fun `merge keeps foreign layers and appends ours`() {
        val server = """{"success":true,"layers":[{"id":"other","name":"Their Map","url":"u","tilePath":"/p"}]}"""
        val layers = merged(server, radar)
        assertEquals(2, layers.size)
        assertEquals("Their Map", layers.field(0, "name"))
        assertEquals("Radar", layers.field(1, "name"))
    }

    @Test
    fun `merge overwrites a foreign layer sharing our name`() {
        val server = """{"layers":[{"id":"stale","name":"Radar","url":"old","tilePath":"/old"}]}"""
        val layers = merged(server, radar)
        assertEquals(1, layers.size)
        assertEquals("cl_wmsproxy_dwd_radar", layers.field(0, "id"))
    }

    @Test
    fun `merge overwrites a layer sharing our id even under a new name`() {
        // A source renamed since the last sync: same id, different name — still ours.
        val server = """{"layers":[{"id":"cl_wmsproxy_dwd_radar","name":"Old Name","url":"o","tilePath":"/o"}]}"""
        val layers = merged(server, DmdSync.toDmdLayer("New Name", "dwd/radar", "https://p/{z}/{x}/{y}.png"))
        assertEquals(1, layers.size)
        assertEquals("New Name", layers.field(0, "name"))
    }

    @Test
    fun `merge carries an entry that is not even an object through untouched`() {
        val layers = merged("""{"layers":[7,{"name":"x"}]}""", radar)
        assertEquals(listOf("7", """{"name":"x"}"""), layers.take(2).map { it.toString() })
        assertEquals(3, layers.size)
    }

    @Test
    fun `foreign entries are the account's own layers, verbatim, and never ours`() {
        val theirs = """{"id":"cl_abc","name":"Theirs","isWms":true,"wmsLayer":"x","extra":1}"""
        val server = """{"layers":[{"id":"cl_wmsproxy_osm","name":"OSM"},$theirs,{"name":"no id"}]}"""
        assertEquals(listOf(theirs, """{"name":"no id"}"""), DmdSync.foreignEntries(server))
        assertEquals(emptyList<String>(), DmdSync.foreignEntries("not json"))
    }

    @Test
    fun `the account dump is every layer, verbatim, ours included`() {
        val ours = """{"id":"cl_wmsproxy_osm","name":"OSM","url":"https://h/{z}/{x}/{y}.png"}"""
        val theirs = """{"id":"cl_abc","name":"Theirs","extra":1}"""
        assertEquals(listOf(ours, theirs), DmdSync.accountEntries("""{"layers":[$ours,$theirs]}"""))
        assertEquals(emptyList<String>(), DmdSync.accountEntries("not json"))
    }

    @Test
    fun `a malformed server body yields just our layers`() {
        assertEquals(1, merged("not json", radar).size)
    }

    @Test
    fun `choices round-trip, and an unreadable store is an empty one`() {
        val choices = mapOf("osm" to DmdSyncChoice(direct = true), "off" to DmdSyncChoice(enabled = false))
        assertEquals(choices, DmdSync.decodeChoices(DmdSync.encodeChoices(choices)))
        assertEquals(emptyMap<String, DmdSyncChoice>(), DmdSync.decodeChoices("not json"))
        assertEquals(
            mapOf("osm" to DmdSyncChoice()),
            DmdSync.decodeChoices("""{"osm":{"future":1}}"""),
        )
    }
}
