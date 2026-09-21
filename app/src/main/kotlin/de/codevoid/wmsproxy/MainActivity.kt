package de.codevoid.wmsproxy

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.codevoid.wmsproxy.core.DiscoveredLayer
import de.codevoid.wmsproxy.core.DmdSyncChoice
import de.codevoid.wmsproxy.core.LibraryEntry
import de.codevoid.wmsproxy.core.Rewrite
import de.codevoid.wmsproxy.core.SourceValidator
import de.codevoid.wmsproxy.core.TileLayer
import de.codevoid.wmsproxy.core.choiceFor
import de.codevoid.wmsproxy.core.directBlocker
import de.codevoid.wmsproxy.core.rewrites
import de.codevoid.wmsproxy.core.sendsDirect
import de.codevoid.wmsproxy.dmd.DmdSession
import de.codevoid.wmsproxy.dmd.DmdStatus
import de.codevoid.wmsproxy.dmd.DmdSyncState
import de.codevoid.wmsproxy.dmd.DmdViewModel
import de.codevoid.wmsproxy.library.LibraryPrefs
import de.codevoid.wmsproxy.proxy.BundledLibrary
import de.codevoid.wmsproxy.proxy.ImportState
import de.codevoid.wmsproxy.proxy.SourcesViewModel
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
private fun MainScreen(
    updateViewModel: UpdateViewModel = viewModel(),
    sourcesViewModel: SourcesViewModel = viewModel(),
) {
    val context = LocalContext.current
    val running by ProxyService.running.collectAsStateWithLifecycle()
    val config by Sources.config.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // null means no import dialog. A non-null value is the URL it opens with, so the
    // library can hand over a chosen service and the Sources tab can start from blank.
    var importUrl by remember { mutableStateOf<String?>(null) }

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
                text = { Text(stringResource(R.string.tab_library)) },
            )
            Tab(
                selected = tab == 2,
                onClick = { tab = 2 },
                text = { Text(stringResource(R.string.tab_dmd)) },
            )
            Tab(
                selected = tab == 3,
                onClick = { tab = 3 },
                text = { Text(stringResource(R.string.tab_settings)) },
            )
        }

        ProbingLine(sourcesViewModel)

        when (tab) {
            0 -> SourcesTab(config.layers, sourcesViewModel, onImport = { importUrl = "" })
            1 -> LibraryTab(onAdd = { importUrl = it })
            2 -> DmdTab(config.layers)
            else -> SettingsTab(updateViewModel, context)
        }
    }

    // Held here rather than in either tab, because both open it and the dialog has to
    // outlive a tab switch made while it is up.
    importUrl?.let { initial ->
        ImportDialog(
            existing = config.layers,
            initialUrl = initial,
            onDismiss = { importUrl = null },
        )
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

/**
 * Above the tabs, not inside one: a source added from the library is measured after the
 * dialog closes, and the tab it was added from is not where the user necessarily is by
 * then. Hand-typed sources are measured the same way.
 */
@Composable
private fun ProbingLine(viewModel: SourcesViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    (state as? ImportState.Probing)?.let {
        Text(
            text = stringResource(R.string.probing, it.zoom) + " · " + it.layer,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun ColumnScope.SourcesTab(
    layers: List<TileLayer>,
    viewModel: SourcesViewModel,
    onImport: () -> Unit,
) {
    // null means no dialog. A TileLayer with a blank source means "new", which is also
    // the empty form the editor starts from.
    var editing by remember { mutableStateOf<TileLayer?>(null) }

    Row(
        modifier = Modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(onClick = { editing = TileLayer(source = "") }) {
            Text(stringResource(R.string.add_source))
        }
        OutlinedButton(onClick = onImport) {
            Text(stringResource(R.string.import_source))
        }
    }

    LazyColumn(
        modifier = Modifier
            .weight(1f)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        // Tighter than the 12dp elsewhere, because the rows are two lines.
        verticalArrangement = Arrangement.spacedBy(6.dp),
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
                onEdit = { editing = layer },
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

    editing?.let { target ->
        SourceEditor(
            initial = target,
            // An edit must not collide with everything except itself, so the source
            // being edited is excluded from the duplicate check. A new source is not in
            // the list, so the filter leaves it whole.
            existing = layers.filterNot { it == target },
            onDismiss = { editing = null },
            onSave = { saved ->
                // A new source is measured before it is stored; an edit keeps whatever
                // was measured before, since the range belongs to the server rather than
                // to the name or title that just changed.
                if (target.source.isBlank()) {
                    viewModel.addAll(listOf(saved to null), layers)
                } else {
                    Sources.replace(target, saved)
                }
                editing = null
            },
        )
    }
}

@Composable
private fun SourceCard(
    layer: TileLayer,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    var copying by remember { mutableStateOf(false) }

    fun choose(address: String) {
        copying = false
        copy(context, layer.path, address)
    }

    // One row per source, not a card with a heading: the list is scrolled to find a URL
    // to copy, and a title styled as a heading pushed each entry to four lines for two
    // lines of content.
    WrappingRow(
        info = {
            Text(
                text = layer.displayName,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = layer.urlTemplate,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            // What the proxy does for this source, so a reader can tell what a direct
            // paste would lose. Nothing is said for a source it only relays.
            val rewrites = layer.rewrites()
            if (rewrites.isNotEmpty()) {
                Text(
                    text = stringResource(
                        R.string.proxy_rewrites,
                        rewrites.map { stringResource(it.description) }.joinToString(),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Text(
                text = layer.zoomRangeLabel()
                    ?.let { stringResource(R.string.zoom_range, it) }
                    ?: stringResource(R.string.zoom_untested),
                style = MaterialTheme.typography.labelSmall,
            )
        },
        // Text buttons rather than outlined: three outlines in a row read as a toolbar
        // competing with the URL. The layer path names the clipboard entry.
        controls = {
            // Three addresses, chosen at the moment of copying rather than by a setting:
            // which one a paste needs depends on where it is going, not on the source.
            // The direct one is always offered; the row above says what the proxy would
            // have done for it, and the DMD tab is where the gate is.
            Box {
                TextButton(onClick = { copying = true }) {
                    Text(stringResource(R.string.copy))
                }
                DropdownMenu(expanded = copying, onDismissRequest = { copying = false }) {
                    val server = ProxyService.server
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.copy_direct)) },
                        onClick = { choose(layer.urlTemplate) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.copy_proxy_https)) },
                        onClick = { choose(server.templateFor(layer)) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.copy_proxy_http)) },
                        onClick = { choose(server.plainTemplateFor(layer)) },
                    )
                }
            }
            TextButton(onClick = onEdit) { Text(stringResource(R.string.edit)) }
            TextButton(onClick = onDelete) { Text(stringResource(R.string.delete)) }
        },
    )
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

    // A copy, so what the form does not show — the measured zoom range — survives an edit.
    fun build() = initial.copy(
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
        // As wide as the import dialog, and for the same reason: the URL template field
        // holds a hundred characters and a platform-width dialog shows a dozen of them.
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
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

                LabelledSwitch(R.string.field_flip_y, checked = flipY, onCheckedChange = { flipY = it })

                error?.let { ErrorText(it) }
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
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: Int,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        singleLine = true,
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
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
    initialUrl: String = "",
    viewModel: SourcesViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var url by rememberSaveable { mutableStateOf(initialUrl) }
    var provider by rememberSaveable { mutableStateOf("") }
    val selected = remember { mutableStateListOf<DiscoveredLayer>() }

    fun close() {
        viewModel.reset()
        onDismiss()
    }

    // Opened from the library, the service is already chosen, so fetching without a
    // second tap is the point of having picked it from a list. A blank one is the
    // Sources tab's own button, where there is nothing to fetch yet.
    LaunchedEffect(initialUrl) {
        if (initialUrl.isNotBlank()) viewModel.fetch(initialUrl)
    }

    AlertDialog(
        onDismissRequest = ::close,
        // Wider than a platform dialog: the fields hold capabilities URLs and service
        // names that run to a hundred characters, and the default width turns both into
        // a keyhole. usePlatformDefaultWidth has to be off for a modifier to widen it.
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
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

                    is ImportState.Probing -> Text(
                        text = stringResource(R.string.probing, current.zoom),
                        style = MaterialTheme.typography.bodySmall,
                    )

                    is ImportState.Failed -> ErrorText(current.message)

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
                        viewModel.addAll(
                            selected.map { discovered ->
                                TileLayer(
                                    source = name,
                                    layer = discovered.suggestedLayerId(),
                                    title = discovered.title,
                                    urlTemplate = discovered.template,
                                ) to discovered.centre
                            },
                            existing,
                        )
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

/**
 * The shipped list, with a region filter, on a tab of its own.
 *
 * It outgrew the import dialog: sixty services in a modal meant a scroll inside a scroll
 * with four entries visible, and choosing a service is a different job from choosing
 * layers out of one. Adding hands the URL to that dialog and the usual flow takes over,
 * so the library still never claims a layer works — the server is asked.
 */
@Composable
private fun ColumnScope.LibraryTab(onAdd: (String) -> Unit) {
    val context = LocalContext.current
    val library = remember { BundledLibrary.get(context) }

    if (library.entries.isEmpty()) {
        Text(
            text = stringResource(R.string.library_empty),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
        )
        return
    }

    val region by LibraryPrefs.region.collectAsStateWithLifecycle()
    val category by LibraryPrefs.category.collectAsStateWithLifecycle()
    var nameQuery by rememberSaveable { mutableStateOf("") }
    val hasFilter = region != null || category != null || nameQuery.isNotBlank()

    val regionCounts = remember { library.byRegion().mapValues { it.value.size } }
    val allCategories = remember { library.allCategories }
    val shown = remember(region, category, nameQuery) { library.filtered(region, category, nameQuery) }
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val regionDropdown: @Composable () -> Unit = {
        var expanded by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(region ?: stringResource(R.string.library_region_all))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.library_region_all)) },
                    onClick = { LibraryPrefs.setRegion(null); expanded = false },
                )
                regionCounts.forEach { (name, count) ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.library_region_count, name, count)) },
                        onClick = { LibraryPrefs.setRegion(name); expanded = false },
                    )
                }
            }
        }
    }

    val categoryDropdown: @Composable () -> Unit = {
        var expanded by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(category ?: stringResource(R.string.library_category_all))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.library_category_all)) },
                    onClick = { LibraryPrefs.setCategory(null); expanded = false },
                )
                allCategories.forEach { name ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = { LibraryPrefs.setCategory(name); expanded = false },
                    )
                }
            }
        }
    }

    val searchAndClear: @Composable RowScope.() -> Unit = {
        OutlinedTextField(
            value = nameQuery,
            onValueChange = { nameQuery = it },
            modifier = Modifier.weight(1f),
            label = { Text(stringResource(R.string.library_search)) },
            singleLine = true,
        )
        if (hasFilter) {
            TextButton(onClick = { LibraryPrefs.clear(); nameQuery = "" }) {
                Text(stringResource(R.string.clear))
            }
        }
    }

    if (isLandscape) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            regionDropdown()
            categoryDropdown()
            searchAndClear()
        }
    } else {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                regionDropdown()
                categoryDropdown()
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                searchAndClear()
            }
        }
    }

    if (library.verified.isNotBlank()) {
        Text(
            // Said plainly rather than implied: the list records when it was last
            // checked, and a service can withdraw or move at any time after that.
            text = stringResource(R.string.library_checked, library.verified),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }

    LazyColumn(
        modifier = Modifier
            .weight(1f)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        shown.forEach { (name, entries) ->
            item(key = "region:$name") {
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            items(entries, key = { it.url }) { entry ->
                LibraryRow(entry, onAdd)
            }
        }
    }
}

@Composable
private fun LibraryRow(entry: LibraryEntry, onAdd: (String) -> Unit) {
    // A plain row rather than a WrappingRow: sixty entries are browsed, not scrolled for
    // a URL, and a card each would double the height of the list. So the text column
    // yields instead — a long note wraps under the name while the count and the button
    // keep their line — which is the trade the source list makes the other way.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = entry.name, style = MaterialTheme.typography.bodyMedium)
            if (entry.note.isNotBlank()) {
                Text(text = entry.note, style = MaterialTheme.typography.labelSmall)
            }
        }
        // The count, not a status colour: every service here works, and what separates
        // them is whether picking a layer is a glance or a hunt. The second number
        // appears only when the server offers layers this proxy cannot serve, so its
        // presence is itself the warning. Nothing is shown for an unmeasured entry.
        if (entry.measured) {
            Text(
                text = if (entry.refused > 0) {
                    stringResource(R.string.library_layers_partial, entry.usable, entry.total)
                } else {
                    pluralStringResource(R.plurals.library_layers, entry.usable, entry.usable)
                },
                style = MaterialTheme.typography.labelSmall,
            )
        }
        OutlinedButton(onClick = { onAdd(entry.url) }) {
            Text(stringResource(R.string.library_add))
        }
    }
}

// ---------------------------------------------------------------- log

@Composable
private fun ColumnScope.LogSection(context: Context) {
    // Collected here, not in MainScreen: the log grows a new entry per proxied tile, and
    // reading it higher up would recompose every tab. Confined here, only the log itself
    // pays for its own churn.
    val entries by ProxyService.log.requests.collectAsStateWithLifecycle()
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

// ---------------------------------------------------------------- dmd

/**
 * Sign-in to the DMD Hub account, and the sync of sources into it.
 *
 * The form and the signed-in view are the same tab, chosen by whether a session exists:
 * the account is either connected or it is not, and a modal for one state would be a
 * detour. The connection line under the name is not decoration — a remembered token can
 * have lapsed, so the tab confirms it against the server rather than trusting that having
 * a token means being signed in.
 */
@Composable
private fun ColumnScope.DmdTab(layers: List<TileLayer>, viewModel: DmdViewModel = viewModel()) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val syncState by viewModel.sync.collectAsStateWithLifecycle()
    val choices by viewModel.choices.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val current = session
        if (current == null) {
            DmdSignIn(status, onSignIn = viewModel::login)
        } else {
            DmdSignedIn(
                session = current,
                status = status,
                syncState = syncState,
                layers = layers,
                choices = choices,
                onEnabled = viewModel::setSourceEnabled,
                onDirect = viewModel::setSourceDirect,
                onSync = viewModel::syncNow,
                onSignOut = viewModel::logout,
            )
        }
    }
}

@Composable
private fun DmdSignIn(status: DmdStatus, onSignIn: (String, String) -> Unit) {
    var email by rememberSaveable { mutableStateOf("") }
    // Deliberately not rememberSaveable: that Bundle is serialized to disk unencrypted,
    // and keeping the password out of plaintext-at-rest is the whole point of SecureStore.
    var password by remember { mutableStateOf("") }

    Text(
        text = stringResource(R.string.dmd_intro),
        style = MaterialTheme.typography.bodyMedium,
    )

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

    val busy = status is DmdStatus.Busy
    Button(
        onClick = { onSignIn(email, password) },
        enabled = !busy && email.isNotBlank() && password.isNotBlank(),
    ) {
        Text(stringResource(if (busy) R.string.dmd_signing_in else R.string.dmd_sign_in))
    }

    (status as? DmdStatus.Error)?.let {
        ErrorText(stringResource(R.string.dmd_error, it.message))
    }
}

@Composable
private fun DmdSignedIn(
    session: DmdSession,
    status: DmdStatus,
    syncState: DmdSyncState,
    layers: List<TileLayer>,
    choices: Map<String, DmdSyncChoice>,
    onEnabled: (String, Boolean) -> Unit,
    onDirect: (String, Boolean) -> Unit,
    onSync: () -> Unit,
    onSignOut: () -> Unit,
) {
    Text(
        text = stringResource(R.string.dmd_signed_in, session.name),
        style = MaterialTheme.typography.titleMedium,
    )

    when (status) {
        is DmdStatus.Error -> ErrorText(status.message)
        else -> Text(
            text = stringResource(
                if (status is DmdStatus.Busy) R.string.dmd_checking else R.string.dmd_connected,
            ),
            style = MaterialTheme.typography.bodySmall,
        )
    }

    HorizontalDivider()

    Text(
        text = stringResource(R.string.dmd_sources_title),
        style = MaterialTheme.typography.titleMedium,
    )

    if (layers.isEmpty()) {
        Text(
            text = stringResource(R.string.dmd_no_sources),
            style = MaterialTheme.typography.bodyMedium,
        )
    } else {
        layers.forEach { layer ->
            DmdSourceCard(
                layer = layer,
                choice = choices.choiceFor(layer.path),
                onEnabled = { onEnabled(layer.path, it) },
                onDirect = { onDirect(layer.path, it) },
            )
        }
    }

    val syncing = syncState is DmdSyncState.Syncing
    Button(onClick = onSync, enabled = !syncing) {
        Text(stringResource(if (syncing) R.string.dmd_syncing else R.string.dmd_sync))
    }

    when (syncState) {
        is DmdSyncState.Done -> Text(
            text = pluralStringResource(R.plurals.dmd_synced, syncState.count, syncState.count),
            style = MaterialTheme.typography.bodySmall,
        )
        is DmdSyncState.Failed -> ErrorText(stringResource(R.string.dmd_sync_failed, syncState.message))
        else -> Unit
    }

    TextButton(onClick = onSignOut) {
        Text(stringResource(R.string.dmd_sign_out))
    }
}

/**
 * One source's sync choices: whether it is pushed at all, and whether it goes direct.
 *
 * The direct switch is only offered when the source can be served without the proxy —
 * see [Rewrite] for what cannot — otherwise it is disabled and the caption says what
 * the source needs. Disabling the whole source also disables the direct switch, since a
 * layer that is not pushed has no URL to choose.
 */
@Composable
private fun DmdSourceCard(
    layer: TileLayer,
    choice: DmdSyncChoice,
    onEnabled: (Boolean) -> Unit,
    onDirect: (Boolean) -> Unit,
) {
    val blocker = layer.directBlocker()
    val direct = layer.sendsDirect(choice)

    WrappingRow(
        info = {
            Text(
                text = layer.displayName,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = when {
                    blocker != null ->
                        stringResource(R.string.dmd_source_proxy_only, stringResource(blocker.label))
                    direct -> stringResource(R.string.dmd_source_direct)
                    else -> stringResource(R.string.dmd_source_proxy)
                },
                style = MaterialTheme.typography.labelSmall,
            )
        },
        // The two switches travel together, in one row of their own: split across lines
        // they would read as two unrelated controls. Label before switch, unlike the
        // standalone settings, so the pair reads as two columns of one control.
        controls = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.dmd_switch_sync), style = MaterialTheme.typography.labelMedium)
                    Switch(checked = choice.enabled, onCheckedChange = onEnabled)
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.dmd_switch_direct), style = MaterialTheme.typography.labelMedium)
                    Switch(
                        checked = direct,
                        enabled = blocker == null && choice.enabled,
                        onCheckedChange = onDirect,
                    )
                }
            }
        },
    )
}

/** The caption's word for what a source needs that DMD cannot do by itself. */
private val Rewrite.label: Int
    get() = when (this) {
        Rewrite.FLIPPED_ROWS -> R.string.dmd_blocker_flipped_rows
        Rewrite.REFERER -> R.string.dmd_blocker_referer
        Rewrite.SUBDOMAINS -> R.string.dmd_blocker_subdomains
        Rewrite.QUADKEY -> R.string.dmd_blocker_quadkey
        Rewrite.PADDED_ZOOM -> R.string.dmd_blocker_padded_zoom
        // Never a blocker, so never captioned; here so the mapping stays exhaustive.
        Rewrite.WMS_BBOX -> R.string.rewrite_wms_bbox
    }

/** The row's word for a rewrite: what goes in, what comes out. */
private val Rewrite.description: Int
    get() = when (this) {
        Rewrite.FLIPPED_ROWS -> R.string.rewrite_flipped_rows
        Rewrite.SUBDOMAINS -> R.string.rewrite_subdomains
        Rewrite.QUADKEY -> R.string.rewrite_quadkey
        Rewrite.PADDED_ZOOM -> R.string.rewrite_padded_zoom
        Rewrite.WMS_BBOX -> R.string.rewrite_wms_bbox
        Rewrite.REFERER -> R.string.rewrite_referer
    }

// ---------------------------------------------------------------- settings

/**
 * Version, updates and the live request log on one tab.
 *
 * The log's [LogSection] owns a `weight(1f)` LazyColumn, so the whole tab is a single
 * Column and the settings block above it is fixed height — not wrapped in a
 * `verticalScroll`, which cannot host a weighted child. In landscape the two sit side by
 * side comfortably; in portrait the top block is short enough that the log still gets most
 * of the height.
 */
@Composable
private fun ColumnScope.SettingsTab(
    viewModel: UpdateViewModel,
    context: Context,
) {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.installed_version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodyMedium,
        )

        HorizontalDivider()
        UpdateSection(viewModel)
    }

    HorizontalDivider()
    LogSection(context)
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

// ---------------------------------------------------------------- shared

/**
 * A full-width card holding a list entry: [info] stacked on the left, [controls] in a
 * line on the right.
 *
 * FlowRow rather than Row so the controls drop to their own line when they and the
 * info will not both fit — which is portrait on a phone, and any width on a long source
 * name. Content decides that, not a breakpoint guessed at from a screenshot.
 * SpaceBetween puts the controls at the far edge while they share the line, and
 * harmlessly left-aligns them once they have a line of their own.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WrappingRow(
    info: @Composable ColumnScope.() -> Unit,
    controls: @Composable RowScope.() -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Column(content = info)
            Row(verticalAlignment = Alignment.CenterVertically, content = controls)
        }
    }
}

/** A switch with its label after it. */
@Composable
private fun LabelledSwitch(label: Int, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Switch(checked = checked, onCheckedChange = onCheckedChange)
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ErrorText(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}
