package de.codevoid.wmsproxy.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlsTest {

    @Test
    fun `the host is what sits between the scheme and the path`() {
        assertEquals("a.b.c:8443", Urls.hostOf("https://a.b.c:8443/x/y?z=1#f"))
        assertEquals("tile.example", Urls.hostOf("https://tile.example"))
        assertEquals("h", Urls.hostOf("http://user:pw@h/p"))
        assertEquals("", Urls.hostOf("no scheme here"))
    }

    @Test
    fun `a short address is left alone and a long one keeps its server and its tail`() {
        assertEquals("https://a/b", Urls.abbreviate("https://a/b", 48))
        val long = "https://services.arcgis.com/v01gqwM5QqNysAAi/ArcGIS/rest/services/PADUS3_0PublicAccess/MapServer?f=json"
        val short = Urls.abbreviate(long, 48)
        assertTrue(short, short.startsWith("https://services.arcgis.com/"))
        assertTrue(short, short.endsWith("MapServer?f=json"))
        assertTrue(short, short.contains("…"))
        assertTrue(short, short.length <= 48)
    }

    @Test
    fun `a host too long for the room is cut at the end instead`() {
        val host = "https://" + "x".repeat(60) + "/a"
        val short = Urls.abbreviate(host, 32)
        assertTrue(short, short.endsWith("…"))
        assertEquals(32, short.length)
    }

    @Test
    fun `placeholders pasted percent-encoded are decoded`() {
        assertEquals(
            "https://maptile.example/tile/offroad/{z}/{x}/{y}.png",
            Urls.fromInput("https://maptile.example/tile/offroad/%7Bz%7D/%7Bx%7D/%7By%7D.png"),
        )
        assertEquals("https://t.example/{z}/{x}/{-y}.png", Urls.fromInput("https://t.example/%7bz%7d/%7Bx%7d/%7B-y%7D.png"))
        assertEquals("https://t.example/{z:02}/{X}/{Y}", Urls.fromInput("https://t.example/%7Bz:02%7D/%7BX%7D/%7BY%7D"))
        assertEquals(
            "https://s.example/export?bbox={bbox-epsg-3857}&f=image",
            Urls.fromInput("https://s.example/export?bbox=%7Bbbox-epsg-3857%7D&f=image"),
        )
    }

    @Test
    fun `everything else encoded is left as it was`() {
        val json = "https://s.example/exportImage?renderingRule=%7B%22rasterFunction%22%3A%22x%22%7D&f=image"
        assertEquals(json, Urls.fromInput(json))
        val layer = "https://s.example/wms?SERVICE=WMS&LAYERS=lgl%3Aroads&REQUEST=GetCapabilities"
        assertEquals(layer, Urls.fromInput(layer))
        assertEquals("https://t.example/%7B%7D.png", Urls.fromInput("https://t.example/%7B%7D.png"))
    }

    @Test
    fun `an address is trimmed and a clean one passes unchanged`() {
        assertEquals("https://t.example/{z}/{x}/{y}.png", Urls.fromInput("  https://t.example/{z}/{x}/{y}.png\n"))
        assertTrue(XyzTemplate.isTemplate(Urls.fromInput("https://t.example/%7Bz%7D/%7Bx%7D/%7By%7D.png")))
    }
}
