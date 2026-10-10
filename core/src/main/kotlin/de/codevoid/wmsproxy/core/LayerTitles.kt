package de.codevoid.wmsproxy.core

/**
 * Makes a document's repeated layer titles tell the layers apart.
 *
 * Some services publish the same layer twice under one title: ArcGIS's Structures service
 * keeps a "Labels" copy and a "Features" copy of every layer, each under a group of the
 * same name, so a list showed "Ranger Stations" twice with nothing to choose by. Each
 * layer of a repeated title gets the title of the nearest enclosing group that differs
 * between them, `Ranger Stations (Labels)`; where no group level does, its own name.
 * Titles that are already unique are left alone.
 */
internal object LayerTitles {

    /** A layer with the titles of the groups around it, nearest first. */
    class Placed(val layer: DiscoveredLayer, val groups: List<String>)

    fun distinct(placed: List<Placed>): List<DiscoveredLayer> {
        val renamed = HashMap<Placed, String>()
        for ((title, members) in placed.groupBy { it.layer.title }) {
            if (members.size < 2) continue
            val depth = members.maxOf { it.groups.size }
            val level = (0 until depth).firstOrNull { k ->
                val keys = members.map { it.groups.getOrNull(k) }
                keys.none { it == null } && keys.toSet().size == keys.size
            }
            for (member in members) {
                renamed[member] = "$title (${if (level != null) member.groups[level] else member.layer.name})"
            }
        }
        return placed.map { p -> renamed[p]?.let { p.layer.copy(title = it) } ?: p.layer }
    }
}
