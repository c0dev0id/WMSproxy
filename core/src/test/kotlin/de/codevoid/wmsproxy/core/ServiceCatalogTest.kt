package de.codevoid.wmsproxy.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceCatalogTest {

    private val bw = "https://bw.example/wms?SERVICE=WMS&REQUEST=GetCapabilities"
    private val usgs = "https://usgs.example/MapServer?f=json"
    private val own = "https://mine.example/geoserver/wms"
    private val gone = "https://gone.example/wms"

    private val library = SourceLibrary(
        regions = listOf("Global", "Europe"),
        entries = listOf(
            LibraryEntry("Baden-Württemberg — roadworks", bw, "Germany", "Traffic", "Roadworks and closures.", usable = 7, refused = 1),
            LibraryEntry("USGS — topographic", usgs, "United States", "Basemap", "The national topo map.", usable = 1),
            LibraryEntry("Waymarked Trails — hiking", "https://tile.example/{z}/{x}/{y}.png", "Global", "Traffic", "Signed hiking routes."),
        ),
    )

    private fun layer(name: String, service: ServiceKind = ServiceKind.WMS, template: String = "https://bw.example/wms?LAYERS=$name&BBOX={bbox}") =
        DiscoveredLayer(name, name.replaceFirstChar { it.uppercase() }, service, "image/png", template)

    private val bwDocument = CachedService(
        url = bw,
        title = "LGL",
        service = ServiceKind.WMS,
        fetchedAt = 10,
        layers = listOf(layer("roads"), layer("closures"), layer("detours")),
    )

    private val stored = listOf(
        TileLayer("Baden_Württemberg_roadworks", "roads", "Roads", "https://bw.example/wms?LAYERS=roads&BBOX={bbox}", origin = bw),
        // Needs the proxy: a template DMD cannot fill in.
        TileLayer("Baden_Württemberg_roadworks", "closures", "Closures", "https://bw.example/wms?LAYERS=closures&BBOX={bbox}", flipY = true, origin = bw),
        TileLayer("mine", "a", "A", "https://mine.example/{z}/{x}/{y}.png", origin = own),
        TileLayer("lost", "b", "B", "https://gone.example/{z}/{x}/{y}.png", origin = gone),
        TileLayer("legacy", null, "Legacy", "http://legacy.example/{z}/{x}/{y}.png"),
    )

    private val user = UserCatalog(own = listOf(OwnService(own, 1)), favorites = setOf(usgs, "legacy"))

    private fun assemble(cache: CatalogCache = CatalogCache().with(bwDocument)) =
        ServiceCatalog.assemble(library, user, cache, stored)

    private fun List<ServiceItem>.byKey(key: String) = single { it.key == key }

    @Test
    fun `every library entry, own service, orphan origin and local source is one item`() {
        val items = assemble()
        assertEquals(6, items.size)
        assertEquals(Origin.LIBRARY, items.byKey(bw).origin)
        assertEquals(Origin.OWN, items.byKey(own).origin)
        assertEquals(Origin.OWN, items.byKey(gone).origin)
        assertEquals(Origin.LOCAL, items.byKey("legacy").origin)
        assertEquals(ServiceCatalog.MINE, items.byKey(own).region)
        assertEquals(ServiceCatalog.MINE, items.byKey("legacy").region)
        assertNull(items.byKey("legacy").url)
    }

    @Test
    fun `counts come from the stored layers and the cached document, or the library before a read`() {
        val read = assemble().byKey(bw)
        assertEquals(2, read.loaded)
        assertEquals(3, read.available)
        assertEquals(1, read.proxied)
        assertTrue(read.isLoaded && read.needsProxy)
        assertEquals(10L, read.fetchedAt)
        val unread = assemble().byKey(usgs)
        assertEquals(0, unread.loaded)
        assertEquals(1, unread.available)
        assertFalse(unread.isLoaded)
        assertNull(assemble().byKey("https://tile.example/{z}/{x}/{y}.png").available)
        val local = assemble().byKey("legacy")
        assertEquals(1, local.loaded)
        assertEquals(1, local.available)
        assertEquals(1, local.proxied)
    }

    @Test
    fun `an own service is named by its host until its document is read`() {
        assertEquals("mine.example", assemble().byKey(own).name)
        assertNull(assemble().byKey(own).available)
        val read = CachedService(url = own, title = "My GeoServer", service = ServiceKind.WMS, layers = listOf(layer("x"), layer("y")))
        val item = assemble(CatalogCache().with(bwDocument).with(read)).byKey(own)
        assertEquals("My GeoServer", item.name)
        assertEquals(2, item.available)
    }

    @Test
    fun `favourites come from the user file for every origin`() {
        val items = assemble()
        assertTrue(items.byKey(usgs).favorite)
        assertTrue(items.byKey("legacy").favorite)
        assertFalse(items.byKey(bw).favorite)
    }

    @Test
    fun `an own address that is also a library entry is listed once, as the entry`() {
        val duplicate = user.withOwn(bw, 2)
        val items = ServiceCatalog.assemble(library, duplicate, CatalogCache(), stored)
        assertEquals(1, items.count { it.key == bw })
        assertEquals(Origin.LIBRARY, items.byKey(bw).origin)
    }

    @Test
    fun `each filter narrows the list on its own and together`() {
        val items = assemble()
        val order = library.regions
        fun keys(filter: CatalogFilter) = ServiceCatalog.filtered(items, filter, order).values.flatten().map { it.key }
        assertEquals(setOf(usgs, "legacy"), keys(CatalogFilter(favorites = true)).toSet())
        assertEquals(setOf(bw, own, gone, "legacy"), keys(CatalogFilter(loaded = true)).toSet())
        assertEquals(listOf(bw), keys(CatalogFilter(region = "Germany")))
        assertEquals(setOf(bw, "https://tile.example/{z}/{x}/{y}.png"), keys(CatalogFilter(category = "Traffic")).toSet())
        assertEquals(listOf(bw), keys(CatalogFilter(query = "closures")))
        assertEquals(listOf(usgs), keys(CatalogFilter(query = "TOPO")))
        assertEquals(listOf("legacy"), keys(CatalogFilter(favorites = true, loaded = true)))
        assertTrue(keys(CatalogFilter(region = "Germany", category = "Basemap")).isEmpty())
        assertFalse(CatalogFilter().active)
        assertTrue(CatalogFilter(query = "x").active)
    }

    @Test
    fun `groups come Mine first, then the library's wide regions, then the rest by name`() {
        val groups = ServiceCatalog.filtered(assemble(), CatalogFilter(), library.regions)
        assertEquals(listOf(ServiceCatalog.MINE, "Global", "Germany", "United States"), groups.keys.toList())
        // Within Mine: by name, so the host-named own service sorts among the rest.
        assertEquals(listOf("gone.example", "legacy", "mine.example"), groups[ServiceCatalog.MINE]!!.map { it.name })
        assertEquals(groups.keys.toList(), ServiceCatalog.regions(assemble(), library.regions))
        assertEquals(listOf("Basemap", "Traffic"), ServiceCatalog.categories(assemble()))
    }

    @Test
    fun `the proxied layers are the ones DMD cannot address itself`() {
        assertEquals(listOf("Closures", "Legacy"), ServiceCatalog.proxied(stored).map { it.title })
    }

    @Test
    fun `the source id is derived the same way every time`() {
        val items = assemble()
        assertEquals("Baden-W_rttemberg_roadworks", ServiceCatalog.sourceIdFor(items.byKey(bw), null))
        assertEquals("mine.example", ServiceCatalog.sourceIdFor(items.byKey(own), null))
        val read = CachedService(url = own, title = "My GeoServer", service = ServiceKind.WMS)
        assertEquals("My_GeoServer", ServiceCatalog.sourceIdFor(items.byKey(own), read))
        assertEquals("legacy", ServiceCatalog.sourceIdFor(items.byKey("legacy"), null))
        assertNull(SourceValidator.validate(TileLayer(ServiceCatalog.sourceIdFor(items.byKey(bw), null), "x", urlTemplate = "https://e/{z}/{x}/{y}")))
    }
}
