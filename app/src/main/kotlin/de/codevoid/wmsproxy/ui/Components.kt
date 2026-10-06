package de.codevoid.wmsproxy.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import de.codevoid.wmsproxy.R
import de.codevoid.wmsproxy.core.Rewrite

/** A single-line text field labelled from resources, the one text input the app uses. */
@Composable
internal fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: Int,
    modifier: Modifier = Modifier.fillMaxWidth(),
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
        modifier = modifier,
    )
}

/** One choice in a [PickerMenu]: its label, whether it can be chosen, and what choosing it does. */
internal class MenuItem(val label: String, val enabled: Boolean = true, val onChoose: () -> Unit)

/**
 * A control that opens a menu of choices: [trigger] draws the control and is handed the
 * call that opens it. The menu closes itself on a choice, so no caller tracks whether
 * it is open.
 */
@Composable
internal fun PickerMenu(
    items: List<MenuItem>,
    trigger: @Composable (open: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        trigger { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.label) },
                    onClick = { open = false; item.onChoose() },
                    enabled = item.enabled,
                )
            }
        }
    }
}

/** A switch with its label after it. The handler comes last, so it can be a trailing lambda. */
@Composable
internal fun LabelledSwitch(
    label: Int,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        Text(text = stringResource(label), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun ErrorText(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

/** A heading over a group of settings or rows. */
@Composable
internal fun SectionTitle(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium)
}

/** Puts [value] on the clipboard; says so on versions where the system does not. */
internal fun copyToClipboard(context: Context, label: String, value: String) {
    context.getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText(label, value))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
    }
}

/**
 * The screen's word for a rewrite: what goes in, what comes out. Used wherever a layer
 * is said to need the proxy, so the list and the detail name the reason alike.
 */
internal val Rewrite.label: Int
    get() = when (this) {
        Rewrite.FLIPPED_ROWS -> R.string.rewrite_flipped_rows
        Rewrite.SUBDOMAINS -> R.string.rewrite_subdomains
        Rewrite.QUADKEY -> R.string.rewrite_quadkey
        Rewrite.PADDED_ZOOM -> R.string.rewrite_padded_zoom
        Rewrite.WMS_BBOX -> R.string.rewrite_wms_bbox
        Rewrite.REFERER -> R.string.rewrite_referer
        Rewrite.CLEARTEXT -> R.string.rewrite_cleartext
    }
