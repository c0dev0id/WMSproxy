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
 * Nothing here is authoritative about layers. An entry says the service had usable
 * layers when it was checked; what it offers today comes from its own capabilities
 * document when the user adds it.
 */
object BundledLibrary {

    private const val ASSET = "library.json"

    private var library: SourceLibrary = SourceLibrary()

    /** Called once from [de.codevoid.wmsproxy.WmsProxyApp]. */
    fun init(context: Context) {
        library = LibraryCodec.decode(
            runCatching { context.assets.open(ASSET).use { it.readBytes().decodeToString() } }
                .getOrDefault(""),
        )
    }

    fun get(): SourceLibrary = library
}
