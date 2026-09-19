package de.codevoid.wmsproxy.core

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryTest {

    private val library = SourceLibrary(
        verified = "2026-09-19",
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
            library.byRegion().map { it.first },
        )
    }

    @Test
    fun `sorts entries within a region by name`() {
        val norway = library.byRegion().single { it.first == "Norway" }.second
        assertEquals(listOf("Alpha basemap", "Zeta national map"), norway.map { it.name })
    }

    @Test
    fun `round trips`() {
        val text = Json.encodeToString(SourceLibrary.serializer(), library)
        assertEquals(library, LibraryCodec.decode(text))
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
    fun `an entry with no region still groups`() {
        val odd = SourceLibrary(entries = listOf(LibraryEntry("Loose", "https://l.example/caps")))
        assertTrue(odd.byRegion().isNotEmpty())
    }
}
