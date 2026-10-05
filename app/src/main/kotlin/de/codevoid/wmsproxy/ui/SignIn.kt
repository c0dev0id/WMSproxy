package de.codevoid.wmsproxy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import de.codevoid.wmsproxy.R
import de.codevoid.wmsproxy.dmd.DmdStatus

/** The DMD Hub sign-in: email, password, button, and the last error under it. */
@Composable
internal fun SignInForm(status: DmdStatus, onSignIn: (String, String) -> Unit, modifier: Modifier = Modifier) {
    var email by rememberSaveable { mutableStateOf("") }
    // Deliberately not rememberSaveable: that Bundle is serialized to disk unencrypted,
    // and keeping the password out of plaintext at rest is the whole point of SecureStore.
    var password by remember { mutableStateOf("") }
    val busy = status is DmdStatus.Busy

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = stringResource(R.string.dmd_intro), style = MaterialTheme.typography.bodyMedium)
        Field(
            email,
            { email = it },
            R.string.dmd_email,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        )
        Field(
            password,
            { password = it },
            R.string.dmd_password,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = PasswordVisualTransformation(),
        )
        Button(
            onClick = { onSignIn(email, password) },
            enabled = !busy && email.isNotBlank() && password.isNotBlank(),
        ) {
            Text(stringResource(if (busy) R.string.dmd_signing_in else R.string.dmd_sign_in))
        }
        (status as? DmdStatus.Error)?.let { ErrorText(stringResource(R.string.dmd_error, it.message)) }
    }
}

/** The same form in a dialog, for a sync asked for before there is a session. */
@Composable
internal fun SignInDialog(status: DmdStatus, onSignIn: (String, String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sign_in_title)) },
        text = { SignInForm(status, onSignIn) },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
