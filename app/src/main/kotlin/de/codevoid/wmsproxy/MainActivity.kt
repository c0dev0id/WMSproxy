package de.codevoid.wmsproxy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.codevoid.wmsproxy.update.UpdateState
import de.codevoid.wmsproxy.update.UpdateViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    StatusScreen()
                }
            }
        }
    }
}

@Composable
private fun StatusScreen(viewModel: UpdateViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pendingInstall by viewModel.pendingInstall.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // A finished download hands straight to the system installer. Doing this from the
    // composition rather than the ViewModel keeps Activity launching out of the model.
    LaunchedEffect(pendingInstall) {
        pendingInstall?.let { file ->
            context.startActivity(viewModel.installIntentFor(file))
            viewModel.installHandled()
        }
    }

    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.status_not_implemented),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(R.string.installed_version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodySmall,
        )

        Button(onClick = viewModel::check, enabled = !state.busy) {
            Text(text = stringResource(R.string.check_for_updates))
        }

        when (val current = state) {
            is UpdateState.Checking ->
                Text(
                    text = stringResource(R.string.checking_updates),
                    style = MaterialTheme.typography.bodySmall,
                )

            is UpdateState.Downloading ->
                Text(
                    // The percentage is concatenated rather than passed through a format
                    // string: a literal % in a template is a crash waiting to happen.
                    text = current.percent?.let { "$it% · ${current.speed}" } ?: current.speed,
                    style = MaterialTheme.typography.bodySmall,
                )

            else -> Unit
        }
    }

    when (val current = state) {
        is UpdateState.Available -> AlertDialog(
            onDismissRequest = viewModel::dismiss,
            title = { Text(stringResource(R.string.update_available, current.release.versionName)) },
            confirmButton = {
                TextButton(onClick = { viewModel.download(current.release) }) {
                    Text(stringResource(R.string.update_download))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismiss) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )

        is UpdateState.UpToDate -> MessageDialog(
            text = stringResource(R.string.update_none),
            onDismiss = viewModel::dismiss,
        )

        is UpdateState.Failed -> MessageDialog(
            text = stringResource(R.string.update_failed, current.message),
            onDismiss = viewModel::dismiss,
        )

        else -> Unit
    }
}

@Composable
private fun MessageDialog(text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
        },
    )
}
