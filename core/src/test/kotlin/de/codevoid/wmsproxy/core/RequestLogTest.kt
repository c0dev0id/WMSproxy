package de.codevoid.wmsproxy.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestLogTest {

    private fun entry(note: String, at: Long = 0L) = LoggedRequest(
        at = at,
        method = "GET",
        path = "/tileproxy/osm/1/2/3.png",
        query = "",
        userAgent = "DMD",
        status = 200,
        note = note,
    )

    @Test
    fun `publishes every recorded request without being asked`() {
        val log = RequestLog()
        assertEquals(emptyList<LoggedRequest>(), log.requests.value)

        log.record(entry("first"))
        assertEquals(1, log.requests.value.size)

        log.record(entry("second"))
        assertEquals(listOf("first", "second"), log.requests.value.map { it.note })
    }

    @Test
    fun `drops the oldest past capacity, and says so through the flow`() {
        val log = RequestLog(capacity = 3)
        repeat(5) { log.record(entry("n$it")) }

        assertEquals(3, log.requests.value.size)
        assertEquals(listOf("n2", "n3", "n4"), log.requests.value.map { it.note })
    }

    @Test
    fun `clearing empties the flow too`() {
        val log = RequestLog()
        log.record(entry("one"))
        log.clear()

        assertEquals(emptyList<LoggedRequest>(), log.requests.value)
        assertEquals(emptyList<LoggedRequest>(), log.snapshot())
    }

    @Test
    fun `published lists are snapshots, not a live view of the deque`() {
        val log = RequestLog()
        log.record(entry("one"))
        val held = log.requests.value

        log.record(entry("two"))

        // The list captured earlier must not have grown underneath its reader.
        assertEquals(1, held.size)
        assertEquals(2, log.requests.value.size)
    }

    @Test
    fun `text export reads oldest first so a session reads chronologically`() {
        val log = RequestLog()
        log.record(entry("older"))
        log.record(entry("newer"))

        val text = log.asText("header")
        assertTrue(text.startsWith("header"))
        assertTrue(text.indexOf("older") < text.indexOf("newer"))
    }

    @Test
    fun `an empty log exports a readable placeholder rather than a bare header`() {
        assertTrue(RequestLog().asText("header").contains("no requests yet"))
    }
}
