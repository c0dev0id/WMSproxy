package de.codevoid.wmsproxy.ui.catalog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AltRoute
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.codevoid.wmsproxy.R
import de.codevoid.wmsproxy.core.CatalogFilter
import de.codevoid.wmsproxy.core.ServiceItem
import de.codevoid.wmsproxy.dmd.DmdSyncState
import de.codevoid.wmsproxy.dmd.DmdViewModel
import de.codevoid.wmsproxy.proxy.ProxyService
import de.codevoid.wmsproxy.ui.AddServiceDialog
import de.codevoid.wmsproxy.ui.PickerMenu
import de.codevoid.wmsproxy.ui.SignInDialog
import kotlinx.coroutines.launch

/**
 * The one list: every service, narrowed by the chips and the search, each row saying
 * what is loaded of it. Sync at the top pushes every loaded layer to DMD, signing in
 * first when there is no session; the button at the bottom adds an address by hand.
 * [onOpen] gets the service key and the text to seed its layer search with: the search
 * typed here when it was a layer and not the service that matched, blank otherwise.
 */
@Composable
internal fun CatalogScreen(
    dmd: DmdViewModel,
    onOpen: (key: String, layerQuery: String) -> Unit,
    onSettings: () -> Unit,
    viewModel: CatalogViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val session by dmd.session.collectAsStateWithLifecycle()
    val syncState by dmd.sync.collectAsStateWithLifecycle()
    val dmdStatus by dmd.status.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var adding by rememberSaveable { mutableStateOf(false) }
    var signingIn by rememberSaveable { mutableStateOf(false) }
    // The search text lives here, not in the state flow: that flow is assembled off the
    // main thread, and a text field fed a value that lags its own keystrokes loses them.
    var query by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(query) { viewModel.setQuery(query) }

    // The sign-in asked for a sync; once a session exists the push is under way.
    LaunchedEffect(session) { if (session != null) signingIn = false }

    // Cleared before it is shown, so the outcome is shown once however the screen changes
    // meanwhile; the snackbar outlives the effect, which the clearing restarts.
    LaunchedEffect(syncState) {
        val message = when (val outcome = syncState) {
            is DmdSyncState.Done -> context.resources.getQuantityString(R.plurals.dmd_synced, outcome.count, outcome.count)
            is DmdSyncState.Failed -> context.getString(R.string.dmd_sync_failed, outcome.message)
            else -> return@LaunchedEffect
        }
        dmd.clearSyncResult()
        scope.launch { snackbar.showSnackbar(message) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    if (syncState is DmdSyncState.Syncing) {
                        Box(modifier = Modifier.padding(12.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    } else {
                        IconButton(onClick = { if (session == null) signingIn = true else dmd.syncNow() }) {
                            Icon(Icons.Outlined.Sync, contentDescription = stringResource(R.string.dmd_sync))
                        }
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.settings))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { adding = true }) {
                Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.add_service_title))
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            SearchField(query) { query = it }
            FilterRow(state, viewModel::setFilter) { query = ""; viewModel.clearFilters() }
            if (state.proxyWarning > 0) ProxyWarning(state.proxyWarning) { ProxyService.start(context) }
            CatalogList(state, onStar = viewModel::toggleFavorite) { item ->
                onOpen(item.key, if (item.matchedByName) "" else state.filter.query)
            }
        }
    }

    if (adding) {
        AddServiceDialog(
            onAdd = viewModel::addService,
            onOpened = { adding = false; onOpen(it, "") },
            onDismiss = { adding = false },
        )
    }
    if (signingIn) {
        SignInDialog(dmdStatus, onSignIn = dmd::loginAndSync, onDismiss = { signingIn = false })
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        placeholder = { Text(stringResource(R.string.library_search)) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.clear))
                }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/** Favorites, Loaded, a region and a category, in one scrolling line of chips. */
@Composable
private fun FilterRow(
    state: CatalogUiState,
    onFilter: ((CatalogFilter) -> CatalogFilter) -> Unit,
    onClear: () -> Unit,
) {
    val filter = state.filter
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = filter.favorites,
            onClick = { onFilter { it.copy(favorites = !it.favorites) } },
            label = { Text(stringResource(R.string.filter_favorites)) },
            leadingIcon = { Icon(Icons.Filled.Star, contentDescription = null, modifier = Modifier.size(18.dp)) },
        )
        FilterChip(
            selected = filter.loaded,
            onClick = { onFilter { it.copy(loaded = !it.loaded) } },
            label = { Text(stringResource(R.string.filter_loaded)) },
            leadingIcon = { Icon(Icons.Outlined.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp)) },
        )
        PickerMenu(
            items = listOf(stringResource(R.string.library_region_all) to { onFilter { it.copy(region = null) } }) +
                state.regions.map { region -> region to { onFilter { it.copy(region = region) } } },
        ) { open ->
            FilterChip(
                selected = filter.region != null,
                onClick = open,
                label = { Text(filter.region ?: stringResource(R.string.filter_region)) },
                trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, contentDescription = null) },
            )
        }
        PickerMenu(
            items = listOf(stringResource(R.string.library_category_all) to { onFilter { it.copy(category = null) } }) +
                state.categories.map { category -> category to { onFilter { it.copy(category = category) } } },
        ) { open ->
            FilterChip(
                selected = filter.category != null,
                onClick = open,
                label = { Text(filter.category ?: stringResource(R.string.filter_category)) },
                trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, contentDescription = null) },
            )
        }
        if (filter.active) {
            TextButton(onClick = onClear) { Text(stringResource(R.string.clear)) }
        }
    }
}

@Composable
private fun ProxyWarning(count: Int, onStart: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = pluralStringResource(R.plurals.proxy_warning, count, count),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onStart) { Text(stringResource(R.string.start_service)) }
        }
    }
}

@Composable
private fun CatalogList(state: CatalogUiState, onStar: (String) -> Unit, onOpen: (ServiceItem) -> Unit) {
    if (state.groups.isEmpty()) {
        Text(
            text = stringResource(if (state.total == 0) R.string.library_empty else R.string.nothing_matches),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 88.dp),
    ) {
        state.groups.forEach { (region, entries) ->
            item(key = "region:$region") {
                Text(
                    text = region,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                )
            }
            items(entries, key = { it.key }) { item -> ServiceRow(item, onStar, onOpen) }
        }
        if (state.verified.isNotBlank()) {
            item(key = "verified") {
                Text(
                    text = stringResource(R.string.library_checked, state.verified),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun ServiceRow(item: ServiceItem, onStar: (String) -> Unit, onOpen: (ServiceItem) -> Unit) {
    ListItem(
        modifier = Modifier.clickable { onOpen(item) },
        leadingContent = {
            IconButton(onClick = { onStar(item.key) }) {
                Icon(
                    imageVector = if (item.favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = stringResource(R.string.filter_favorites),
                    tint = if (item.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        headlineContent = { Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (item.note.isNotBlank()) {
                    Text(item.note, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                StatusLine(item)
                if (item.matchingLayers > 0) {
                    Text(
                        text = pluralStringResource(R.plurals.layers_matching, item.matchingLayers, item.matchingLayers),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        trailingContent = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null) },
    )
}

/** Loaded of offered as a pill when something is loaded; the offered count alone before that. */
@Composable
private fun StatusLine(item: ServiceItem) {
    val available = item.available
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        when {
            item.isLoaded -> Surface(
                shape = MaterialTheme.shapes.extraSmall,
                color = MaterialTheme.colorScheme.tertiaryContainer,
            ) {
                Text(
                    text = "${item.loaded}/${available ?: item.loaded}",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            available != null -> Text(
                text = pluralStringResource(R.plurals.library_layers, available, available),
                style = MaterialTheme.typography.labelSmall,
            )
            else -> Unit
        }
        if (item.needsProxy) {
            Icon(Icons.Outlined.AltRoute, contentDescription = null, modifier = Modifier.size(14.dp))
            Text(stringResource(R.string.via_proxy), style = MaterialTheme.typography.labelSmall)
        }
    }
}
