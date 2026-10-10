package de.codevoid.wmsproxy.core

import org.junit.Assert.assertEquals
import org.junit.Test

class LayerTitlesTest {

    private fun layer(name: String, title: String) =
        DiscoveredLayer(name = name, title = title, service = ServiceKind.WMS, format = "image/png", template = "https://s/$name")

    private fun placed(name: String, title: String, vararg groups: String) = LayerTitles.Placed(layer(name, title), groups.toList())

    @Test
    fun `unique titles are left alone`() {
        val titles = LayerTitles.distinct(listOf(placed("1", "Roads", "Transport"), placed("2", "Rail", "Transport"))).map { it.title }
        assertEquals(listOf("Roads", "Rail"), titles)
    }

    @Test
    fun `a repeated title takes the nearest group that tells the copies apart`() {
        // ArcGIS's Structures service: a labels copy and a symbols copy of each layer, under
        // subgroups that share their title too.
        val titles = LayerTitles.distinct(
            listOf(
                placed("32", "Ranger Stations", "Recreation", "Labels", "structures"),
                placed("67", "Ranger Stations", "Recreation", "Features", "structures"),
                placed("40", "Hospitals", "Medical", "Features", "structures"),
            ),
        ).map { it.title }
        assertEquals(listOf("Ranger Stations (Labels)", "Ranger Stations (Features)", "Hospitals"), titles)
    }

    @Test
    fun `copies no group tells apart are told apart by name`() {
        val titles = LayerTitles.distinct(listOf(placed("a", "Roads", "Base"), placed("b", "Roads", "Base"))).map { it.title }
        assertEquals(listOf("Roads (a)", "Roads (b)"), titles)
    }

    @Test
    fun `a group level only counts when every copy has one there`() {
        val titles = LayerTitles.distinct(listOf(placed("a", "Roads", "Base", "Day"), placed("b", "Roads", "Base"))).map { it.title }
        assertEquals(listOf("Roads (a)", "Roads (b)"), titles)
    }
}
