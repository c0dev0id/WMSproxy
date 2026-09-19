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
)

@Serializable
data class SourceLibrary(
    /** When the entries were last checked, so an old list can say so. */
    val verified: String = "",
    val entries: List<LibraryEntry> = emptyList(),
) {
    /**
     * Entries grouped for browsing.
     *
     * Wide coverage first, then countries alphabetically: someone looking for a national
     * map knows which country they want, while someone browsing has no reason to start
     * at Australia.
     */
    fun byRegion(): List<Pair<String, List<LibraryEntry>>> =
        entries.groupBy { it.region }
            .toList()
            .sortedWith(compareBy({ REGION_ORDER.indexOf(it.first).let { i -> if (i < 0) REGION_ORDER.size else i } }, { it.first }))
            .map { (region, group) -> region to group.sortedBy { it.name } }
}

/**
 * Regions that sort ahead of the countries, in this order.
 *
 * A file-level value rather than a companion: `@Serializable` generates `serializer()`
 * on the companion, and declaring one `private` to hold a constant takes that generated
 * accessor private with it — leaving the class serializable in name only.
 */
private val REGION_ORDER = listOf("Global", "Europe")

/**
 * Reads the bundled list.
 *
 * Lenient in the same way stored configuration is: a malformed or truncated file costs
 * the library, not the app, and unknown keys are ignored so a file written by a newer
 * build still loads.
 */
object LibraryCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun decode(text: String): SourceLibrary =
        runCatching { json.decodeFromString(SourceLibrary.serializer(), text) }
            .getOrDefault(SourceLibrary())
}
