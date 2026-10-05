package de.codevoid.wmsproxy.catalog

import de.codevoid.wmsproxy.core.LonLat
import de.codevoid.wmsproxy.core.TileLayer
import de.codevoid.wmsproxy.core.ZoomMeasurement
import de.codevoid.wmsproxy.proxy.Sources
import de.codevoid.wmsproxy.proxy.ZoomProbeRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Measures loaded layers one after another, for the life of the process, so leaving the
 * screen that asked does not cancel the measurement. A layer is stored the moment it is
 * loaded and measured afterwards: the proxy serves it meanwhile, passing every zoom
 * through, and the row says it is being measured.
 */
object ProbeQueue {

    /** The layer under measurement right now and the zoom it is at. */
    data class Progress(val serviceKey: String, val path: String, val zoom: Int)

    private data class Job(val serviceKey: String, val stored: TileLayer, val centre: LonLat?)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = Channel<Job>(Channel.UNLIMITED)

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    /** The stored paths queued or under measurement. */
    private val _pending = MutableStateFlow<Set<String>>(emptySet())
    val pending: StateFlow<Set<String>> = _pending.asStateFlow()

    init {
        scope.launch { for (job in jobs) measure(job) }
    }

    fun enqueue(serviceKey: String, stored: TileLayer, centre: LonLat?) {
        _pending.update { it + stored.path }
        jobs.trySend(Job(serviceKey, stored, centre))
    }

    private fun measure(job: Job) {
        try {
            val report = ZoomProbeRunner.probe(job.stored, job.centre) { zoom ->
                _progress.value = Progress(job.serviceKey, job.stored.path, zoom)
            }
            val measured = job.stored.copy(
                minZoom = report.minZoom,
                maxZoom = report.maxZoom,
                urlTemplate = report.urlTemplate,
            )
            // A no-op when the layer was unloaded meanwhile: replace matches the stored
            // form exactly. The cache keeps the result either way, for the next load.
            Sources.replace(job.stored, measured)
            CatalogStore.measured(
                job.stored,
                ZoomMeasurement(report.minZoom, report.maxZoom, report.urlTemplate, System.currentTimeMillis()),
            )
        } finally {
            _progress.value = null
            _pending.update { it - job.stored.path }
        }
    }
}
