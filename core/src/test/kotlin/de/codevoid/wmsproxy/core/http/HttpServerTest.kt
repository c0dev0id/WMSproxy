package de.codevoid.wmsproxy.core.http

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/**
 * Drives the server over a real socket. This is the payoff for keeping it in :core —
 * the same coverage would need an instrumented test if it lived in :app.
 */
class HttpServerTest {

    private var server: HttpServer? = null

    @After
    fun tearDown() {
        server?.stop()
    }

    private fun start(handler: (HttpRequest) -> HttpResponse): Int {
        // Port 0 lets the OS pick a free one, so parallel test runs cannot collide.
        val started = HttpServer("127.0.0.1", 0, handler = handler)
        started.start()
        server = started
        return started.boundPort
    }

    private fun get(port: Int, path: String): Pair<Int, String> {
        val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            code to (stream?.readBytes()?.toString(Charsets.UTF_8) ?: "")
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun `serves a handler response`() {
        val port = start { HttpResponse.text(200, "OK", "hello ${it.path}") }
        assertEquals(200 to "hello /a/b", get(port, "/a/b"))
    }

    @Test
    fun `passes the query through to the handler`() {
        val port = start { HttpResponse.text(200, "OK", it.query) }
        assertEquals(200 to "x=1&y=2", get(port, "/a?x=1&y=2"))
    }

    @Test
    fun `returns the status the handler chose`() {
        val port = start { HttpResponse.notFound("nope") }
        assertEquals(404 to "nope", get(port, "/missing"))
    }

    @Test
    fun `serves binary bodies unchanged`() {
        val payload = ByteArray(256) { it.toByte() }
        val port = start { HttpResponse.ok("image/png", payload) }

        val connection = URL("http://127.0.0.1:$port/tile").openConnection() as HttpURLConnection
        val body = connection.inputStream.use { it.readBytes() }

        assertEquals("image/png", connection.contentType)
        assertTrue(payload.contentEquals(body))
    }

    /** A handler that throws must not take the connection or the server down. */
    @Test
    fun `a failing handler becomes a 500`() {
        val port = start { error("boom") }
        assertEquals(500, get(port, "/x").first)
    }

    @Test
    fun `rejects methods other than GET`() {
        val port = start { HttpResponse.text(200, "OK", "unused") }
        val connection = URL("http://127.0.0.1:$port/x").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.outputStream.use { it.write(ByteArray(0)) }
        assertEquals(405, connection.responseCode)
    }

    @Test
    fun `keeps serving after a bad request`() {
        val port = start { HttpResponse.text(200, "OK", "fine") }

        java.net.Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().write("garbage\r\n\r\n".toByteArray())
            socket.getOutputStream().flush()
            socket.getInputStream().readBytes()
        }

        assertEquals(200 to "fine", get(port, "/still-here"))
    }

    @Test
    fun `serves concurrent requests`() {
        val port = start { HttpResponse.text(200, "OK", it.path) }
        val results = (1..16).toList().parallelStream()
            .map { get(port, "/p$it") }
            .toList()

        assertTrue(results.all { it.first == 200 })
    }
}
