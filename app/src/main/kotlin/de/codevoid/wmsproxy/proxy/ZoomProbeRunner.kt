package de.codevoid.wmsproxy.proxy

import de.codevoid.wmsproxy.core.LonLat
import de.codevoid.wmsproxy.core.TileLayer
import de.codevoid.wmsproxy.core.TileMath
import de.codevoid.wmsproxy.core.TileMediaType
import de.codevoid.wmsproxy.core.ZoomProbe
import okhttp3.Request

/** What one source turned out to serve, with the evidence that decided it. */
data class ProbeReport(
    val minZoom: Int?,
    val maxZoom: Int?,
    val attempts: List<Attempt>,
) {
    data class Attempt(val zoom: Int, val usable: Boolean, val millis: Long, val detail: String)

    val usable: Boolean get() = minZoom != null && maxZoom != null
}

/**
 * Measures where a source is worth asking, once, when it is added.
 *
 * Fetches a real tile at a handful of zoom levels and keeps the span that came back in
 * time. Aimed at the middle of the layer's declared extent when the server published one:
 * a tile over empty ocean renders instantly however expensive the layer is, so timing one
 * would measure nothing. Without a declared extent the world centre is used, which is the
 * right choice for the global sources that typically lack one.
 *
 * The wide [Upstream.probeClient] is used rather than the tile client, because the point
 * is to find out how slow the slow case is. Cutting it off at the tile timeout would
 * record "failed" where the truth is "took thirty-one seconds", and that difference is
 * what the range is made of.
 */
object ZoomProbeRunner {

    private val BUDGET_MILLIS = Upstream.TILE_TIMEOUT_SECONDS * 1000

    fun probe(layer: TileLayer, centre: LonLat?, onProgress: (Int) -> Unit = {}): ProbeReport {
        val attempts = mutableListOf<ProbeReport.Attempt>()

        val range = ZoomProbe.findRange { zoom ->
            onProgress(zoom)
            val attempt = attempt(layer, centre, zoom)
            attempts += attempt
            attempt.usable
        }

        return ProbeReport(range?.first, range?.last, attempts.sortedBy { it.zoom })
    }

    private fun attempt(layer: TileLayer, centre: LonLat?, zoom: Int): ProbeReport.Attempt {
        val tile = TileMath.tileFor(centre?.longitude ?: 0.0, centre?.latitude ?: 0.0, zoom)
        val url = layer.urlFor(tile)
        val started = System.nanoTime()

        fun elapsed() = (System.nanoTime() - started) / 1_000_000

        return try {
            Upstream.probeClient.newCall(
                Request.Builder()
                    .url(url)
                    .header("User-Agent", ProxyServer.USER_AGENT)
                    .apply { layer.referer?.let { header("Referer", it) } }
                    .build(),
            ).execute().use { response ->
                val took = elapsed()
                val type = response.body?.contentType()?.toString()
                when {
                    !response.isSuccessful ->
                        ProbeReport.Attempt(zoom, false, took, "HTTP ${response.code}")

                    !TileMediaType.isRasterImage(type) ->
                        ProbeReport.Attempt(zoom, false, took, type ?: "no content type")

                    // Answered, but not in the time the live path allows. Recorded as a
                    // duration rather than a failure, because that is what it was.
                    took > BUDGET_MILLIS ->
                        ProbeReport.Attempt(zoom, false, took, "too slow")

                    else -> ProbeReport.Attempt(zoom, true, took, "ok")
                }
            }
        } catch (e: Exception) {
            ProbeReport.Attempt(zoom, false, elapsed(), e.javaClass.simpleName)
        }
    }
}
