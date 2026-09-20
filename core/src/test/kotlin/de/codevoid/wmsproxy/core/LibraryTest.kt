package de.codevoid.wmsproxy.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryTest {

    private val library = SourceLibrary(
        verified = "2026-09-19",
        regions = listOf("Global", "Europe"),
        entries = listOf(
            LibraryEntry("Zeta national map", "https://z.example/caps", "Norway"),
            LibraryEntry("Alpha imagery", "https://a.example/caps", "Global"),
            LibraryEntry("Beta terrain", "https://b.example/caps", "Europe"),
            LibraryEntry("Alpha basemap", "https://c.example/caps", "Norway"),
            LibraryEntry("Gamma charts", "https://d.example/caps", "Australia"),
        ),
    )

    @Test
    fun `groups by region, wide coverage first then countries alphabetically`() {
        assertEquals(
            listOf("Global", "Europe", "Australia", "Norway"),
            library.byRegion().keys.toList(),
        )
    }

    @Test
    fun `region order comes from the list, not from the code`() {
        assertEquals(
            listOf("Norway", "Australia", "Europe", "Global"),
            library.copy(regions = listOf("Norway")).byRegion().keys.toList(),
        )
    }

    @Test
    fun `sorts entries within a region by name`() {
        val norway = library.byRegion().getValue("Norway")
        assertEquals(listOf("Alpha basemap", "Zeta national map"), norway.map { it.name })
    }

    @Test
    fun `reads the shape the bundled file is written in`() {
        val text = """
            {"verified":"2026-09-19",
             "regions":["Global","Europe"],
             "entries":[{"name":"Alpha imagery","url":"https://a.example/caps",
                         "region":"Global","note":"Daily satellite imagery."}]}
        """.trimIndent()
        assertEquals(
            SourceLibrary(
                verified = "2026-09-19",
                regions = listOf("Global", "Europe"),
                entries = listOf(
                    LibraryEntry(
                        name = "Alpha imagery",
                        url = "https://a.example/caps",
                        region = "Global",
                        note = "Daily satellite imagery.",
                    ),
                ),
            ),
            LibraryCodec.decode(text),
        )
    }

    @Test
    fun `layer counts are optional, so an unmeasured entry still loads`() {
        val parsed = LibraryCodec.decode(
            """{"entries":[{"name":"X","url":"https://x.example/caps"}]}""",
        )
        assertEquals(0, parsed.entries.single().usable)
        assertEquals(0, parsed.entries.single().refused)
    }

    @Test
    fun `layer counts are read when present`() {
        val parsed = LibraryCodec.decode(
            """{"entries":[{"name":"X","url":"https://x.example/caps",
                             "usable":22,"refused":25}]}""",
        )
        assertEquals(22, parsed.entries.single().usable)
        assertEquals(25, parsed.entries.single().refused)
    }

    @Test
    fun `a malformed file costs the library, not the app`() {
        assertEquals(SourceLibrary(), LibraryCodec.decode(""))
        assertEquals(SourceLibrary(), LibraryCodec.decode("not json"))
        assertEquals(SourceLibrary(), LibraryCodec.decode("""{"entries": 7}"""))
    }

    @Test
    fun `a file from a newer build still loads`() {
        val forward = """
            {"verified":"2027-01-01","somethingNew":true,
             "entries":[{"name":"X","url":"https://x.example/caps","futureField":1}]}
        """.trimIndent()
        val parsed = LibraryCodec.decode(forward)
        assertEquals(listOf("X"), parsed.entries.map { it.name })
        assertEquals("2027-01-01", parsed.verified)
    }

    @Test
    fun `an entry with no region forms a group of its own, after the named ones`() {
        val loose = LibraryEntry("Loose", "https://l.example/caps")
        val odd = library.copy(entries = library.entries + loose)
        assertEquals(
            listOf("Global", "Europe", "", "Australia", "Norway"),
            odd.byRegion().keys.toList(),
        )
        assertEquals(listOf(loose), odd.byRegion().getValue(""))
    }

    @Test
    fun `an entry is measured once either count is set`() {
        val entry = LibraryEntry("X", "https://x.example/caps")
        assertFalse(entry.measured)
        assertTrue(entry.copy(usable = 3).measured)
        assertTrue(entry.copy(refused = 2).measured)
        assertEquals(47, entry.copy(usable = 22, refused = 25).total)
    }
}
