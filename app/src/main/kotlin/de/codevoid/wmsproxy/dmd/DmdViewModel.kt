package de.codevoid.wmsproxy.dmd

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.codevoid.wmsproxy.core.DmdAuthException
import de.codevoid.wmsproxy.core.MapLayerCodec
import de.codevoid.wmsproxy.core.MapLayerSync
import de.codevoid.wmsproxy.describe
import de.codevoid.wmsproxy.proxy.ProxyService
import de.codevoid.wmsproxy.proxy.Sources
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the account is doing, hoisted so the form and the status line cannot disagree.
 * The account itself lives in [DmdHub]; this only drives what the tab shows.
 */
sealed interface DmdStatus {
    /** Nothing in flight: the form, or a session the last check left standing. */
    data object Idle : DmdStatus

    /** Signing in, or confirming a remembered session against the server. */
    data object Busy : DmdStatus
    data class Error(val message: String) : DmdStatus
}

/** The outcome of the last sync, so the tab can show progress and a result line. */
sealed interface DmdSyncState {
    data object Idle : DmdSyncState
    data object Syncing : DmdSyncState
    data class Done(val count: Int) : DmdSyncState
    data class Failed(val message: String) : DmdSyncState
}

class DmdViewModel : ViewModel() {

    val session: StateFlow<DmdSession?> = DmdHub.session
    val fullSync: StateFlow<Boolean> = DmdSyncPrefs.fullSync

    private val _status = MutableStateFlow<DmdStatus>(DmdStatus.Idle)
    val status: StateFlow<DmdStatus> = _status.asStateFlow()

    private val _sync = MutableStateFlow<DmdSyncState>(DmdSyncState.Idle)
    val sync: StateFlow<DmdSyncState> = _sync.asStateFlow()

    init {
        // A remembered session is only a claim until the token is tried. Confirm it once
        // on open so the tab does not show "signed in" over a token the server has already
        // rejected — the check signs out on its own if it has.
        if (session.value != null) confirm()
    }

    fun login(email: String, password: String) {
        if (_status.value is DmdStatus.Busy) return
        _status.value = DmdStatus.Busy
        attempt(onFailure = { _status.value = DmdStatus.Error(it.describe()) }) {
            DmdHub.login(email.trim(), password)
            _status.value = DmdStatus.Idle
        }
    }

    fun logout() {
        DmdHub.logout()
        _status.value = DmdStatus.Idle
        _sync.value = DmdSyncState.Idle
    }

    /**
     * Signs in and, once that succeeds, pushes straight away: what the list's Sync button
     * does when there is no session yet, so one tap finishes the job it started.
     */
    fun loginAndSync(email: String, password: String) {
        if (_status.value is DmdStatus.Busy) return
        _status.value = DmdStatus.Busy
        attempt(onFailure = { _status.value = DmdStatus.Error(it.describe()) }) {
            DmdHub.login(email.trim(), password)
            _status.value = DmdStatus.Idle
            syncNow()
        }
    }

    /** Forgets the last sync's outcome once it has been shown, so it is shown once. */
    fun clearSyncResult() {
        if (_sync.value !is DmdSyncState.Syncing) _sync.value = DmdSyncState.Idle
    }

    fun setFullSync(enabled: Boolean) = DmdSyncPrefs.setFullSync(enabled)

    /**
     * Pushes every loaded layer to the account. The new API has per-layer operations, so
     * the sync is three steps rather than a full replace: fetch what the account has,
     * import ours (upserts by stable id), and delete every one of our ids the push no
     * longer produces. A full sync also deletes every foreign layer; otherwise they stand.
     */
    fun syncNow() {
        if (_sync.value is DmdSyncState.Syncing) return
        _sync.value = DmdSyncState.Syncing
        attempt(onFailure = { _sync.value = DmdSyncState.Failed(it.describe()) }) {
            val count = withContext(Dispatchers.IO) {
                val ours = MapLayerSync.layersFor(
                    Sources.config.value.layers,
                    ProxyService.server::templateFor,
                )
                val ourIds = ours.mapNotNullTo(mutableSetOf()) { it.id }

                val serverBody = DmdHub.fetchMapLayers()
                val orphans = MapLayerSync.ourIdsIn(serverBody).filter { it !in ourIds }
                val foreign = if (DmdSyncPrefs.fullSync.value) MapLayerSync.foreignIdsIn(serverBody) else emptyList()

                DmdHub.importMapLayers(MapLayerCodec.encodeImportBody(ours))
                (orphans + foreign).forEach { DmdHub.deleteMapLayer(it) }

                ours.size
            }
            _sync.value = DmdSyncState.Done(count)
        }
    }

    /**
     * Writes every map layer in the account into the request log, all fields verbatim: a
     * layer DMD wrote itself beside the one this app pushed for the same source, so a
     * difference in form can be read off the log rather than guessed at. The outcome goes
     * to the log either way, since the log is where the button that asked sits.
     */
    fun dumpAccountLayers() {
        attempt(
            onFailure = {
                ProxyService.log.note("dmd-hub", "Reading the account's map layers failed: ${it.describe()}")
            },
        ) {
            val entries = withContext(Dispatchers.IO) { MapLayerSync.accountEntries(DmdHub.fetchMapLayers()) }
            ProxyService.log.note(
                "dmd-hub",
                "${entries.size} account map layers, every field as the server sent it:\n    " +
                    entries.joinToString("\n    "),
            )
        }
    }

    /**
     * Tries the remembered session against the server. A refusal signs the account out in
     * [DmdHub] and the form comes back, which needs no message; any other failure — the
     * network, the server — leaves the session standing and is reported over it.
     */
    private fun confirm() {
        _status.value = DmdStatus.Busy
        attempt(
            onFailure = {
                _status.value = if (it is DmdAuthException) DmdStatus.Idle else DmdStatus.Error(it.describe())
            },
        ) {
            val foreign = withContext(Dispatchers.IO) { MapLayerSync.foreignEntries(DmdHub.fetchMapLayers()) }
            _status.value = DmdStatus.Idle
            noteAccountLayers(foreign)
        }
    }

    /**
     * Writes the account's own layers into the request log, verbatim, as one entry: the log
     * is the app's one diagnostic surface, and what the next sync will see belongs there.
     * One entry however many layers, so a check costs one of its three hundred lines.
     */
    private fun noteAccountLayers(foreign: List<String>) {
        if (foreign.isEmpty()) return
        ProxyService.log.note(
            "dmd-hub",
            "${foreign.size} account map layers not from this app:\n    " + foreign.joinToString("\n    "),
        )
    }

    /**
     * Runs [block] on the view model's scope and hands any failure to [onFailure].
     * Cancellation is not a failure: a job cancelled with the view model must complete as
     * cancelled, and CancellationException is an Exception, so it is let through first.
     */
    private fun attempt(onFailure: (Throwable) -> Unit, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onFailure(e)
            }
        }
    }
}
