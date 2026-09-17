package de.codevoid.wmsproxy

import android.app.Application
import de.codevoid.wmsproxy.update.UpdateChecker
import kotlin.concurrent.thread

class WmsProxyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Once this build is running, the APK it was installed from is dead weight.
        // Off the main thread because it touches a possibly cold cache directory.
        thread(name = "delete-installed-update") { UpdateChecker(this).deleteInstalledUpdate() }
    }
}
