package de.codevoid.wmsproxy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.codevoid.wmsproxy.R
import de.codevoid.wmsproxy.ui.catalog.AddResult

/**
 * One field for an address of any kind the app reads. What it is gets found out on the
 * detail screen, which opens next, so this asks for nothing else. An address the library
 * already has is not added: the dialog names the library's entry and offers it, and adding
 * anyway only where the address differs from the library's.
 */
@Composable
internal fun AddServiceDialog(
    onAdd: (String) -> AddResult,
    onAddAnyway: (String) -> AddResult.Opened,
    onOpened: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var url by rememberSaveable { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    var inLibrary by remember { mutableStateOf<AddResult.InLibrary?>(null) }
    val match = inLibrary

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_service_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(
                    url,
                    { url = it; invalid = false; inLibrary = null },
                    R.string.field_service_url,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                Text(text = stringResource(R.string.add_service_hint), style = MaterialTheme.typography.bodySmall)
                if (invalid) ErrorText(stringResource(R.string.add_service_invalid))
                if (match != null) {
                    Text(
                        text = stringResource(R.string.add_service_in_library, match.name),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        },
        confirmButton = {
            if (match != null) {
                TextButton(onClick = { onOpened(match.key) }) {
                    Text(stringResource(R.string.add_service_open_library))
                }
            } else {
                TextButton(
                    onClick = {
                        when (val result = onAdd(url)) {
                            is AddResult.Opened -> onOpened(result.key)
                            is AddResult.InLibrary -> inLibrary = result
                            AddResult.Invalid -> invalid = true
                        }
                    },
                    enabled = url.isNotBlank(),
                ) {
                    Text(stringResource(R.string.add_service_add))
                }
            }
        },
        dismissButton = {
            if (match != null && !match.sameAddress) {
                TextButton(onClick = { onOpened(onAddAnyway(url).key) }) {
                    Text(stringResource(R.string.add_service_add_anyway))
                }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}
