package de.codevoid.wmsproxy

import android.app.Application
import de.codevoid.wmsproxy.dmd.DmdHub
import de.codevoid.wmsproxy.dmd.DmdSyncPrefs
import de.codevoid.wmsproxy.proxy.BlankTile
import de.codevoid.wmsproxy.proxy.Sources
import de.codevoid.wmsproxy.update.UpdateChecker
import kotlin.concurrent.thread

class WmsProxyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Before anything can serve or display a tile URL.
        Sources.init(this)
        BlankTile.init(this)
        // Restores a remembered DMD session so the tab opens signed in.
        DmdHub.init(this)
        DmdSyncPrefs.init(this)

        // Once this build is running, the APK it was installed from is dead weight.
        // Off the main thread because it touches a possibly cold cache directory.
        thread(name = "delete-installed-update") { UpdateChecker(this).deleteInstalledUpdate() }
    }
}
