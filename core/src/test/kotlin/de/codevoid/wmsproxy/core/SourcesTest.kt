package de.codevoid.wmsproxy.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TileLayerTest {

    private val osm = TileLayer(
        source = "osm",
        title = "OpenStreetMap",
        urlTemplate = "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
    )

    @Test
    fun `path omits the layer segment when the provider has none`() {
        assertEquals("osm", osm.path)
        assertEquals("carto/light", osm.copy(source = "carto", layer = "light").path)
    }

    @Test
    fun `display name falls back to the path when there is no title`() {
        assertEquals("OpenStreetMap", osm.displayName)
        assertEquals("carto/light", osm.copy(source = "carto", layer = "light", title = " ").displayName)
    }

    @Test
    fun `expands a path-style template`() {
        assertEquals(
            "https://tile.openstreetmap.org/12/2152/1410.png",
            osm.urlFor(TileRef(12, 2152, 1410)),
        )
    }

    @Test
    fun `expands a query-style template, placeholders anywhere`() {
        val google = osm.copy(urlTemplate = "https://mt1.google.com/vt/lyrs=y&x={x}&y={y}&z={z}")
        assertEquals(
            "https://mt1.google.com/vt/lyrs=y&x=2152&y=1410&z=12",
            google.urlFor(TileRef(12, 2152, 1410)),
        )
    }

    @Test
    fun `flipY converts an XYZ row to the TMS row`() {
        val tms = osm.copy(flipY = true)
        // At z12 there are 4096 rows, so row 1410 from the north is 2685 from the south.
        assertEquals(
            "https://tile.openstreetmap.org/12/2152/2685.png",
            tms.urlFor(TileRef(12, 2152, 1410)),
        )
    }

    @Test
    fun `subdomain choice is deterministic, so the client cache stays useful`() {
        val carto = osm.copy(
            urlTemplate = "https://{s}.basemaps.cartocdn.com/light_all/{z}/{x}/{y}.png",
            subdomains = listOf("a", "b", "c", "d"),
        )
        val tile = TileRef(12, 2152, 1410)
        assertEquals(carto.urlFor(tile), carto.urlFor(tile))
        // (2152 + 1410) mod 4 == 2
        assertEquals(
            "https://c.basemaps.cartocdn.com/light_all/12/2152/1410.png",
            carto.urlFor(tile),
        )
    }

    @Test
    fun `expands a zoom padded to a fixed width`() {
        val padded = osm.copy(urlTemplate = "https://e.com/wmts?TILEMATRIX={z:02}&r={y}&c={x}")
        assertEquals(
            "https://e.com/wmts?TILEMATRIX=07&r=40&c=66",
            padded.urlFor(TileRef(7, 66, 40)),
        )
        // Wider than the number needs, and already wide enough, both come out right.
        assertEquals(
            "https://e.com/wmts?TILEMATRIX=18&r=40&c=66",
            padded.urlFor(TileRef(18, 66, 40)),
        )
    }

    @Test
    fun `the plain-zoom form of a padded template asks for the bare level`() {
        val padded = osm.copy(urlTemplate = "https://s/tiles/{z:02}/{y}/{x}.png")
        val plain = padded.withPlainZoom()!!
        assertEquals("https://s/tiles/{z}/{y}/{x}.png", plain.urlTemplate)
        assertEquals("https://s/tiles/5/16/17.png", plain.urlFor(TileRef(5, 17, 16)))
        assertEquals("https://s/tiles/05/16/17.png", padded.urlFor(TileRef(5, 17, 16)))
        assertNull(osm.withPlainZoom())
    }

    @Test
    fun `a padded zoom works with a prefix in front of it`() {
        val padded = osm.copy(urlTemplate = "https://e.com/EPSG:3857:{z:02}/{x}/{y}.png")
        assertEquals("https://e.com/EPSG:3857:04/2/3.png", padded.urlFor(TileRef(4, 2, 3)))
    }

    @Test
    fun `the proxy's rewrites are listed in the order they are applied`() {
        val all = osm.copy(urlTemplate = "http://{s}.s/{z:02}/{x}/{y}.png", flipY = true, referer = "https://r")
        assertEquals(
            listOf(Rewrite.PADDED_ZOOM, Rewrite.FLIPPED_ROWS, Rewrite.SUBDOMAINS, Rewrite.REFERER, Rewrite.CLEARTEXT),
            all.rewrites(),
        )
        assertEquals(listOf(Rewrite.CLEARTEXT), osm.copy(urlTemplate = "HTTP://s/{z}/{x}/{y}.png").rewrites())
        assertEquals(listOf(Rewrite.WMS_BBOX), osm.copy(urlTemplate = "https://s?BBOX={bbox}").rewrites())
        assertEquals(emptyList<Rewrite>(), osm.rewrites())
    }

    @Test
    fun `expands a quadkey template`() {
        val bing = osm.copy(urlTemplate = "https://t.example.com/tiles/{q}.jpeg")
        assertEquals(
            "https://t.example.com/tiles/${TileMath.quadKey(12, 2152, 1410)}.jpeg",
            bing.urlFor(TileRef(12, 2152, 1410)),
        )
    }
}

class TileLayerZoomRangeTest {

    private val layer = TileLayer(source = "s", urlTemplate = "https://e.com/{z}/{x}/{y}")

    @Test
    fun `an unmeasured source serves every zoom`() {
        for (z in 0..20) assertTrue("z$z", layer.serves(z))
        assertNull(layer.zoomRangeLabel())
    }

    @Test
    fun `a measured source serves its span and nothing outside it`() {
        val measured = layer.copy(minZoom = 8, maxZoom = 14)
        assertFalse(measured.serves(7))
        assertTrue(measured.serves(8))
        assertTrue(measured.serves(11))
        assertTrue(measured.serves(14))
        assertFalse(measured.serves(15))
        assertEquals("z8–z14", measured.zoomRangeLabel())
    }

    @Test
    fun `a half-open span limits only the end it names`() {
        assertFalse(layer.copy(minZoom = 5).serves(4))
        assertTrue(layer.copy(minZoom = 5).serves(20))
        assertTrue(layer.copy(maxZoom = 5).serves(0))
        assertFalse(layer.copy(maxZoom = 5).serves(6))
    }

    @Test
    fun `the range survives being stored`() {
        val measured = layer.copy(minZoom = 8, maxZoom = 14)
        val config = SourceConfig(listOf(measured))
        assertEquals(config, SourceCodec.decode(SourceCodec.encode(config)))
    }
}

class SourceValidatorTest {

    private val valid = TileLayer(
        source = "osm",
        title = "OpenStreetMap",
        urlTemplate = "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
    )

    @Test
    fun `a suggested name is valid by construction, whatever the title held`() {
        val name = SourceValidator.asPathSegment("Élan: Straße/Nord (2025)", fallback = "x")
        assertEquals("lan_Stra_e_Nord_2025", name)
        assertNull(SourceValidator.validate(TileLayer(source = name, urlTemplate = "https://s/{z}/{x}/{y}")))
        assertEquals("x", SourceValidator.asPathSegment("¿¡", fallback = "x"))
    }

    @Test
    fun `accepts a plain xyz source`() {
        assertNull(SourceValidator.validate(valid))
    }

    @Test
    fun `accepts a padded zoom placeholder as the zoom`() {
        assertNull(SourceValidator.validate(valid.copy(urlTemplate = "https://e.com/{z:02}/{x}/{y}")))
    }

    @Test
    fun `accepts a quadkey source without z x y`() {
        assertNull(SourceValidator.validate(valid.copy(urlTemplate = "https://e.com/{q}.png")))
    }

    @Test
    fun `rejects a name that would change the route it answers on`() {
        assertNotNull(SourceValidator.validate(valid.copy(source = "")))
        assertNotNull(SourceValidator.validate(valid.copy(source = "osm/extra")))
        assertNotNull(SourceValidator.validate(valid.copy(source = "my source")))
        assertNotNull(SourceValidator.validate(valid.copy(layer = "a/b")))
        // Present but empty is a mistake; absent is the way to say "no layer".
        assertNotNull(SourceValidator.validate(valid.copy(layer = "")))
        assertNull(SourceValidator.validate(valid.copy(layer = null)))
    }

    @Test
    fun `rejects a template that would fetch the wrong tile forever`() {
        assertNotNull(SourceValidator.validate(valid.copy(urlTemplate = "")))
        assertNotNull(SourceValidator.validate(valid.copy(urlTemplate = "https://e.com/{z}/{x}.png")))
        assertNotNull(SourceValidator.validate(valid.copy(urlTemplate = "https://e.com/tiles.png")))
    }

    @Test
    fun `rejects a template that is not http`() {
        assertNotNull(SourceValidator.validate(valid.copy(urlTemplate = "ftp://e.com/{z}/{x}/{y}")))
        assertNotNull(SourceValidator.validate(valid.copy(urlTemplate = "tile.openstreetmap.org/{z}/{x}/{y}")))
    }

    @Test
    fun `keeps the s placeholder and the subdomain list consistent`() {
        val sharded = valid.copy(urlTemplate = "https://{s}.e.com/{z}/{x}/{y}.png")
        assertNotNull(SourceValidator.validate(sharded))
        assertNull(SourceValidator.validate(sharded.copy(subdomains = listOf("a", "b"))))
        // Subdomains with nowhere to go are a silent no-op, so say so.
        assertNotNull(SourceValidator.validate(valid.copy(subdomains = listOf("a"))))
    }

    @Test
    fun `rejects a duplicate of an existing source and layer pair`() {
        val existing = listOf(valid)
        assertNotNull(SourceValidator.validate(valid, existing))
        // Same source, different layer, is a different route and therefore fine.
        assertNull(SourceValidator.validate(valid.copy(layer = "other"), existing))
    }
}

class SourceCodecTest {

    private val config = SourceConfig(
        listOf(
            TileLayer(
                source = "carto",
                layer = "light",
                title = "CARTO Positron",
                urlTemplate = "https://{s}.basemaps.cartocdn.com/light_all/{z}/{x}/{y}.png",
                subdomains = listOf("a", "b"),
                referer = "https://example.com/",
            ),
        ),
    )

    @Test
    fun `round trips every field`() {
        assertEquals(config, SourceCodec.decode(SourceCodec.encode(config)))
    }

    @Test
    fun `an absent file or unparseable text yields an empty config, not a crash`() {
        assertEquals(SourceConfig(), SourceCodec.decode(""))
        assertEquals(SourceConfig(), SourceCodec.decode("not json"))
        assertEquals(SourceConfig(), SourceCodec.decode("""{"layers":"wrong type"}"""))
    }

    @Test
    fun `a config from a build with extra fields still loads`() {
        // A field that stopped existing (useHttps once did) is the same case as one that
        // does not exist yet: an unknown key, ignored. Pre-1.0 there are no migrations.
        val forward = """{"layers":[{"source":"osm","urlTemplate":"https://e.com/{z}/{x}/{y}","futureField":7}],"somethingNew":true,"useHttps":false}"""
        assertEquals(listOf("osm"), SourceCodec.decode(forward).layers.map { it.source })
    }

    @Test
    fun `start on boot defaults off and survives a round trip`() {
        assertEquals(false, SourceConfig().startOnBoot)
        val wanted = SourceConfig(layers = emptyList(), startOnBoot = true)
        assertEquals(wanted, SourceCodec.decode(SourceCodec.encode(wanted)))
    }

    @Test
    fun `a config written before start on boot existed still loads`() {
        val old = """{"layers":[]}"""
        assertEquals(false, SourceCodec.decode(old).startOnBoot)
    }
}
