package de.codevoid.wmsproxy.preview

import android.content.Context
import de.codevoid.wmsproxy.core.TileLayer
import de.codevoid.wmsproxy.core.TileRef
import de.codevoid.wmsproxy.proxy.ProxyServer
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex
import java.io.File

/**
 * A stored layer as an osmdroid tile source. Every tile address is the layer's own
 * template filled in by [TileLayer.urlFor], the same expansion the proxy performs and
 * DMD is handed, so what the preview draws is what they get: bbox, quadkey, padded
 * zoom, flipped rows and subdomains included.
 *
 * Zoom 0 to 22 whatever the layer measured, on purpose: the preview exists to show
 * where a layer answers and where it stops, so the request is made at every level and
 * a level the server has not got draws as nothing.
 */
class TemplateTileSource(private val layer: TileLayer) : OnlineTileSourceBase(
    cacheName(layer),
    MIN_ZOOM,
    MAX_ZOOM,
    TILE_SIZE,
    FILENAME_ENDING,
    emptyArray<String>(),
) {
    override fun getTileURLString(pMapTileIndex: Long): String = layer.urlFor(
        TileRef(
            MapTileIndex.getZoom(pMapTileIndex),
            MapTileIndex.getX(pMapTileIndex),
            MapTileIndex.getY(pMapTileIndex),
        ),
    )

    private companion object {
        const val MIN_ZOOM = 0
        const val MAX_ZOOM = 22
        const val TILE_SIZE = 256
        const val FILENAME_ENDING = ".png"

        /**
         * The name the tile cache files tiles under. The template is part of it, so a
         * layer whose address changed is not drawn from tiles fetched for the old one.
         */
        fun cacheName(layer: TileLayer): String =
            "preview:${layer.path}:" + layer.urlTemplate.hashCode().toUInt().toString(16)
    }
}

/** osmdroid's process-wide settings, set once before the first map view is made. */
object OsmdroidTiles {

    private const val CACHE_MAX_BYTES = 50L * 1024 * 1024
    private const val CACHE_TRIM_BYTES = 40L * 1024 * 1024
    private const val DOWNLOAD_THREADS: Short = 4
    private const val REFERER = "Referer"

    @Volatile
    private var configured = false

    /**
     * The same User-Agent the proxy sends, for the same reason: tile hosts require one
     * that names the application. The tile cache goes under the app's own cache
     * directory, which needs no permission and is the system's to clear.
     */
    fun configure(context: Context) {
        if (configured) return
        configured = true
        val base = File(context.applicationContext.cacheDir, "osmdroid")
        Configuration.getInstance().apply {
            userAgentValue = ProxyServer.USER_AGENT
            osmdroidBasePath = base
            osmdroidTileCache = File(base, "tiles").also { it.mkdirs() }
            tileFileSystemCacheMaxBytes = CACHE_MAX_BYTES
            tileFileSystemCacheTrimBytes = CACHE_TRIM_BYTES
            tileDownloadThreads = DOWNLOAD_THREADS
        }
    }

    /**
     * The Referer a layer asks for, sent with every tile request while its preview is
     * open. osmdroid's request headers are process-wide, so the screen clears it on
     * the way out.
     */
    fun setReferer(referer: String?) {
        val properties = Configuration.getInstance().additionalHttpRequestProperties
        if (referer == null) properties.remove(REFERER) else properties[REFERER] = referer
    }
}
