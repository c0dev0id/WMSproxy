package de.codevoid.wmsproxy.proxy

import android.content.Context
import de.codevoid.wmsproxy.core.LibraryCodec
import de.codevoid.wmsproxy.core.SourceLibrary

/**
 * The list of services shipped with the app.
 *
 * Bundled rather than fetched, so it works before anything else does and adds no
 * dependency on a host staying up. The cost is that a broken entry needs a new build to
 * remove; the list is short and only claims a service exists, so that cost stays small.
 *
 * Read the first time the Library tab is shown and held for the rest of the process.
 * Not at startup, unlike the blank tile: a launch that opens the app to start the proxy
 * never needs it. And not per visit: the tab leaves the composition on every tab switch,
 * so a `remember` inside it re-read and re-parsed the file on the main thread each time
 * the user came back. A file that cannot be read leaves the tab with a notice; the
 * Sources tab and a typed URL are unaffected.
 */
object BundledLibrary {

    private const val ASSET = "library.json"

    // Only the UI reads this, on the main thread, so a plain field is enough.
    private var cached: SourceLibrary? = null

    fun get(context: Context): SourceLibrary =
        cached ?: load(context).also { cached = it }

    private fun load(context: Context): SourceLibrary =
        runCatching { context.assets.open(ASSET).use { it.readBytes().decodeToString() } }
            .getOrNull()
            ?.let(LibraryCodec::decode)
            ?: SourceLibrary()
}
