package de.codevoid.wmsproxy.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceDetailTest {

    private val url = "https://bw.example/wms?SERVICE=WMS&REQUEST=GetCapabilities"

    private fun discovered(name: String) = DiscoveredLayer(
        name = "lgl:$name",
        title = name.replaceFirstChar { it.uppercase() },
        service = ServiceKind.WMS,
        format = "image/png",
        template = "https://bw.example/wms?LAYERS=lgl:$name&BBOX={bbox}",
        centre = LonLat(9.0, 48.5),
    )

    private val cached = CachedService(
        url = url,
        title = "LGL",
        service = ServiceKind.WMS,
        layers = listOf(discovered("roads"), discovered("closures")),
    )

    private val item = ServiceItem(url, "Roadworks", "Germany", "Traffic", "", url, Origin.LIBRARY, false, 1, 2, 0, 1)

    private val loadedRoads = TileLayer("Roadworks", "lgl_roads", "Roads", "https://bw.example/wms?LAYERS=lgl:roads&BBOX={bbox}", origin = url)
    private val staleOne = TileLayer("Roadworks", "lgl_old", "Old", "https://bw.example/wms?LAYERS=lgl:old&BBOX={bbox}", origin = url)
    private val foreign = TileLayer("other", "x", "X", "https://o/{z}/{x}/{y}", origin = "https://other")

    @Test
    fun `a loaded layer carries its stored form, an unloaded one what loading would store`() {
        val rows = ServiceDetail.rows(item, cached, listOf(loadedRoads, foreign))
        assertEquals(listOf("lgl:roads", "lgl:closures"), rows.map { it.id })
        val roads = rows[0]
        assertTrue(roads.loaded)
        assertEquals(loadedRoads, roads.candidate)
        assertEquals(LonLat(9.0, 48.5), roads.centre)
        val closures = rows[1]
        assertFalse(closures.loaded)
        assertEquals("Roadworks", closures.candidate.source)
        assertEquals("lgl_closures", closures.candidate.layer)
        assertEquals(url, closures.candidate.origin)
        assertEquals("https://bw.example/wms?LAYERS=lgl:closures&BBOX={bbox}", closures.candidate.urlTemplate)
        assertNull(closures.blocker)
        assertNull(SourceValidator.validate(closures.candidate, listOf(loadedRoads)))
    }

    @Test
    fun `a stored layer the document no longer lists is kept and marked stale`() {
        val rows = ServiceDetail.rows(item, cached, listOf(loadedRoads, staleOne))
        assertEquals(3, rows.size)
        val stale = rows.last()
        assertTrue(stale.stale && stale.loaded)
        assertEquals("Roadworks/lgl_old", stale.id)
        assertEquals(staleOne, stale.candidate)
        assertNull(stale.service)
    }

    @Test
    fun `a template service has one row with no layer segment`() {
        val template = "https://tile.example/{z}/{x}/{-y}.png"
        val xyz = CachedService.forTemplate(template, 1)
        val own = ServiceItem(template, "tile.example", ServiceCatalog.MINE, "", "", template, Origin.OWN, false, 0, 1, 0, 1)
        val row = ServiceDetail.rows(own, xyz, emptyList()).single()
        assertEquals(XyzTemplate.LAYER_NAME, row.id)
        assertNull(row.candidate.layer)
        assertEquals("tile.example", row.candidate.source)
        assertEquals(Rewrite.FLIPPED_ROWS, row.blocker)
        // Once loaded, the stored form matches the same row.
        val stored = row.candidate.copy(title = "Hiking")
        val loaded = ServiceDetail.rows(own, xyz, listOf(stored)).single()
        assertTrue(loaded.loaded)
        assertEquals(stored, loaded.candidate)
    }

    @Test
    fun `a local service and an unread one show their stored layers only`() {
        val legacy = TileLayer("legacy", null, "Legacy", "https://l/{z}/{x}/{y}.png")
        val local = ServiceItem("legacy", "legacy", ServiceCatalog.MINE, "", "", null, Origin.LOCAL, false, 1, 1, 0, null)
        val row = ServiceDetail.rows(local, null, listOf(legacy, loadedRoads)).single()
        assertEquals("legacy", row.id)
        assertTrue(row.loaded)
        assertFalse(row.stale)
        assertEquals(listOf(loadedRoads), ServiceDetail.rows(item, null, listOf(legacy, loadedRoads)).map { it.candidate })
    }

    @Test
    fun `the row search matches the title or the document's name`() {
        val rows = ServiceDetail.rows(item, cached, emptyList())
        assertEquals(listOf("lgl:closures"), ServiceDetail.filterRows(rows, "CLOS").map { it.id })
        assertEquals(listOf("lgl:roads"), ServiceDetail.filterRows(rows, "lgl:ro").map { it.id })
        assertEquals(rows, ServiceDetail.filterRows(rows, "  "))
    }

    @Test
    fun `select all takes the rows not loaded yet and leaves the loaded ones alone`() {
        val rows = ServiceDetail.rows(item, cached, listOf(loadedRoads))
        val batch = ServiceDetail.toLoad(rows, listOf(loadedRoads))
        assertEquals(listOf("lgl:closures"), batch.rows.map { it.id })
        assertTrue(batch.refused.isEmpty())
        assertNull(batch.notice())
        // Only what the search shows: a narrowed list loads nothing hidden.
        assertTrue(ServiceDetail.toLoad(ServiceDetail.filterRows(rows, "roads"), listOf(loadedRoads)).rows.isEmpty())
    }

    @Test
    fun `select all stores a path once however often the document names it`() {
        val thrice = cached.copy(layers = listOf(discovered("roads"), discovered("roads"), discovered("roads"), discovered("closures")))
        val batch = ServiceDetail.toLoad(ServiceDetail.rows(item, thrice, emptyList()), emptyList())
        assertEquals(listOf("lgl:roads", "lgl:closures"), batch.rows.map { it.id })
        assertEquals(2, batch.refused.size)
        assertEquals("2 layers could not be loaded: A source with that name already exists", batch.notice())
    }

    @Test
    fun `select all checks against the store as it is, not as the screen last saw it`() {
        val rows = ServiceDetail.rows(item, cached, emptyList())
        val batch = ServiceDetail.toLoad(rows, listOf(loadedRoads))
        assertEquals(listOf("lgl:closures"), batch.rows.map { it.id })
        assertEquals("1 layer could not be loaded: A source with that name already exists", batch.notice())
    }

    @Test
    fun `deselect all unloads what is loaded among the shown rows, stale included`() {
        val rows = ServiceDetail.rows(item, cached, listOf(loadedRoads, staleOne))
        assertEquals(listOf(loadedRoads, staleOne), ServiceDetail.toUnload(rows))
        assertTrue(ServiceDetail.toUnload(ServiceDetail.filterRows(rows, "clos")).isEmpty())
    }
}
