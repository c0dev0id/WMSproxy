package de.codevoid.wmsproxy.core.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URL

/** A build published in the dist store. */
data class ReleaseInfo(
    val versionName: String,
    val apkUrl: String,
    val apkName: String,
)

@Serializable
private data class DistFile(
    val name: String = "",
    val version: String? = null,
    val url: String? = null,
)

/**
 * Pure half of the update check: parsing the dist store's answer and deciding whether the
 * published build differs from the installed one.
 *
 * It lives in :core, away from Context and FileProvider, so it is testable with plain
 * JUnit. Parsing uses kotlinx-serialization rather than org.json deliberately —
 * org.json is an Android platform class stubbed out on the JVM, which would drag
 * Robolectric in and push these tests into :app.
 */
object ReleaseParser {

    /** The APK filename convention CI produces: `wmsproxy-<versionName>.apk`. */
    const val APK_PREFIX: String = "wmsproxy-"

    private const val APK_SUFFIX = ".apk"

    /**
     * The debug variant appends this to versionName. The published build never carries
     * it, so it is stripped before comparing — otherwise a debug build would see every
     * check as an update and offer the same phantom upgrade forever.
     */
    private const val DEBUG_SUFFIX = "-debug"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Reads the file a dist store `latest` answer describes, whose `url` is relative to
     * [latestUrl]. Null for a malformed answer, a file without a version or address, or
     * one whose name is not a bare APK file name, since the name becomes the download's
     * file name.
     */
    fun parseDistFile(payload: String, latestUrl: String): ReleaseInfo? {
        val file = runCatching { json.decodeFromString<DistFile>(payload) }.getOrNull()
            ?: return null
        val version = file.version ?: return null
        val url = file.url ?: return null
        if (!file.name.endsWith(APK_SUFFIX) || '/' in file.name) return null

        return ReleaseInfo(
            versionName = version,
            apkUrl = URL(URL(latestUrl), url).toString(),
            apkName = file.name,
        )
    }

    /**
     * Strips the debug suffix so a debug build compares against the same version string
     * a release build would.
     */
    fun normalizeInstalledVersion(versionName: String): String =
        versionName.removeSuffix(DEBUG_SUFFIX)

    /**
     * Whether [remote] is a different build from the one installed.
     *
     * Deliberately "different", not "newer". Every build of `main` is published as
     * `dev-<short sha>`, and a git SHA carries no ordering, so there is nothing to compare
     * greater-than against. Different is also the behaviour wanted here: whatever main
     * last published is what should be installed, including after a revert.
     */
    fun isDifferentBuild(remote: ReleaseInfo, installedVersionName: String): Boolean =
        remote.versionName.isNotEmpty() &&
            remote.versionName != normalizeInstalledVersion(installedVersionName)
}
