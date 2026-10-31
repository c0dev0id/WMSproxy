package de.codevoid.wmsproxy.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import de.codevoid.wmsproxy.BuildConfig
import de.codevoid.wmsproxy.core.update.ReleaseInfo
import de.codevoid.wmsproxy.core.update.ReleaseParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads the APK published by the Build workflow so a device can be updated without
 * a cable. The project cannot be built locally, so this is the normal way a new build
 * reaches the device.
 *
 * All parsing and version comparison lives in :core; this class is only the parts that
 * genuinely need a Context — network, cache directory and the installer hand-off.
 *
 * No networking library: HttpURLConnection is enough for two requests, and the proxy's
 * own HTTP client has no business being on this path.
 */
class UpdateChecker(private val context: Context) {

    private val downloadDir = File(context.cacheDir, "updates")

    /** Returns the published release when it differs from the running build, else null. */
    suspend fun check(): ReleaseInfo? = withContext(Dispatchers.IO) {
        val release = ReleaseParser.parseDistFile(httpGet(DIST_LATEST), DIST_LATEST)
            ?: return@withContext null
        if (ReleaseParser.isDifferentBuild(release, BuildConfig.VERSION_NAME)) release else null
    }

    suspend fun download(
        release: ReleaseInfo,
        onProgress: ((bytesWritten: Long, totalBytes: Long) -> Unit)? = null,
    ): File = withContext(Dispatchers.IO) {
        val dir = downloadDir.apply { mkdirs() }
        val file = File(dir, release.apkName)
        // The directory holds at most the download in progress.
        dir.listFiles()?.filter { it != file }?.forEach { it.delete() }
        httpDownload(release.apkUrl, file, onProgress)
        file
    }

    /**
     * Deletes the APK of the build that is now running, i.e. the update that was just
     * installed. Only that file: a different version might still be open in the system
     * installer if this process was restarted to serve it through the FileProvider.
     */
    fun deleteInstalledUpdate() {
        val installed = ReleaseParser.normalizeInstalledVersion(BuildConfig.VERSION_NAME)
        File(downloadDir, "${ReleaseParser.APK_PREFIX}$installed.apk").delete()
    }

    fun installIntent(file: File): Intent {
        // Authority mirrors the manifest's ${applicationId}.fileprovider. Derived at
        // runtime rather than hardcoded so the .debug variant keeps its own authority
        // and the two builds can be installed side by side.
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun httpGet(urlString: String): String {
        val connection = open(urlString)
        try {
            connection.setRequestProperty("Accept", "application/json")
            // A dist repository nothing was pushed to yet answers 404 with an error body.
            // Reading it as a build would surface as a confusing "up to date", so the
            // status is checked and reported as itself.
            val status = connection.responseCode
            if (status !in 200..299) {
                throw IOException("The update server returned HTTP $status")
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun httpDownload(
        urlString: String,
        dest: File,
        onProgress: ((Long, Long) -> Unit)?,
    ) {
        val connection = open(urlString)
        try {
            val totalBytes = connection.contentLengthLong
            var written = 0L
            connection.inputStream.use { input ->
                FileOutputStream(dest).use { output ->
                    val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        output.write(buf, 0, n)
                        written += n
                        onProgress?.invoke(written, totalBytes)
                    }
                }
            }
            if (totalBytes > 0L && written != totalBytes) {
                dest.delete()
                throw IOException("Download incomplete: received $written of $totalBytes bytes")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(urlString: String): HttpURLConnection =
        (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
        }

    private companion object {
        const val DIST_LATEST = "https://home.codevoid.de/dist/ddxj4rid/latest"
        const val USER_AGENT = "WMSproxy"
        const val TIMEOUT_MS = 15_000
    }
}
