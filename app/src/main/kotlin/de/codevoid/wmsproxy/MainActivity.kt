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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.codevoid.wmsproxy.core.LoggedRequest
import de.codevoid.wmsproxy.core.DiscoveredLayer
import de.codevoid.wmsproxy.core.SourceValidator
import de.codevoid.wmsproxy.core.TileLayer
import de.codevoid.wmsproxy.proxy.ImportState
import de.codevoid.wmsproxy.proxy.ImportViewModel
import de.codevoid.wmsproxy.proxy.ProxyService
import de.codevoid.wmsproxy.proxy.Sources
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

/**
 * Three tabs under a permanent status bar.
 *
 * The status line and the start/stop control stay visible on every tab because they
 * answer the question asked most often — is it running — and because switching tabs to
 * find out would be one step too many while a phone is on a handlebar. The log gets a
 * tab of its own rather than a section at the bottom of a long page: it is the project's
 * primary diagnostic, and it needs the whole height to be worth reading.
 */
@Composable
private fun MainScreen(updateViewModel: UpdateViewModel = viewModel()) {
    val context = LocalContext.current
    val running by ProxyService.running.collectAsStateWithLifecycle()
    val entries by ProxyService.log.requests.collectAsStateWithLifecycle()
    val layers by Sources.layers.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }

    // Android 13+ will not show the service notification without this, and a foreground
    // service with no visible notification is a confusing thing to debug.
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(Unit) {
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        StatusBar(running, context)

        TabRow(selectedTabIndex = tab) {
            Tab(
                selected = tab == 0,
                onClick = { tab = 0 },
                text = { Text(stringResource(R.string.tab_sources)) },
            )
            Tab(
                selected = tab == 1,
                onClick = { tab = 1 },
                text = { Text(stringResource(R.string.tab_log, entries.size)) },
            )
            Tab(
                selected = tab == 2,
                onClick = { tab = 2 },
                text = { Text(stringResource(R.string.tab_app)) },
            )
        }

        when (tab) {
            0 -> SourcesTab(layers, context)
            1 -> LogTab(entries, context)
            else -> AppTab(updateViewModel, context)
        }
    }
}

@Composable
private fun StatusBar(running: Boolean, context: Context) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = {
                    if (running) ProxyService.stop(context) else ProxyService.start(context)
                },
            ) {
                Text(stringResource(if (running) R.string.stop_service else R.string.start_service))
            }
            Text(
                text = stringResource(
                    if (running) R.string.service_running else R.string.service_stopped,
                ),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// ---------------------------------------------------------------- sources

@Composable
private fun ColumnScope.SourcesTab(layers: List<TileLayer>, context: Context) {
    // null means no dialog. A TileLayer with a blank source means "new", which is also
    // the empty form the editor starts from.
    var editing by remember { mutableStateOf<TileLayer?>(null) }
    var creating by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(onClick = { creating = true; editing = TileLayer(source = "") }) {
            Text(stringResource(R.string.add_source))
        }
        OutlinedButton(onClick = { importing = true }) {
            Text(stringResource(R.string.import_source))
        }
        OutlinedButton(onClick = { Sources.restoreDefaults() }) {
            Text(stringResource(R.string.restore_defaults))
        }
    }

    LazyColumn(
        modifier = Modifier
            .weight(1f)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (layers.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.no_sources),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        items(layers) { layer ->
            SourceCard(
                layer = layer,
                context = context,
                onEdit = { creating = false; editing = layer },
                onDelete = { Sources.remove(layer) },
            )
        }

        item {
            Text(
                text = stringResource(R.string.urls_hint),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (importing) {
        ImportDialog(existing = layers, onDismiss = { importing = false })
    }

    editing?.let { target ->
        SourceEditor(
            initial = target,
            // An edit must not collide with everything except itself, so the source
            // being edited is excluded from the duplicate check.
            existing = if (creating) layers else layers.filterNot { it == target },
            onDismiss = { editing = null },
            onSave = { saved ->
                if (creating) Sources.add(saved) else Sources.replace(target, saved)
                editing = null
            },
        )
    }
}

@Composable
private fun SourceCard(
    layer: TileLayer,
    context: Context,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = layer.title.ifBlank { layer.path },
                style = MaterialTheme.typography.titleMedium,
            )

            UrlRow(
                label = stringResource(R.string.url_http, layer.path),
                value = ProxyService.server.templateFor(layer),
                context = context,
            )
            UrlRow(
                label = stringResource(R.string.url_https, layer.path),
                value = ProxyService.server.secureTemplateFor(layer),
                context = context,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onEdit) { Text(stringResource(R.string.edit)) }
                OutlinedButton(onClick = onDelete) { Text(stringResource(R.string.delete)) }
            }
        }
    }
}

@Composable
private fun UrlRow(label: String, value: String, context: Context) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = label, style = MaterialTheme.typography.labelMedium)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = { copy(context, label, value) }) {
                Text(stringResource(R.string.copy))
            }
        }
    }
}

@Composable
private fun SourceEditor(
    initial: TileLayer,
    existing: List<TileLayer>,
    onDismiss: () -> Unit,
    onSave: (TileLayer) -> Unit,
) {
    var source by remember { mutableStateOf(initial.source) }
    var layerName by remember { mutableStateOf(initial.layer ?: "") }
    var title by remember { mutableStateOf(initial.title) }
    var url by remember { mutableStateOf(initial.urlTemplate) }
    var subdomains by remember { mutableStateOf(initial.subdomains.joinToString(", ")) }
    var referer by remember { mutableStateOf(initial.referer ?: "") }
    var flipY by remember { mutableStateOf(initial.flipY) }
    var error by remember { mutableStateOf<String?>(null) }

    fun build() = TileLayer(
        source = source.trim(),
        // Absent rather than empty: the path segment is left out entirely when a
        // provider has no layer concept.
        layer = layerName.trim().ifBlank { null },
        title = title.trim(),
        urlTemplate = url.trim(),
        flipY = flipY,
        subdomains = subdomains.split(',').map { it.trim() }.filter { it.isNotEmpty() },
        referer = referer.trim().ifBlank { null },
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (initial.source.isBlank()) R.string.new_source else R.string.edit_source,
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Field(source, { source = it }, R.string.field_source)
                Field(layerName, { layerName = it }, R.string.field_layer)
                Field(title, { title = it }, R.string.field_title)
                Field(url, { url = it }, R.string.field_url)
                Field(subdomains, { subdomains = it }, R.string.field_subdomains)
                Field(referer, { referer = it }, R.string.field_referer)

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Switch(checked = flipY, onCheckedChange = { flipY = it })
                    Text(
                        text = stringResource(R.string.field_flip_y),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val candidate = build()
                    // Validated in :core, so the same rule holds wherever a source is
                    // created — including an imported config later on.
                    val problem = SourceValidator.validate(candidate, existing)
                    if (problem == null) onSave(candidate) else error = problem
                },
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: Int) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Paste a service URL, read what it offers, tick the layers to keep.
 *
 * Skipped layers are listed with their reason rather than hidden. A layer missing from
 * the list looks like a bug in this app; a layer shown as "offers only vector tiles" is
 * an answer, and it is usually the server's decision rather than something to fix here.
 */
@Composable
private fun ImportDialog(
    existing: List<TileLayer>,
    onDismiss: () -> Unit,
    viewModel: ImportViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var url by rememberSaveable { mutableStateOf("") }
    var provider by rememberSaveable { mutableStateOf("") }
    val selected = remember { mutableStateListOf<DiscoveredLayer>() }

    fun close() {
        viewModel.reset()
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = ::close,
        title = { Text(stringResource(R.string.import_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Field(url, { url = it }, R.string.field_capabilities_url)
                Text(
                    text = stringResource(R.string.import_hint),
                    style = MaterialTheme.typography.bodySmall,
                )

                when (val current = state) {
                    is ImportState.Fetching -> Text(
                        text = stringResource(R.string.fetching),
                        style = MaterialTheme.typography.bodySmall,
                    )

                    is ImportState.Failed -> Text(
                        text = current.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )

                    is ImportState.Loaded -> {
                        // Proposed from the service's own title, and editable. Seeded on
                        // first sight of the document so it is visible rather than a
                        // surprise applied at save time.
                        LaunchedEffect(current.document) {
                            if (provider.isBlank()) provider = current.document.suggestedSourceId()
                        }
                        Field(provider, { provider = it }, R.string.field_provider)

                        current.document.layers.forEach { layer ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = layer in selected,
                                    onCheckedChange = { checked ->
                                        if (checked) selected += layer else selected -= layer
                                    },
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = layer.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        text = "${layer.service} · ${layer.format}",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }

                        if (current.document.skipped.isNotEmpty()) {
                            HorizontalDivider()
                            Text(
                                text = stringResource(
                                    R.string.import_skipped,
                                    current.document.skipped.size,
                                ),
                                style = MaterialTheme.typography.labelMedium,
                            )
                            current.document.skipped.forEach {
                                Text(
                                    text = "${it.name}: ${it.reason}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }

                    ImportState.Idle -> Unit
                }
            }
        },
        confirmButton = {
            val loaded = state as? ImportState.Loaded
            if (loaded == null) {
                TextButton(onClick = { viewModel.fetch(url) }, enabled = !state.busy) {
                    Text(stringResource(R.string.fetch))
                }
            } else {
                TextButton(
                    onClick = {
                        val name = provider.ifBlank { loaded.document.suggestedSourceId() }
                        // Validated one at a time against what is already stored plus
                        // what this batch has added, so two layers cannot both claim the
                        // same route.
                        val accumulated = existing.toMutableList()
                        selected.forEach { discovered ->
                            val candidate = TileLayer(
                                source = name,
                                layer = discovered.suggestedLayerId(),
                                title = discovered.title,
                                urlTemplate = discovered.template,
                            )
                            if (SourceValidator.validate(candidate, accumulated) == null) {
                                accumulated += candidate
                                Sources.add(candidate)
                            }
                        }
                        close()
                    },
                    enabled = selected.isNotEmpty(),
                ) {
                    Text(stringResource(R.string.import_add, selected.size))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = ::close) { Text(stringResource(R.string.cancel)) }
        },
    )
}

// ---------------------------------------------------------------- log

@Composable
private fun ColumnScope.LogTab(entries: List<LoggedRequest>, context: Context) {
    val listState = rememberLazyListState()

    // Follow the tail only while the tail is what is being looked at. Scrolling up is
    // how the user reads an earlier failure, and yanking them back every time a tile
    // arrives would make the log unreadable exactly when it matters. One entry of slack,
    // because the item that just arrived must not itself count as having scrolled away.
    val following by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index
                ?: return@derivedStateOf true
            lastVisible >= info.totalItemsCount - 2
        }
    }

    LaunchedEffect(entries.size) {
        if (following && entries.isNotEmpty()) listState.scrollToItem(entries.lastIndex)
    }

    Row(
        modifier = Modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(onClick = { shareLog(context) }) {
            Text(stringResource(R.string.share_log))
        }
        OutlinedButton(onClick = { copyLog(context) }) {
            Text(stringResource(R.string.copy_log))
        }
        OutlinedButton(onClick = { ProxyService.log.clear() }) {
            Text(stringResource(R.string.clear))
        }
    }

    Text(
        text = stringResource(
            if (following) R.string.log_following else R.string.log_paused,
        ),
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
        // Oldest first, so the newest is at the bottom and the log reads the same way
        // the shared text does.
        items(entries) { entry ->
            Text(
                text = entry.format(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

// ---------------------------------------------------------------- app

@Composable
private fun ColumnScope.AppTab(viewModel: UpdateViewModel, context: Context) {
    Column(
        modifier = Modifier
            .weight(1f)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.installed_version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodyMedium,
        )

        HorizontalDivider()
        UpdateSection(viewModel)

        HorizontalDivider()
        OutlinedButton(onClick = { shareCertificate(context) }) {
            Text(stringResource(R.string.export_certificate))
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

/**
 * Writes the certificate to a shareable file. Installing it is only useful on a device
 * whose client apps opt into user-installed CAs, or where it can reach the system store.
 */
private fun shareCertificate(context: Context) {
    val file = java.io.File(context.cacheDir, "updates").apply { mkdirs() }
        .resolve("wmsproxy-localhost.crt")
    file.writeBytes(de.codevoid.wmsproxy.proxy.Tls.certificateBytes(context))

    val uri = androidx.core.content.FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/x-x509-ca-cert"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, null))
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
