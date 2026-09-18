package de.codevoid.wmsproxy.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomProbeTest {

    /** Counts probes, so the cost of the search is asserted rather than assumed. */
    private class Counting(private val usable: IntRange?) : (Int) -> Boolean {
        var calls = 0
            private set
        val asked = mutableListOf<Int>()

        override fun invoke(zoom: Int): Boolean {
            calls++
            asked += zoom
            return usable != null && zoom in usable
        }
    }

    private fun range(usable: IntRange?, deepest: Int = 20): Pair<IntRange?, Counting> {
        val probe = Counting(usable)
        return ZoomProbe.findRange(deepest, probe) to probe
    }

    @Test
    fun `finds a span that sits in the middle`() {
        assertEquals(6..14, range(6..14).first)
    }

    @Test
    fun `finds a span reaching the shallow end`() {
        assertEquals(0..12, range(0..12).first)
    }

    @Test
    fun `finds a span reaching the deep end`() {
        assertEquals(8..20, range(8..20).first)
    }

    @Test
    fun `finds the whole range when everything works`() {
        assertEquals(0..20, range(0..20).first)
    }

    @Test
    fun `finds a single usable level`() {
        assertEquals(3..3, range(3..3).first)
        assertEquals(0..0, range(0..0).first)
        assertEquals(20..20, range(20..20).first)
    }

    @Test
    fun `reports nothing usable rather than an empty span`() {
        assertNull(range(null).first)
    }

    @Test
    fun `costs a handful of probes, not one per level`() {
        // The point of the search: a probe may take tens of seconds against a server
        // that renders on demand, so scanning 21 levels is not an option.
        val (result, probe) = range(6..14)
        assertEquals(6..14, result)
        assertTrue("used ${probe.calls} probes", probe.calls <= 12)
    }

    @Test
    fun `asks the middle first, where a source is most likely to answer`() {
        val (_, probe) = range(0..20)
        assertEquals(10, probe.asked.first())
    }

    @Test
    fun `finds a narrow span at the shallow end, which costs the most probes`() {
        val (result, probe) = range(0..1)
        assertEquals(0..1, result)
        assertTrue("used ${probe.calls} probes", probe.calls <= 24)
    }

    @Test
    fun `handles a degenerate range of one level`() {
        assertEquals(0..0, ZoomProbe.findRange(deepest = 0) { true })
        assertNull(ZoomProbe.findRange(deepest = 0) { false })
    }

    @Test
    fun `every level in the reported span was usable`() {
        // Guards the contiguity assumption: whatever the search returns must hold
        // throughout, or the range would promise tiles that are not there.
        for (span in listOf(0..20, 4..4, 7..19, 0..3, 17..20)) {
            val found = ZoomProbe.findRange(20) { it in span }
            assertEquals(span, found)
        }
    }
}
