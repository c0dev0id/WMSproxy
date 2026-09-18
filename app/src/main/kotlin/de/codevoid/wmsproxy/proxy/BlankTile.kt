package de.codevoid.wmsproxy.proxy

import android.content.Context

/**
 * The transparent tile served where a source has nothing to offer.
 *
 * Shipped as an asset rather than drawn: a fully transparent 256×256 PNG is 334 bytes,
 * identical every time, and building one on demand would put an encoder on the data path
 * for a constant. There is no `Bitmap` here, only bytes read once and handed out.
 *
 * Why blank rather than 404, which is the XYZ convention for a missing tile: WMS
 * prescribes a blank map outside a layer's declared scale range, and this is that case.
 * The client also has a vote — DMD refuses a source whose test tile is not a 200, and
 * conforming to what the client accepts is the job of the side facing it.
 *
 * The honest caveat is that the measured range excludes a zoom for being slow as well as
 * for being empty, and blank asserts emptiness. Separating the two means recording why a
 * bound is where it is, not just where it is.
 */
object BlankTile {

    const val CONTENT_TYPE = "image/png"

    private const val ASSET = "blank-tile.png"

    private var bytes: ByteArray? = null

    /** Called once from [de.codevoid.wmsproxy.WmsProxyApp]. */
    fun init(context: Context) {
        bytes = runCatching { context.assets.open(ASSET).use { it.readBytes() } }.getOrNull()
    }

    /**
     * The tile, or null when the asset could not be read.
     *
     * Null rather than an empty array on purpose: a zero-byte body sent as `image/png` is
     * a corrupt tile the client would cache, which is worse than the error it replaced.
     * The caller falls back to saying so.
     */
    fun bytesOrNull(): ByteArray? = bytes
}
