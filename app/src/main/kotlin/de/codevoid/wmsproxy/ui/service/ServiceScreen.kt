package de.codevoid.wmsproxy.ui.service

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.codevoid.wmsproxy.R
import de.codevoid.wmsproxy.core.LayerRow
import de.codevoid.wmsproxy.core.Origin
import de.codevoid.wmsproxy.core.ServiceKind
import de.codevoid.wmsproxy.core.SkippedLayer
import de.codevoid.wmsproxy.proxy.ProxyService
import de.codevoid.wmsproxy.ui.ErrorText
import de.codevoid.wmsproxy.ui.Field
import de.codevoid.wmsproxy.ui.UrlPreview
import de.codevoid.wmsproxy.ui.label
import kotlinx.coroutines.launch

/**
 * One service: what it is, the address it was read from, and its layers to tick. A
 * ticked layer is stored at once and measured in the background; a row says where it
 * goes, direct or through the proxy and why, and opens to its addresses on tap.
 */
@Composable
internal fun ServiceScreen(
    viewModel: ServiceViewModel,
    onBack: () -> Unit,
    onPreview: (LayerRow) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    // Kept here rather than read back from the state flow, which is assembled off the
    // main thread: a text field fed a value that lags its own keystrokes loses them.
    var layerQuery by rememberSaveable { mutableStateOf("") }
    val item = state.item

    LaunchedEffect(Unit) { viewModel.ensureFetched() }
    LaunchedEffect(layerQuery) { viewModel.setLayerQuery(layerQuery) }
    LaunchedEffect(state.notice) {
        val notice = state.notice ?: return@LaunchedEffect
        viewModel.clearNotice()
        scope.launch { snackbar.showSnackbar(notice) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(item?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (item != null) {
                        IconButton(onClick = viewModel::toggleFavorite) {
                            Icon(
                                imageVector = if (item.favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                contentDescription = stringResource(R.string.filter_favorites),
                            )
                        }
                        if (item.origin != Origin.LOCAL) {
                            IconButton(onClick = viewModel::rescan, enabled = state.status !is ServiceStatus.Fetching) {
                                Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.rescan))
                            }
                        }
                        Box {
                            IconButton(onClick = { menu = true }) {
                                Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.more))
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.unload_all)) },
                                    onClick = { menu = false; viewModel.unloadAll() },
                                    enabled = item.isLoaded,
                                )
                                if (item.origin == Origin.OWN) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.remove_service)) },
                                        onClick = { menu = false; viewModel.removeService(); onBack() },
                                    )
                                }
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "header") {
                Header(state, onRetry = viewModel::retry, onPreview = onPreview)
            }
            if (state.proxied.isNotEmpty()) {
                item(key = "proxy") { ProxyNotice(state.proxied, state.proxyOff) { ProxyService.start(context) } }
            }
            if (state.totalRows > SEARCH_THRESHOLD) {
                item(key = "search") {
                    Field(layerQuery, { layerQuery = it }, R.string.layer_search)
                }
            }
            if (state.totalRows > 1 && state.rows.isNotEmpty()) {
                item(key = "all") {
                    AllShownRow(
                        shown = state.rows.size,
                        loaded = state.visibleLoaded,
                        filtered = layerQuery.isNotBlank(),
                        onToggle = viewModel::setAllVisibleLoaded,
                    )
                }
            }
            // A document may name two layers alike, so the key carries the position too.
            itemsIndexed(state.rows, key = { index, row -> "row:$index:${row.id}" }) { _, row ->
                LayerRowItem(
                    row = row,
                    measuring = row.candidate.path in state.measuring,
                    expanded = expanded == row.id,
                    onToggle = { viewModel.setLoaded(row, it) },
                    onExpand = { expanded = if (expanded == row.id) null else row.id },
                    onPreview = { onPreview(row) },
                )
            }
            if (state.skipped.isNotEmpty()) {
                item(key = "skipped") { SkippedSection(state.skipped) }
            }
        }
    }
}

private const val SEARCH_THRESHOLD = 8

@Composable
private fun Header(state: ServiceUiState, onRetry: () -> Unit, onPreview: (LayerRow) -> Unit) {
    val item = state.item ?: return
    val cached = state.cached
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val meta = listOf(item.region, item.category, cached?.service?.name.orEmpty())
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(meta, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (item.note.isNotBlank()) Text(item.note, style = MaterialTheme.typography.bodyMedium)
            item.url?.let { url ->
                val label = when (cached?.service) {
                    ServiceKind.ARCGIS -> R.string.url_description
                    ServiceKind.XYZ -> R.string.url_template
                    else -> R.string.url_capabilities
                }
                UrlPreview(stringResource(label), cached?.fetchedFrom ?: url)
            }
            when (val status = state.status) {
                ServiceStatus.Fetching -> Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.reading), style = MaterialTheme.typography.bodySmall)
                }
                is ServiceStatus.Failed -> Column {
                    ErrorText(status.message)
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
                }
                ServiceStatus.Idle -> when {
                    cached != null && cached.fetchedAt > 0 -> Text(
                        text = stringResource(
                            R.string.read_at,
                            DateUtils.getRelativeTimeSpanString(cached.fetchedAt).toString(),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    item.url != null && cached == null -> Text(
                        text = stringResource(R.string.not_read),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    else -> Unit
                }
            }
            // A template has one layer and no list worth a row of its own controls, so
            // its preview sits here.
            if (cached?.service == ServiceKind.XYZ) {
                state.rows.firstOrNull()?.let { only ->
                    OutlinedButton(onClick = { onPreview(only) }) {
                        Icon(Icons.Outlined.Map, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.preview), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ProxyNotice(proxied: List<LayerRow>, proxyOff: Boolean, onStart: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (proxyOff) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.proxy_notice, proxied.size),
                style = MaterialTheme.typography.bodySmall,
            )
            val reasons = proxied.mapNotNull { it.blocker }.distinct().map { stringResource(it.label) }
            if (reasons.isNotEmpty()) {
                Text(text = reasons.joinToString(", "), style = MaterialTheme.typography.labelSmall)
            }
            if (proxyOff) {
                TextButton(onClick = onStart) { Text(stringResource(R.string.proxy_off_start)) }
            }
        }
    }
}

/**
 * Select all, scoped to what the search shows: off when none of the shown rows is
 * loaded, on when all are, in between otherwise. A tap loads everything shown unless
 * everything shown is loaded already, in which case it unloads it.
 */
@Composable
private fun AllShownRow(shown: Int, loaded: Int, filtered: Boolean, onToggle: (Boolean) -> Unit) {
    val state = when {
        loaded == 0 -> ToggleableState.Off
        loaded == shown -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        TriStateCheckbox(state = state, onClick = { onToggle(state != ToggleableState.On) })
        Text(
            text = if (filtered) pluralStringResource(R.plurals.layers_matching, shown, shown)
            else pluralStringResource(R.plurals.library_layers, shown, shown),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun LayerRowItem(
    row: LayerRow,
    measuring: Boolean,
    expanded: Boolean,
    onToggle: (Boolean) -> Unit,
    onExpand: () -> Unit,
    onPreview: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier
                    .clickable(onClick = onExpand)
                    .padding(end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = row.loaded, onCheckedChange = onToggle)
                Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
                    Text(row.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val caption = listOf(row.id, row.service?.name.orEmpty(), row.format)
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")
                    Text(
                        text = caption,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val zoom = when {
                            measuring -> stringResource(R.string.measuring)
                            else -> row.zoomLabel()?.let { stringResource(R.string.zoom_range, it) }
                        }
                        zoom?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                        val blocker = row.blocker
                        Text(
                            text = if (blocker == null) stringResource(R.string.direct_marker)
                            else stringResource(R.string.proxy_rewrites, stringResource(blocker.label)),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (blocker == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    if (row.stale) {
                        Text(
                            text = stringResource(R.string.stale_layer),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                IconButton(onClick = onPreview) {
                    Icon(Icons.Outlined.Map, contentDescription = stringResource(R.string.preview))
                }
            }
            if (expanded) {
                Column(
                    modifier = Modifier.padding(start = 48.dp, end = 12.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val direct = if (row.candidate.urlTemplate.contains("{bbox}")) R.string.url_getmap else R.string.url_tiles
                    UrlPreview(stringResource(direct), row.candidate.urlTemplate)
                    UrlPreview(stringResource(R.string.url_proxy), ProxyService.server.templateFor(row.candidate))
                }
            }
        }
    }
}

@Composable
private fun SkippedSection(skipped: List<SkippedLayer>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.import_skipped, skipped.size), style = MaterialTheme.typography.labelMedium)
            skipped.forEach {
                Text(
                    text = "${it.name}: ${it.reason}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
