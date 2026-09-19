package de.codevoid.wmsproxy.dmd

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.codevoid.wmsproxy.core.DmdLayer
import de.codevoid.wmsproxy.core.DmdSync
import de.codevoid.wmsproxy.proxy.ProxyService
import de.codevoid.wmsproxy.proxy.Sources
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The signed-in-ness of the DMD account, hoisted so the form and the status line cannot
 * disagree. The account itself lives in [DmdHub]; this only drives what the tab shows.
 */
sealed interface DmdStatus {
    /** No sign-in attempt in flight — either the form or a confirmed session is shown. */
    data object Idle : DmdStatus
    data object SigningIn : DmdStatus

    /** A restored session is being checked against the server. */
    data object Checking : DmdStatus
    data object Connected : DmdStatus
    data class Error(val message: String) : DmdStatus

    val busy: Boolean get() = this is SigningIn || this is Checking
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
    val choices: StateFlow<Map<String, DmdSyncChoice>> = DmdSyncPrefs.choices

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
        if (_status.value.busy) return
        _status.value = DmdStatus.SigningIn
        viewModelScope.launch {
            try {
                DmdHub.login(email.trim(), password)
                    .onSuccess { _status.value = DmdStatus.Connected }
                    .onFailure { _status.value = DmdStatus.Error(describe(it)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _status.value = DmdStatus.Error(describe(e))
            }
        }
    }

    fun logout() {
        DmdHub.logout()
        _status.value = DmdStatus.Idle
        _sync.value = DmdSyncState.Idle
    }

    fun setSourceEnabled(path: String, enabled: Boolean) = DmdSyncPrefs.setEnabled(path, enabled)

    fun setSourceDirect(path: String, direct: Boolean) = DmdSyncPrefs.setDirect(path, direct)

    /**
     * Pushes the enabled sources to the account: fetch the current layers, replace ours in
     * place, and send the whole set back — the endpoint has no partial update, so the merge
     * is what keeps the user's other layers. A non-2xx at either end leaves the account's
     * layers as they were.
     */
    fun syncNow() {
        if (_sync.value is DmdSyncState.Syncing) return
        _sync.value = DmdSyncState.Syncing
        viewModelScope.launch {
            try {
                val ours = buildLayers()
                val current = DmdHub.request("GET", DmdHub.CUSTOM_LAYERS_PATH)
                if (current.code !in 200..299) {
                    _sync.value = DmdSyncState.Failed("HTTP ${current.code}")
                    return@launch
                }
                val body = DmdSync.mergeForPush(current.body, ours)
                val posted = DmdHub.request("POST", DmdHub.CUSTOM_LAYERS_PATH, body)
                _sync.value = if (posted.code in 200..299) {
                    DmdSyncState.Done(ours.size)
                } else {
                    DmdSyncState.Failed("HTTP ${posted.code}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _sync.value = DmdSyncState.Failed(describe(e))
            }
        }
    }

    /**
     * The DMD layers for the currently enabled sources. Direct is honoured only where the
     * source is compatible; otherwise it falls back to the proxy URL, which always works.
     * The name is the source's title, or its path when the title is blank, so the DMD list
     * never carries an empty name.
     */
    private fun buildLayers(): List<DmdLayer> {
        val config = Sources.config.value
        val server = ProxyService.server
        return config.layers.mapNotNull { layer ->
            val choice = DmdSyncPrefs.choiceFor(layer.path)
            if (!choice.enabled) return@mapNotNull null
            val direct = choice.direct && with(DmdSync) { layer.directCompatible() }
            val template = when {
                direct -> layer.urlTemplate
                config.useHttps -> server.secureTemplateFor(layer)
                else -> server.templateFor(layer)
            }
            DmdSync.toDmdLayer(layer.displayName, layer.path, template)
        }
    }

    private fun confirm() {
        _status.value = DmdStatus.Checking
        viewModelScope.launch {
            try {
                DmdHub.checkConnection()
                    .onSuccess { _status.value = DmdStatus.Connected }
                    // A failure that did not sign out (a network blip) still leaves a
                    // session; report it rather than pretend it is connected.
                    .onFailure {
                        _status.value =
                            if (session.value == null) DmdStatus.Idle else DmdStatus.Error(describe(it))
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _status.value = DmdStatus.Error(describe(e))
            }
        }
    }

    private fun describe(e: Throwable): String = e.message ?: e.javaClass.simpleName
}
