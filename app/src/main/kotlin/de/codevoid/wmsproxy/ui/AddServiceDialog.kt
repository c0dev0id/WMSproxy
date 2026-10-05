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
 * detail screen, which opens next, so this asks for nothing else.
 */
@Composable
internal fun AddServiceDialog(
    onAdd: (String) -> AddResult,
    onOpened: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var url by rememberSaveable { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_service_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(
                    url,
                    { url = it; invalid = false },
                    R.string.field_service_url,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                Text(text = stringResource(R.string.add_service_hint), style = MaterialTheme.typography.bodySmall)
                if (invalid) ErrorText(stringResource(R.string.add_service_invalid))
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    when (val result = onAdd(url)) {
                        is AddResult.Opened -> onOpened(result.key)
                        AddResult.Invalid -> invalid = true
                    }
                },
                enabled = url.isNotBlank(),
            ) {
                Text(stringResource(R.string.add_service_add))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
