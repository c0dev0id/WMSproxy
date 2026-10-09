package de.codevoid.wmsproxy.tool

import de.codevoid.wmsproxy.core.CachedService
import de.codevoid.wmsproxy.core.CatalogCache
import de.codevoid.wmsproxy.core.LibraryEntry
import de.codevoid.wmsproxy.core.ServiceKind
import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogToolTest {

    private val known = LibraryEntry(name = "Known", url = "https://known.example/wms")
    private val added = LibraryEntry(name = "Added", url = "https://added.example/wms")
    private val catalogue = CatalogCache().with(CachedService(url = known.url, service = ServiceKind.WMS))

    @Test
    fun `a run reads only the entries the catalogue has no document for`() {
        assertEquals(listOf(added), CatalogTool.toRead(listOf(known, added), catalogue, all = false))
    }

    @Test
    fun `a run after a removal reads nothing`() {
        assertEquals(emptyList<LibraryEntry>(), CatalogTool.toRead(listOf(known), catalogue, all = false))
    }

    @Test
    fun `a full run reads every entry`() {
        assertEquals(listOf(known, added), CatalogTool.toRead(listOf(known, added), catalogue, all = true))
    }
}
