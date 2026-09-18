package de.codevoid.wmsproxy.core

import kotlin.math.abs

/**
 * Finds the span of zoom levels a source actually serves usefully.
 *
 * Servers rarely say. WMS has `MinScaleDenominator`/`MaxScaleDenominator` for exactly
 * this, and real documents routinely carry neither — one measured for this project
 * declares no scale hints for any of its layers. So the range is established by asking,
 * once, when the source is added.
 *
 * **Usable means answered in time, not merely answered.** A layer that renders on demand
 * can take half a minute for a tile at low zoom, where one tile covers a whole region.
 * That is a successful response nobody can use: the client has given up, the worker
 * serving it is blocked, and the rider sees nothing either way. Treating it as in range
 * would move the failure rather than remove it, so the probe measures against the same
 * budget the live path allows.
 *
 * The search assumes usability is one unbroken span. A source serving z6–z14 but not z9
 * is not a thing that happens: the limits come from how the data was prepared, not from
 * individual levels failing. That assumption is what makes a handful of probes enough
 * where scanning every level would mean twenty requests against a server that might take
 * half a minute for each.
 */
object ZoomProbe {

    /** Past this, tiles are smaller than anything a map renders. */
    const val DEEPEST = 20

    /**
     * The contiguous span for which [usable] holds, or null when nothing does.
     *
     * [usable] is called at most a couple of dozen times and is expected to be expensive,
     * which is the whole reason for the search rather than a scan.
     */
    fun findRange(deepest: Int = DEEPEST, usable: (Int) -> Boolean): IntRange? {
        require(deepest >= 0) { "deepest must not be negative: $deepest" }
        val anchor = findAnchor(deepest, usable) ?: return null
        return lowestUsable(anchor, usable)..highestUsable(anchor, deepest, usable)
    }

    /**
     * Any usable level, searched from the middle outwards.
     *
     * Most sources are usable in the middle of the range, so the first probe usually
     * succeeds. Starting at an end would spend the expensive probes where sources are
     * least likely to answer.
     */
    private fun findAnchor(deepest: Int, usable: (Int) -> Boolean): Int? {
        val middle = deepest / 2
        return (0..deepest).sortedBy { abs(it - middle) }.firstOrNull(usable)
    }

    /** Binary search for the shallowest usable level at or below [anchor]. */
    private fun lowestUsable(anchor: Int, usable: (Int) -> Boolean): Int {
        var known = anchor
        var candidate = 0
        while (candidate < known) {
            val middle = (candidate + known) / 2
            if (usable(middle)) known = middle else candidate = middle + 1
        }
        return known
    }

    /** Binary search for the deepest usable level at or above [anchor]. */
    private fun highestUsable(anchor: Int, deepest: Int, usable: (Int) -> Boolean): Int {
        var known = anchor
        var candidate = deepest
        while (candidate > known) {
            // Rounded up, or a two-level gap would test `known` again and never close.
            val middle = (candidate + known + 1) / 2
            if (usable(middle)) known = middle else candidate = middle - 1
        }
        return known
    }
}
