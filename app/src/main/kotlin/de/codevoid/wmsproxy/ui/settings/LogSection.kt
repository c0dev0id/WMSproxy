package de.codevoid.wmsproxy.ui.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.codevoid.wmsproxy.BuildConfig
import de.codevoid.wmsproxy.R
import de.codevoid.wmsproxy.dmd.DmdViewModel
import de.codevoid.wmsproxy.proxy.ProxyService
import de.codevoid.wmsproxy.ui.ErrorText
import de.codevoid.wmsproxy.ui.copyToClipboard
import de.codevoid.wmsproxy.update.UpdateState
import de.codevoid.wmsproxy.update.UpdateViewModel

/** The request log, following its tail until the user scrolls up to read something. */
@Composable
internal fun ColumnScope.LogSection(context: Context, dmd: DmdViewModel) {
    // Collected here and nowhere higher: the log grows an entry per proxied tile, and
    // only the log itself should pay for its own churn.
    val entries by ProxyService.log.requests.collectAsStateWithLifecycle()
    val session by dmd.session.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // One entry of slack, because the item that just arrived must not itself count as
    // having scrolled away from the tail.
    val following by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf true
            lastVisible >= info.totalItemsCount - 2
        }
    }

    LaunchedEffect(entries.size) {
        if (following && entries.isNotEmpty()) listState.scrollToItem(entries.lastIndex)
    }

    if (BuildConfig.DEBUG) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = { shareLog(context) }) { Text(stringResource(R.string.share_log)) }
            OutlinedButton(onClick = { copyLog(context) }) { Text(stringResource(R.string.copy_log)) }
            OutlinedButton(onClick = { ProxyService.log.clear() }) { Text(stringResource(R.string.clear)) }
        }
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = dmd::dumpAccountLayers, enabled = session != null) {
                Text(stringResource(R.string.log_dmd_layers))
            }
            OutlinedButton(onClick = { copyToClipboard(context, "WMSproxy probe", ProxyService.server.probeTemplate) }) {
                Text(stringResource(R.string.copy_probe))
            }
        }
    }

    Text(
        text = stringResource(if (following) R.string.log_following else R.string.log_paused),
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )

    HorizontalDivider()

    if (entries.isEmpty()) {
        Text(
            text = stringResource(R.string.log_empty),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(16.dp),
        )
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Oldest first, so the newest is at the bottom and the log reads as the shared text does.
        items(entries) { entry ->
            Text(
                text = entry.format(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/** The update check and, when one is found, the download and install hand-off. */
@Composable
internal fun UpdateSection(viewModel: UpdateViewModel) {
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
                ErrorText(stringResource(R.string.update_failed, current.message))

            is UpdateState.Available ->
                Button(onClick = { viewModel.download(current.release) }) {
                    Text(stringResource(R.string.update_available, current.release.versionName))
                }

            UpdateState.Idle -> Unit
        }
    }
}

private fun logText(): String = ProxyService.log.asText("WMSproxy ${BuildConfig.VERSION_NAME} — request log")

private fun shareLog(context: Context) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "WMSproxy request log")
        putExtra(Intent.EXTRA_TEXT, logText())
    }
    context.startActivity(Intent.createChooser(intent, null))
}

private fun copyLog(context: Context) = copyToClipboard(context, "WMSproxy log", logText())
