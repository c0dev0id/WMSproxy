package de.codevoid.wmsproxy.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DmdReachTest {

    private val xyz = TileLayer("s", null, urlTemplate = "https://tile.example/{z}/{x}/{y}.png")
    private val wms = TileLayer("s", "a", urlTemplate = "https://wms.example/wms?LAYERS=a&BBOX={bbox}")
    private val flipped = xyz.copy(flipY = true)
    private val cleartext = TileLayer("s", null, urlTemplate = "http://tile.example/{z}/{x}/{y}.png")
    private val referer = xyz.copy(referer = "https://tile.example/")

    @Test
    fun `no layers is nothing to judge`() {
        assertNull(DmdReach.of(emptyList()))
    }

    @Test
    fun `a WMS bbox is direct, since DMD fills it itself`() {
        assertEquals(DmdReach.DIRECT, DmdReach.of(listOf(xyz, wms)))
    }

    @Test
    fun `every layer behind the proxy is proxy`() {
        assertEquals(DmdReach.PROXY, DmdReach.of(listOf(flipped, cleartext, referer)))
    }

    @Test
    fun `some of each is mixed`() {
        assertEquals(DmdReach.MIXED, DmdReach.of(listOf(wms, flipped)))
    }

    @Test
    fun `a cached document is judged by its templates`() {
        fun layer(name: String, template: String, flipY: Boolean = false) =
            DiscoveredLayer(name, name, ServiceKind.XYZ, "image/png", template, flipY = flipY)
        val url = "https://tile.example/{z}/{x}/{y}.png"
        assertNull(CachedService(url, service = ServiceKind.XYZ).reach)
        assertEquals(DmdReach.DIRECT, CachedService(url, service = ServiceKind.XYZ, layers = listOf(layer("a", url))).reach)
        assertEquals(
            DmdReach.MIXED,
            CachedService(url, service = ServiceKind.XYZ, layers = listOf(layer("a", url), layer("b", url, flipY = true))).reach,
        )
        assertEquals(
            DmdReach.PROXY,
            CachedService(url, service = ServiceKind.XYZ, layers = listOf(layer("a", "https://{s}.tile.example/{z}/{x}/{y}.png"))).reach,
        )
    }
}
