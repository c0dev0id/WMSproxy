package de.codevoid.wmsproxy.proxy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Brings the proxy back after a reboot, if it was running when the phone went down.
 *
 * A receiver rather than a setting the user has to find: the proxy is a navigation
 * dependency, and a phone that reboots mid-ride should come back serving tiles without
 * anyone taking a glove off. It starts nothing the user did not already start — the flag
 * is set when they start the proxy and cleared only when they stop it.
 *
 * Starting a foreground service from the background is normally refused, and
 * `BOOT_COMPLETED` is one of the documented exemptions. Android 14 and 15 narrow that
 * exemption by service type, but the blocked list is `dataSync`, `camera`,
 * `mediaPlayback`, `phoneCall`, `mediaProjection` and `microphone` — `specialUse` is not
 * on it. The type was chosen to dodge Android 15's six-hour cap on `dataSync`; it turns
 * out to be the one that can also come back on boot.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // Application.onCreate has already run, so the stored config is loaded.
        if (Sources.config.value.startOnBoot) {
            ProxyService.start(context)
        }
    }
}
