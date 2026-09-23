package de.codevoid.wmsproxy.core.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The APK asset of a published GitHub release. */
data class ReleaseInfo(
    val versionName: String,
    val apkUrl: String,
    val apkName: String,
)

@Serializable
private data class GithubRelease(val assets: List<GithubAsset> = emptyList())

@Serializable
private data class GithubAsset(
    val name: String = "",
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
)

/**
 * Pure half of the update check: parsing a GitHub release and deciding whether the
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
     * The debug variant appends this to versionName. The published asset never carries
     * it, so it is stripped before comparing — otherwise a debug build would see every
     * check as an update and offer the same phantom upgrade forever.
     */
    private const val DEBUG_SUFFIX = "-debug"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Extracts the APK asset from a GitHub release payload. The version is taken from
     * the asset filename rather than any JSON field: GitHub carries no version for an
     * asset, and CI names the file to match the build's versionName exactly, so the
     * filename is the only place the two sides can agree.
     *
     * Returns null when the payload has no APK asset at all.
     */
    fun parseRelease(payload: String): ReleaseInfo? {
        val release = runCatching { json.decodeFromString<GithubRelease>(payload) }.getOrNull()
            ?: return null

        val asset = release.assets.firstOrNull { it.name.endsWith(APK_SUFFIX) } ?: return null

        return ReleaseInfo(
            versionName = asset.name.removeSuffix(APK_SUFFIX).removePrefix(APK_PREFIX),
            apkUrl = asset.browserDownloadUrl,
            apkName = asset.name,
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
     * Deliberately "different", not "newer". Every build is published under a single
     * rolling `dev` pre-release as `dev-<short sha>`, and a git SHA carries no ordering,
     * so there is nothing to compare greater-than against. Different is also the
     * behaviour wanted here: whatever main last published is what should be installed,
     * including after a revert.
     */
    fun isDifferentBuild(remote: ReleaseInfo, installedVersionName: String): Boolean =
        remote.versionName.isNotEmpty() &&
            remote.versionName != normalizeInstalledVersion(installedVersionName)
}
