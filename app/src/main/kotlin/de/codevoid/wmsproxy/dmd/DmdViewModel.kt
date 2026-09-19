package de.codevoid.wmsproxy.dmd

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

class DmdViewModel : ViewModel() {

    val session: StateFlow<DmdSession?> = DmdHub.session

    private val _status = MutableStateFlow<DmdStatus>(DmdStatus.Idle)
    val status: StateFlow<DmdStatus> = _status.asStateFlow()

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
