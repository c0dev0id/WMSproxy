package de.codevoid.wmsproxy

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.codevoid.wmsproxy.core.LoggedRequest
import de.codevoid.wmsproxy.proxy.BuiltInSources
import de.codevoid.wmsproxy.proxy.ProxyService
import de.codevoid.wmsproxy.update.UpdateState
import de.codevoid.wmsproxy.update.UpdateViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen()
                }
            }
        }
    }
}

@Composable
private fun MainScreen(updateViewModel: UpdateViewModel = viewModel()) {
    val context = LocalContext.current
    val running by ProxyService.running.collectAsStateWithLifecycle()

    // The log is a plain snapshot rather than a stream: it is read when the user looks,
    // and re-read on demand, which is enough for a diagnostic and costs nothing while
    // tiles are being served.
    var entries by remember { mutableStateOf(emptyList<LoggedRequest>()) }
    fun refresh() {
        entries = ProxyService.log.snapshot().asReversed()
    }

    // Android 13+ will not show the service notification without this, and a foreground
    // service with no visible notification is a confusing thing to debug.
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(Unit) {
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        refresh()
    }

    LazyColumn(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = stringResource(R.string.installed_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (running) ProxyService.stop(context) else ProxyService.start(context)
                    },
                ) {
                    Text(
                        stringResource(
                            if (running) R.string.stop_service else R.string.start_service,
                        ),
                    )
                }
                OutlinedButton(onClick = { refresh() }) {
                    Text(stringResource(R.string.refresh))
                }
            }
            Text(
                text = stringResource(
                    if (running) R.string.service_running else R.string.service_stopped,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        item { UrlCard(context) }

        item {
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.request_log, entries.size),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { shareLog(context) }) {
                    Text(stringResource(R.string.share_log))
                }
                OutlinedButton(onClick = { copyLog(context) }) {
                    Text(stringResource(R.string.copy_log))
                }
                OutlinedButton(
                    onClick = {
                        ProxyService.log.clear()
                        refresh()
                    },
                ) {
                    Text(stringResource(R.string.clear))
                }
            }
        }

        if (entries.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.log_empty),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        items(entries) { entry ->
            Text(
                text = entry.format(),
                style = MaterialTheme.typography.bodySmall,
                overflow = TextOverflow.Ellipsis,
            )
        }

        item {
            HorizontalDivider()
            UpdateSection(updateViewModel)
        }
    }
}

@Composable
private fun UrlCard(context: Context) {
    Card {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.urls_title),
                style = MaterialTheme.typography.titleMedium,
            )

            BuiltInSources.all.forEach { source ->
                UrlRow(
                    label = stringResource(R.string.url_xyz, source.title),
                    value = ProxyService.server.tileTemplateFor(source),
                    context = context,
                )
                UrlRow(
                    label = stringResource(R.string.url_wms_template, source.title),
                    value = ProxyService.server.wmsTemplateFor(source),
                    context = context,
                )
            }

            UrlRow(
                label = stringResource(R.string.url_wms),
                value = ProxyService.server.wmsUrl,
                context = context,
            )

            Text(
                text = stringResource(R.string.urls_hint),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun UrlRow(label: String, value: String, context: Context) {
    Column {
        Text(text = label, style = MaterialTheme.typography.labelMedium)
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = { copy(context, label, value) }) {
            Text(stringResource(R.string.copy))
        }
    }
}

@Composable
private fun UpdateSection(viewModel: UpdateViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pendingInstall by viewModel.pendingInstall.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(pendingInstall) {
        pendingInstall?.let { file ->
            context.startActivity(viewModel.installIntentFor(file))
            viewModel.installHandled()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Button(onClick = viewModel::check, enabled = !state.busy) {
            Text(stringResource(R.string.check_for_updates))
        }

        when (val current = state) {
            is UpdateState.Checking ->
                Text(stringResource(R.string.checking_updates), style = MaterialTheme.typography.bodySmall)

            is UpdateState.Downloading ->
                Text(
                    // Concatenated, never run through a format string: a literal % in a
                    // template is a crash waiting to happen.
                    text = current.percent?.let { "$it% · ${current.speed}" } ?: current.speed,
                    style = MaterialTheme.typography.bodySmall,
                )

            is UpdateState.UpToDate ->
                Text(stringResource(R.string.update_none), style = MaterialTheme.typography.bodySmall)

            is UpdateState.Failed ->
                Text(
                    stringResource(R.string.update_failed, current.message),
                    style = MaterialTheme.typography.bodySmall,
                )

            is UpdateState.Available ->
                Button(onClick = { viewModel.download(current.release) }) {
                    Text(stringResource(R.string.update_available, current.release.versionName))
                }

            UpdateState.Idle -> Unit
        }
    }
}

private fun logText(): String = ProxyService.log.asText(
    "WMSproxy ${BuildConfig.VERSION_NAME} — request log",
)

private fun shareLog(context: Context) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "WMSproxy request log")
        putExtra(Intent.EXTRA_TEXT, logText())
    }
    context.startActivity(Intent.createChooser(intent, null))
}

private fun copyLog(context: Context) = copy(context, "WMSproxy log", logText())

private fun copy(context: Context, label: String, value: String) {
    context.getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText(label, value))
}
