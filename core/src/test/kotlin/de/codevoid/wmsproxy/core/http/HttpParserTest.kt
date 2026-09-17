package de.codevoid.wmsproxy.core.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HttpParserTest {

    private fun parse(raw: String): HttpRequest? =
        HttpParser.parse(raw.toByteArray(Charsets.ISO_8859_1).inputStream())

    @Test
    fun `reads method, path and headers`() {
        val request = parse(
            "GET /tileproxy/osm/12/2074/1409.png HTTP/1.1\r\n" +
                "Host: 127.0.0.1:8088\r\n" +
                "User-Agent: DMD2\r\n" +
                "\r\n",
        )!!

        assertEquals("GET", request.method)
        assertEquals("/tileproxy/osm/12/2074/1409.png", request.path)
        assertEquals("", request.query)
        assertEquals("DMD2", request.header("User-Agent"))
    }

    /** Header names are case-insensitive in HTTP, so lookup must not depend on spelling. */
    @Test
    fun `header lookup ignores case`() {
        val request = parse("GET / HTTP/1.1\r\nUSER-AGENT: x\r\n\r\n")!!
        assertEquals("x", request.header("user-agent"))
        assertEquals("x", request.header("User-Agent"))
    }

    @Test
    fun `splits the query off the path`() {
        val request = parse("GET /a/b?x=1&y=2 HTTP/1.1\r\n\r\n")!!
        assertEquals("/a/b", request.path)
        assertEquals("x=1&y=2", request.query)
    }

    @Test
    fun `decodes percent escapes in path segments`() {
        assertEquals("/tileproxy/my source/1", parse("GET /tileproxy/my%20source/1 HTTP/1.1\r\n\r\n")!!.path)
    }

    /**
     * An encoded separator must stay inside its segment. Decoding the whole path at once
     * would turn %2F into a real separator and could reach a route the caller never named.
     */
    @Test
    fun `an encoded slash does not become a separator`() {
        val request = parse("GET /tileproxy/a%2Fb/1 HTTP/1.1\r\n\r\n")!!
        assertEquals(listOf("tileproxy", "a/b", "1"), request.segments)
    }

    @Test
    fun `tolerates bare LF line endings`() {
        val request = parse("GET /x HTTP/1.1\nHost: h\n\n")!!
        assertEquals("/x", request.path)
        assertEquals("h", request.header("host"))
    }

    @Test
    fun `segments drops empty parts`() {
        assertEquals(listOf("a", "b"), parse("GET /a/b/ HTTP/1.1\r\n\r\n")!!.segments)
        assertEquals(emptyList<String>(), parse("GET / HTTP/1.1\r\n\r\n")!!.segments)
    }

    @Test
    fun `rejects malformed requests`() {
        assertNull(parse(""))
        assertNull(parse("GET\r\n\r\n"))
        assertNull(parse("GET /x NOTHTTP\r\n\r\n"))
        assertNull(parse("GET relative HTTP/1.1\r\n\r\n"))
        assertNull(parse("GET /x HTTP/1.1\r\nbroken-header\r\n\r\n"))
    }

    /** A hostile local client must not be able to make the parser allocate without bound. */
    @Test
    fun `rejects an over-long request line`() {
        assertNull(parse("GET /" + "a".repeat(9000) + " HTTP/1.1\r\n\r\n"))
    }

    @Test
    fun `rejects too many headers`() {
        val headers = (1..100).joinToString("") { "H$it: v\r\n" }
        assertNull(parse("GET /x HTTP/1.1\r\n$headers\r\n"))
    }
}
