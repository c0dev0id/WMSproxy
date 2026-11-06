package de.codevoid.wmsproxy.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapLayerSyncTest {

    private fun xyz(source: String, template: String) =
        TileLayer(source = source, urlTemplate = template)

    private fun TileLayer.toMapLayer(): MapLayer =
        MapLayerSync.layersFor(listOf(this)) { "https://p/${it.path}/{z}/{x}/{y}.png" }.single()

    @Test
    fun `a plain xyz source has nothing blocking direct`() {
        assertNull(xyz("osm", "https://a.tile.osm.org/{z}/{x}/{y}.png").directBlocker())
        assertTrue(xyz("osm", "https://a.tile.osm.org/{z}/{x}/{y}.png").goesDirect())
    }

    @Test
    fun `padded zoom, flipped rows, subdomains and wms bbox all go direct`() {
        // The four rewrites the map-layer renderer handles itself.
        assertNull(xyz("pad", "https://s/{z:02}/{x}/{y}.png").directBlocker())
        assertNull(xyz("tms", "https://s/{z}/{x}/{y}.png").copy(flipY = true).directBlocker())
        assertNull(
            xyz("sub", "https://{s}.s/{z}/{x}/{y}.png").copy(subdomains = listOf("a", "b")).directBlocker(),
        )
        assertNull(xyz("wms", "https://s?VERSION=1.3.0&CRS=EPSG:3857&BBOX={bbox}").directBlocker())
    }

    @Test
    fun `quadkey, referer and cleartext still block direct`() {
        assertEquals(Rewrite.QUADKEY, xyz("q", "https://s/{q}").directBlocker())
        assertEquals(
            Rewrite.REFERER,
            xyz("ref", "https://s/{z}/{x}/{y}.png").copy(referer = "https://r").directBlocker(),
        )
        assertEquals(Rewrite.CLEARTEXT, xyz("plain", "http://s/{z}/{x}/{y}.png").directBlocker())
        assertFalse(xyz("q", "https://s/{q}").goesDirect())
    }

    @Test
    fun `a plain xyz layer goes direct with the template as its one tile`() {
        val layer = xyz("osm", "https://a.tile.osm.org/{z}/{x}/{y}.png").toMapLayer()
        assertEquals(listOf("https://a.tile.osm.org/{z}/{x}/{y}.png"), layer.tiles)
        assertEquals("xyz", layer.scheme)
        assertEquals("xyz", layer.source.type)
        assertEquals("https://a.tile.osm.org/{z}/{x}/{y}.png", layer.source.input)
        assertNull(layer.tokenMap)
    }

    @Test
    fun `a tms layer is marked scheme tms and keeps its template`() {
        val layer = xyz("tms", "https://s/{z}/{x}/{y}.png").copy(flipY = true).toMapLayer()
        assertEquals("tms", layer.scheme)
        assertEquals(listOf("https://s/{z}/{x}/{y}.png"), layer.tiles)
    }

    @Test
    fun `a padded-zoom layer emits a token map and normalises its tiles`() {
        val layer = xyz("pad", "https://s/{z:02}/{x}/{y}.png").toMapLayer()
        assertEquals(listOf("https://s/{z}/{x}/{y}.png"), layer.tiles)
        val tokens = layer.tokenMap?.z
        assertEquals(25, tokens?.size)
        assertEquals("00", tokens?.get(0))
        assertEquals("09", tokens?.get(9))
        assertEquals("10", tokens?.get(10))
        assertEquals("24", tokens?.get(24))
        // The source input keeps the template as the user typed it.
        assertEquals("https://s/{z:02}/{x}/{y}.png", layer.source.input)
    }

    @Test
    fun `a three-digit padded zoom produces three-digit tokens`() {
        // The regex captures 1–2 digits as the width value, so {z:3} means width 3.
        val tokens = xyz("pad", "https://s/{z:3}/{x}/{y}.png").toMapLayer().tokenMap?.z
        assertEquals("000", tokens?.get(0))
        assertEquals("024", tokens?.get(24))
    }

    @Test
    fun `a subdomain layer gets one tile entry per subdomain`() {
        val layer = xyz("sub", "https://{s}.s/{z}/{x}/{y}.png")
            .copy(subdomains = listOf("a", "b", "c"))
            .toMapLayer()
        assertEquals(
            listOf(
                "https://a.s/{z}/{x}/{y}.png",
                "https://b.s/{z}/{x}/{y}.png",
                "https://c.s/{z}/{x}/{y}.png",
            ),
            layer.tiles,
        )
        // The source input keeps {s} so the server knows the template was sharded.
        assertEquals("https://{s}.s/{z}/{x}/{y}.png", layer.source.input)
    }

    @Test
    fun `a wms GetMap layer carries source type wms and the LAYERS parameter`() {
        val template = "https://api.mobidata-bw.de/geoserver/ows?SERVICE=WMS&VERSION=1.3.0" +
            "&REQUEST=GetMap&LAYERS=MobiData-BW%3Acharge_points&STYLES=&CRS=EPSG:3857" +
            "&BBOX={bbox}&WIDTH=256&HEIGHT=256&FORMAT=image%2Fpng&TRANSPARENT=TRUE"
        val layer = xyz("mobi", template).toMapLayer()
        assertEquals("wms", layer.source.type)
        assertEquals("MobiData-BW:charge_points", layer.source.layer)
        assertEquals(template, layer.source.input)
        // Tile template has the {bbox} placeholder normalised to the renderer's spelling.
        assertEquals(1, layer.tiles.size)
        assertTrue(layer.tiles[0].contains("{bbox-epsg-3857}"))
        assertFalse(layer.tiles[0].contains("{bbox}"))
    }

    @Test
    fun `a wms GetMap without a LAYERS parameter still classifies as wms with no layer`() {
        val template = "https://s/ows?SERVICE=WMS&REQUEST=GetMap&CRS=EPSG:3857&BBOX={bbox}"
        val source = xyz("w", template).toMapLayer().source
        assertEquals("wms", source.type)
        assertNull(source.layer)
    }

    @Test
    fun `an arcgis export is classified as arcgis_mapserver with no layer field`() {
        val template = "https://h/arcgis/rest/services/x/MapServer/export?bbox={bbox}" +
            "&bboxSR=3857&imageSR=3857&size=256,256&format=png32&transparent=true" +
            "&layers=show:1&f=image"
        val layer = xyz("usfs", template).toMapLayer()
        assertEquals("arcgis_mapserver", layer.source.type)
        assertNull(layer.source.layer)
        assertEquals(template, layer.source.input)
        assertEquals(1, layer.tiles.size)
        assertTrue(layer.tiles[0].contains("{bbox-epsg-3857}"))
    }

    @Test
    fun `a blocked source goes through the proxy template with an xyz source`() {
        val quad = xyz("q", "https://s/{q}")
        val layer = quad.toMapLayer()
        assertEquals(listOf("https://p/q/{z}/{x}/{y}.png"), layer.tiles)
        assertEquals("xyz", layer.source.type)
        assertEquals("https://p/q/{z}/{x}/{y}.png", layer.source.input)
        // Proxy entries never flip or pad: the proxy answers XYZ even for a quadkey upstream.
        assertEquals("xyz", layer.scheme)
        assertNull(layer.tokenMap)
    }

    @Test
    fun `layersFor mixes direct and proxied entries in order and leaves ids unset`() {
        val direct = xyz("osm", "https://a/{z}/{x}/{y}.png")
        val proxied = xyz("q", "https://s/{q}")
        val padded = xyz("pad", "https://s/{z:02}/{x}/{y}.png")
        val layers = MapLayerSync.layersFor(listOf(direct, proxied, padded)) {
            "https://p/${it.path}/{z}/{x}/{y}.png"
        }
        assertTrue("layersFor must not stamp ids", layers.all { it.id == null })
        assertEquals("https://a/{z}/{x}/{y}.png", layers[0].tiles.single())
        assertEquals("https://p/q/{z}/{x}/{y}.png", layers[1].tiles.single())
        assertEquals("https://s/{z}/{x}/{y}.png", layers[2].tiles.single())
        assertNull(layers[0].tokenMap)
        assertNull(layers[1].tokenMap)
        assertEquals(25, layers[2].tokenMap?.z?.size)
    }

    @Test
    fun `display name falls back to the path when no title is set`() {
        val anon = xyz("osm", "https://a/{z}/{x}/{y}.png").toMapLayer()
        assertEquals("osm", anon.name)
        val titled = xyz("osm", "https://a/{z}/{x}/{y}.png").copy(title = "OpenStreetMap").toMapLayer()
        assertEquals("OpenStreetMap", titled.name)
    }

    @Test
    fun `accountEntries lists every layer with its id and its JSON text`() {
        val body = """
            {"layers":[
              {"id":"ml_ours","name":"OSM"},
              {"id":"ml_abc","name":"Theirs","extra":1},
              {"name":"no id"}
            ],"rev":9}
        """.trimIndent()
        val entries = MapLayerSync.accountEntries(body)
        assertEquals(listOf("ml_ours", "ml_abc", null), entries.map { it.id })
        assertTrue(entries[1].text.contains("\"extra\":1"))
        assertTrue(entries[2].text.contains("no id"))
    }

    @Test
    fun `malformed server bodies are the empty list rather than an exception`() {
        assertTrue(MapLayerSync.accountEntries("not json").isEmpty())
        assertTrue(MapLayerSync.accountEntries("").isEmpty())
        assertTrue(MapLayerSync.accountEntries("{}").isEmpty())
    }

    @Test
    fun `importedId is the id the Hub minted, or null when it took nothing`() {
        assertEquals("ml_a", MapLayerSync.importedId("""{"success":true,"imported":["ml_a"],"skipped":0,"skipped_ids":[]}"""))
        assertNull(MapLayerSync.importedId("""{"success":true,"imported":[],"skipped":1,"skipped_ids":[]}"""))
        assertNull(MapLayerSync.importedId("not json"))
    }

    private val proxy: (TileLayer) -> String = { "https://p/${it.path}/{z}/{x}/{y}.png" }

    @Test
    fun `plan updates registered layers the account still has and imports the rest`() {
        val plan = MapLayerSync.plan(
            loaded = listOf(xyz("osm", "https://a/{z}/{x}/{y}.png"), xyz("topo", "https://t/{z}/{x}/{y}.png")),
            proxyTemplate = proxy,
            registry = mapOf("osm" to "ml_osm", "topo" to "ml_gone"),
            accountIds = setOf("ml_osm", "ml_theirs"),
            fullSync = false,
        )
        assertEquals(setOf("osm", "topo"), plan.layers.keys)
        // ml_gone was deleted on the Hub: its path leaves the registry and is imported afresh.
        assertEquals(mapOf("osm" to "ml_osm"), plan.registry)
        assertTrue(plan.deletes.isEmpty())
    }

    @Test
    fun `plan deletes our layers that are no longer loaded and keeps foreign ones`() {
        val plan = MapLayerSync.plan(
            loaded = listOf(xyz("osm", "https://a/{z}/{x}/{y}.png")),
            proxyTemplate = proxy,
            registry = mapOf("osm" to "ml_osm", "old" to "ml_old"),
            accountIds = setOf("ml_osm", "ml_old", "ml_theirs"),
            fullSync = false,
        )
        assertEquals(mapOf("osm" to "ml_osm"), plan.registry)
        assertEquals(setOf("ml_old"), plan.deletes)
    }

    @Test
    fun `a full sync also deletes every layer that is not ours`() {
        val plan = MapLayerSync.plan(
            loaded = listOf(xyz("osm", "https://a/{z}/{x}/{y}.png")),
            proxyTemplate = proxy,
            registry = mapOf("osm" to "ml_osm", "old" to "ml_old"),
            accountIds = setOf("ml_osm", "ml_old", "ml_theirs"),
            fullSync = true,
        )
        assertEquals(mapOf("osm" to "ml_osm"), plan.registry)
        assertEquals(setOf("ml_old", "ml_theirs"), plan.deletes)
    }
}
