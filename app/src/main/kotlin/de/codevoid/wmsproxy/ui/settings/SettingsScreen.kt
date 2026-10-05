package de.codevoid.wmsproxy.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.codevoid.wmsproxy.BuildConfig
import de.codevoid.wmsproxy.R
import de.codevoid.wmsproxy.dmd.DmdStatus
import de.codevoid.wmsproxy.dmd.DmdViewModel
import de.codevoid.wmsproxy.proxy.ProxyService
import de.codevoid.wmsproxy.ui.ErrorText
import de.codevoid.wmsproxy.ui.LabelledSwitch
import de.codevoid.wmsproxy.ui.SectionTitle
import de.codevoid.wmsproxy.ui.SignInForm
import de.codevoid.wmsproxy.ui.UrlPreview
import de.codevoid.wmsproxy.update.UpdateViewModel

/** The proxy switch, the DMD account, Full sync, updates, the build, and the request log. */
@Composable
internal fun SettingsScreen(dmd: DmdViewModel, updates: UpdateViewModel, onBack: () -> Unit) {
    val running by ProxyService.running.collectAsStateWithLifecycle()
    val session by dmd.session.collectAsStateWithLifecycle()
    val status by dmd.status.collectAsStateWithLifecycle()
    val fullSync by dmd.fullSync.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SectionTitle(stringResource(R.string.settings_proxy))
                LabelledSwitch(R.string.proxy_switch, running) {
                    if (it) ProxyService.start(context) else ProxyService.stop(context)
                }
                Text(
                    text = stringResource(if (running) R.string.proxy_running else R.string.proxy_stopped),
                    style = MaterialTheme.typography.bodySmall,
                )
                UrlPreview(stringResource(R.string.proxy_https), ProxyService.server.secureBaseUrl)
                UrlPreview(stringResource(R.string.proxy_http), ProxyService.server.baseUrl)

                HorizontalDivider()
                SectionTitle(stringResource(R.string.settings_dmd))
                val current = session
                if (current == null) {
                    SignInForm(status, dmd::login)
                } else {
                    Text(stringResource(R.string.dmd_signed_in, current.name), style = MaterialTheme.typography.bodyMedium)
                    when (val now = status) {
                        is DmdStatus.Error -> ErrorText(now.message)
                        DmdStatus.Busy -> Text(stringResource(R.string.dmd_checking), style = MaterialTheme.typography.bodySmall)
                        DmdStatus.Idle -> Unit
                    }
                    TextButton(onClick = dmd::logout) { Text(stringResource(R.string.dmd_sign_out)) }
                }
                LabelledSwitch(R.string.dmd_full_sync, fullSync, dmd::setFullSync)

                HorizontalDivider()
                SectionTitle(stringResource(R.string.settings_updates))
                UpdateSection(updates)
                Text(
                    text = stringResource(R.string.installed_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodySmall,
                )

                HorizontalDivider()
                SectionTitle(stringResource(R.string.settings_log))
            }
            LogSection(context, dmd)
        }
    }
}
