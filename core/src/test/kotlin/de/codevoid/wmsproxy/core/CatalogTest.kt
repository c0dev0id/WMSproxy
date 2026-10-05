package de.codevoid.wmsproxy.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogTest {

    private val charging = DiscoveredLayer(
        name = "MobiData-BW:charge_points",
        title = "Charging points",
        service = ServiceKind.WMS,
        format = "image/png",
        template = "https://s?LAYERS=x&BBOX={bbox}",
        centre = LonLat(9.1, 48.7),
    )

    private val document = CapabilitiesResult.Success(
        service = ServiceKind.WMS,
        title = "MobiData",
        layers = listOf(charging),
        skipped = listOf(SkippedLayer("raw", "no WebMercator")),
    )

    @Test
    fun `a read document round-trips with its extent and its skips`() {
        val cached = CachedService.of("https://s?SERVICE=WMS", "https://s?SERVICE=WMS&REQUEST=GetCapabilities", document, now = 42)
        val cache = CatalogCache().with(cached)
        assertEquals(cache, CatalogCodec.decodeCache(CatalogCodec.encodeCache(cache)))
        assertEquals("MobiData", cached.title)
        assertEquals(42L, cached.fetchedAt)
        assertEquals(charging, cached.layer(charging.name))
        assertEquals("no WebMercator", cached.skipped.single().reason)
        assertEquals(CatalogCache(), cache.without(cached.url))
    }

    @Test
    fun `unknown keys are ignored and unreadable text is an empty value`() {
        val forward = """{"services":{"u":{"url":"u","service":"WMS","future":1}},"next":true}"""
        assertEquals("u", CatalogCodec.decodeCache(forward)["u"]?.url)
        assertEquals(CatalogCache(), CatalogCodec.decodeCache("not json"))
        assertEquals(CatalogCache(), CatalogCodec.decodeCache(""))
        assertEquals(UserCatalog(), CatalogCodec.decodeUser("{\"own\":\"wrong type\"}"))
    }

    @Test
    fun `a template is a service with one layer and no document`() {
        val url = "https://tile.example/hiking/{z}/{x}/{y}.png"
        val cached = CachedService.forTemplate(url, now = 1)
        assertEquals(ServiceKind.XYZ, cached.service)
        assertEquals("tile.example", cached.title)
        assertEquals(url, cached.fetchedFrom)
        val layer = cached.layers.single()
        assertEquals(XyzTemplate.LAYER_NAME, layer.name)
        assertEquals("image/png", layer.format)
        // No layer segment, as a hand-typed template never had one; tied back by origin.
        val stored = layer.toTileLayer("hiking", origin = url)
        assertNull(stored.layer)
        assertEquals("hiking", stored.path)
        assertEquals(url, stored.origin)
        assertNull(SourceValidator.validate(stored))
    }

    @Test
    fun `the user file keeps the shortlist and own services apart from the cache`() {
        val user = UserCatalog().withOwn("https://mine/wms", now = 5).withOwn("https://mine/wms", now = 6)
        assertEquals(1, user.own.size)
        assertEquals(5L, user.own.single().addedAt)
        val starred = user.toggleFavorite("https://mine/wms").toggleFavorite("lib")
        assertTrue(starred.isFavorite("https://mine/wms"))
        assertFalse(starred.toggleFavorite("lib").isFavorite("lib"))
        // Removing an own service also drops its star; the library entry's star stays.
        val gone = starred.withoutOwn("https://mine/wms")
        assertTrue(gone.own.isEmpty())
        assertFalse(gone.isFavorite("https://mine/wms"))
        assertTrue(gone.isFavorite("lib"))
        assertEquals(starred, CatalogCodec.decodeUser(CatalogCodec.encodeUser(starred)))
    }
}

class XyzTemplateTest {

    @Test
    fun `a template is recognised in any spelling of its placeholders`() {
        assertTrue(XyzTemplate.isTemplate("https://e/{z}/{x}/{y}.png"))
        assertTrue(XyzTemplate.isTemplate("https://e/{Z}/{X}/{Y}.png"))
        assertTrue(XyzTemplate.isTemplate("https://e/{z:02}/{x}/{y}.png"))
        assertTrue(XyzTemplate.isTemplate("https://e/{z}/{x}/{-y}.png"))
        assertTrue(XyzTemplate.isTemplate("https://e/vt?x={x}&y={y}&z={z}"))
        assertFalse(XyzTemplate.isTemplate("https://e/wms?SERVICE=WMS&REQUEST=GetCapabilities"))
        assertFalse(XyzTemplate.isTemplate("https://e/wms?BBOX={bbox}"))
        assertFalse(XyzTemplate.isTemplate("https://e/{z}/{x}.png"))
    }

    @Test
    fun `a southern row order becomes a flip the proxy performs`() {
        assertEquals("https://e/{z}/{x}/{y}.png" to true, XyzTemplate.normalise("https://e/{z}/{x}/{-y}.png"))
        val layer = XyzTemplate.implicitLayer("https://e/{z}/{x}/{-y}.png").toTileLayer("e", origin = "https://e/{z}/{x}/{-y}.png")
        assertTrue(layer.flipY)
        assertTrue(Rewrite.FLIPPED_ROWS in layer.rewrites())
    }

    @Test
    fun `upper-case placeholders are lowered so the template expands`() {
        val (template, flipY) = XyzTemplate.normalise("https://e/{Z}/{X}/{Y}.png")
        assertFalse(flipY)
        assertEquals("https://e/3/1/2.png", TileLayer(source = "e", urlTemplate = template).urlFor(TileRef(3, 1, 2)))
    }

    @Test
    fun `the format is read off the extension, blank when there is none`() {
        assertEquals("image/jpeg", XyzTemplate.implicitLayer("https://e/{z}/{x}/{y}.jpg?key=1").format)
        assertEquals("image/webp", XyzTemplate.implicitLayer("https://e/{z}/{x}/{y}.WEBP").format)
        assertEquals("", XyzTemplate.implicitLayer("https://e/vt?x={x}&y={y}&z={z}").format)
        assertEquals("e", XyzTemplate.implicitLayer("  https://e/{z}/{x}/{y}.png ").title)
    }
}
