package de.codevoid.wmsproxy.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HttpsTilesTest {

    private fun wmts(name: String, template: String, extent: LonLatBox? = null) = DiscoveredLayer(
        name = name,
        title = name,
        service = ServiceKind.WMTS,
        format = "image/png",
        template = template,
        extent = extent,
    )

    private fun document(vararg layers: DiscoveredLayer) =
        CapabilitiesResult.Success(ServiceKind.WMTS, "T", layers.toList(), emptyList())

    @Test
    fun `the sample is one tile of the first plain-HTTP layer, over HTTPS, over its extent`() {
        val mixed = document(
            wmts("secure", "https://s/secure/{z}/{x}/{y}.png"),
            wmts("plain", "http://s/plain/{z}/{x}/{y}.png", extent = LonLatBox(9.0, 49.0, 11.0, 51.0)),
            wmts("other", "HTTP://s/other/{z:02}/{x}/{y}.png"),
        )
        val tile = TileMath.tileFor(10.0, 50.0, 8)
        assertEquals("https://s/plain/8/${tile.x}/${tile.y}.png", HttpsTiles.sample(mixed))
        // The yes applies to every plain-HTTP layer on the host, and leaves the secure one alone.
        assertEquals(
            listOf("https://s/secure/{z}/{x}/{y}.png", "https://s/plain/{z}/{x}/{y}.png", "https://s/other/{z:02}/{x}/{y}.png"),
            HttpsTiles.secureForm(mixed).layers.map { it.template },
        )
    }

    @Test
    fun `a layer on another host keeps its scheme, since the sample did not ask it`() {
        val twoHosts = document(wmts("a", "http://a/{z}/{x}/{y}.png"), wmts("b", "http://b/{z}/{x}/{y}.png"))
        assertEquals(
            listOf("https://a/{z}/{x}/{y}.png", "http://b/{z}/{x}/{y}.png"),
            HttpsTiles.secureForm(twoHosts).layers.map { it.template },
        )
    }

    @Test
    fun `a document without plain HTTP has nothing to ask and nothing to change`() {
        val secure = document(wmts("a", "https://s/{z}/{x}/{y}.png"), wmts("b", "https://s/{q}.png"))
        assertNull(HttpsTiles.sample(secure))
        assertEquals(secure, HttpsTiles.secureForm(secure))
    }
}
