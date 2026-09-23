package de.codevoid.wmsproxy.update

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.codevoid.wmsproxy.describe
import de.codevoid.wmsproxy.core.update.ReleaseInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * One hoisted state rather than the pair of imperative setClickable/setSubtitle
 * callbacks a View-based flow needs — the button's enabled state and its label are both
 * derived from this, so they cannot disagree.
 */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState
    data class Downloading(val percent: Int?, val speed: String) : UpdateState
    data class Failed(val message: String) : UpdateState

    /** While work is in flight the button is inert, so a second tap cannot start a second job. */
    val busy: Boolean get() = this is Checking || this is Downloading
}

class UpdateViewModel(app: Application) : AndroidViewModel(app) {

    private val checker = UpdateChecker(app)

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /**
     * Set when a download finishes. The ViewModel cannot start an Activity, so the UI
     * observes this, hands the file to the installer and calls [installHandled].
     */
    private val _pendingInstall = MutableStateFlow<File?>(null)
    val pendingInstall: StateFlow<File?> = _pendingInstall.asStateFlow()

    private var job: Job? = null

    fun check() {
        if (_state.value.busy) return
        _state.value = UpdateState.Checking
        job = viewModelScope.launch {
            try {
                val release = checker.check()
                _state.value =
                    if (release == null) UpdateState.UpToDate else UpdateState.Available(release)
            } catch (e: CancellationException) {
                // A job cancelled with the ViewModel must complete as cancelled, not be
                // reported to the user as a failed update. CancellationException is an
                // Exception, so this has to precede the catch below.
                throw e
            } catch (e: Exception) {
                _state.value = UpdateState.Failed(e.describe())
            }
        }
    }

    fun download(release: ReleaseInfo) {
        if (_state.value.busy) return
        _state.value = UpdateState.Downloading(percent = null, speed = "")
        job = viewModelScope.launch {
            var lastBytes = 0L
            var lastTick = System.currentTimeMillis()
            var lastEmit = 0L
            try {
                val file = checker.download(release) { written, total ->
                    val now = System.currentTimeMillis()
                    if (now - lastEmit >= PROGRESS_INTERVAL_MS) {
                        val elapsed = (now - lastTick).coerceAtLeast(1)
                        val mbPerSecond =
                            (written - lastBytes) * 1000.0 / elapsed / (1024.0 * 1024.0)
                        lastBytes = written
                        lastTick = now
                        lastEmit = now
                        _state.value = UpdateState.Downloading(
                            percent = if (total > 0) (written * 100 / total).toInt() else null,
                            speed = String.format(Locale.US, "%.1f MB/s", mbPerSecond),
                        )
                    }
                }
                _pendingInstall.value = file
                _state.value = UpdateState.Idle
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = UpdateState.Failed(e.describe())
            }
        }
    }

    fun installHandled() {
        _pendingInstall.value = null
    }

    fun dismiss() {
        if (!_state.value.busy) _state.value = UpdateState.Idle
    }

    fun installIntentFor(file: File) = checker.installIntent(file)

    private companion object {
        const val PROGRESS_INTERVAL_MS = 500L
    }
}
