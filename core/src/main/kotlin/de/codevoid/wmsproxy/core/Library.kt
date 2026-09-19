package de.codevoid.wmsproxy.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A service known to have layers this proxy can serve.
 *
 * The claim is only that: the service has *some* usable layers. Which ones, and what is
 * refused, comes from its capabilities document at the time it is added, not from here.
 * Recording layers instead would mean curating thousands of them — one service in this
 * list publishes over a thousand — and going stale every time a provider changed theirs.
 */
@Serializable
data class LibraryEntry(
    val name: String,
    /** The capabilities URL, used exactly as written. */
    val url: String,
    val region: String = "",
    val note: String = "",
    /**
     * How many layers the acceptance rule took and left when the list was last checked.
     *
     * Measured by `tools/check-library.py`, never written by hand. It replaces the
     * hedges that used to live in [note] — "very large layer list" and the like — which
     * only appeared where someone remembered to write them and went stale as soon as a
     * provider changed theirs. The number is also the thing that actually predicts
     * trouble: a service offering one layer and a service offering a thousand both work,
     * and only one of them is a hunt.
     *
     * Both zero means not measured. It carries the same caveat as
     * [SourceLibrary.verified]: a service can change after the check, so this sets an
     * expectation rather than making a promise.
     */
    val usable: Int = 0,
    val refused: Int = 0,
)

@Serializable
data class SourceLibrary(
    /** When the entries were last checked, so an old list can say so. */
    val verified: String = "",
    /**
     * Regions that sort ahead of the rest, in this order.
     *
     * Read from the list rather than named in code: the region a service belongs to is
     * written in the same file, so the order and the values it orders cannot drift apart,
     * and a wide region added later needs no code change here.
     */
    val regions: List<String> = emptyList(),
    val entries: List<LibraryEntry> = emptyList(),
) {
    /**
     * Entries grouped for browsing.
     *
     * Wide coverage first, then countries alphabetically: someone looking for a national
     * map knows which country they want, while someone browsing has no reason to start
     * at Australia. Sorting the entries before grouping is enough to order them within
     * each region, because [groupBy] keeps the order it met them in.
     */
    fun byRegion(): List<Pair<String, List<LibraryEntry>>> =
        entries.sortedBy { it.name }
            .groupBy { it.region }
            .toList()
            .sortedWith(compareBy({ rank(it.first) }, { it.first }))

    /** Where [region] sorts. Everything unnamed shares the rank just after the named ones. */
    private fun rank(region: String): Int =
        regions.indexOf(region).takeIf { it >= 0 } ?: regions.size
}

/**
 * Reads the bundled list.
 *
 * A malformed or truncated file costs the library, not the app: the dialog loses its
 * suggestions and a typed URL still works. Unknown keys are ignored so a file written by
 * a newer build still loads.
 */
object LibraryCodec {

    private val json = Json { ignoreUnknownKeys = true }

    fun decode(text: String): SourceLibrary =
        runCatching { json.decodeFromString(SourceLibrary.serializer(), text) }
            .getOrDefault(SourceLibrary())
}
