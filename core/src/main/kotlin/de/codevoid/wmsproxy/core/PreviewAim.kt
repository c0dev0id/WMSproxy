package de.codevoid.wmsproxy.core

/** Where a preview of a layer should open. */
object PreviewAim {

    /**
     * The point to open on, or null for a view of the whole world.
     *
     * A layer that covers a region opens inside it: at the phone when the phone is in
     * it, else at the region's middle, so what the preview is for is on screen at once.
     * A layer that covers the world, or declares nothing, opens at the phone, or on the
     * world when there is no fix either.
     */
    fun startAt(extent: LonLatBox?, fix: LonLat?): LonLat? {
        val region = extent?.takeUnless { it.isWorld }
        return when {
            region != null && (fix == null || !region.contains(fix)) -> region.centre
            else -> fix
        }
    }
}
