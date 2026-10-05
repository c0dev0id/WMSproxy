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
}
