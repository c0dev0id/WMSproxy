package de.codevoid.wmsproxy.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseParserTest {

    private val latest = "https://home.codevoid.de/dist/k3y/latest"

    /** The shape the dist store's latest address answers with. */
    private fun distFile(
        name: String = "wmsproxy-dev-abc1234.apk",
        version: String = "\"dev-abc1234\"",
    ) = """
        {"name":"$name","version":$version,"size":5242880,"sha256":"00ff",
         "uploaded":"2026-10-31T22:00:00Z","url":"/dist/k3y/$name"}
    """.trimIndent()

    @Test
    fun `takes the version and resolves the address against the latest url`() {
        val info = ReleaseParser.parseDistFile(distFile(), latest)!!

        assertEquals("dev-abc1234", info.versionName)
        assertEquals("https://home.codevoid.de/dist/k3y/wmsproxy-dev-abc1234.apk", info.apkUrl)
        assertEquals("wmsproxy-dev-abc1234.apk", info.apkName)
    }

    @Test
    fun `returns null for a file without a version`() {
        assertNull(ReleaseParser.parseDistFile(distFile(version = "null"), latest))
        assertNull(ReleaseParser.parseDistFile("""{"name":"wmsproxy-dev-a.apk","url":"/dist/k3y/a"}""", latest))
    }

    @Test
    fun `returns null for a file without an address`() {
        assertNull(ReleaseParser.parseDistFile("""{"name":"wmsproxy-dev-a.apk","version":"dev-a"}""", latest))
    }

    /** The name becomes the download's file name. */
    @Test
    fun `returns null for a name that is not a bare apk`() {
        assertNull(ReleaseParser.parseDistFile(distFile(name = "notes.txt"), latest))
        assertNull(ReleaseParser.parseDistFile(distFile(name = "../wmsproxy-dev-evil.apk"), latest))
    }

    /** A truncated or non-JSON body, or the store's error answer, must not crash the check. */
    @Test
    fun `returns null on malformed payloads`() {
        assertNull(ReleaseParser.parseDistFile("", latest))
        assertNull(ReleaseParser.parseDistFile("not json", latest))
        assertNull(ReleaseParser.parseDistFile("""{"name":""", latest))
        assertNull(ReleaseParser.parseDistFile("""{"error":"Not found"}""", latest))
    }

    @Test
    fun `a differing published build counts as an update`() {
        val remote = ReleaseInfo("dev-new", "url", "wmsproxy-dev-new.apk")

        assertTrue(ReleaseParser.isDifferentBuild(remote, "dev-old"))
        assertFalse(ReleaseParser.isDifferentBuild(remote, "dev-new"))
    }

    /** Guards a published file with an empty version; it must never look installable. */
    @Test
    fun `an empty remote version is never offered`() {
        assertFalse(ReleaseParser.isDifferentBuild(ReleaseInfo("", "url", "name"), "dev-old"))
    }

    /**
     * The debug variant appends -debug to versionName while the published build does
     * not. Without normalization every check on a debug build would report the build it
     * is already running as an available update.
     */
    @Test
    fun `a debug build does not see its own version as an update`() {
        val remote = ReleaseInfo("dev-abc1234", "url", "wmsproxy-dev-abc1234.apk")

        assertFalse(ReleaseParser.isDifferentBuild(remote, "dev-abc1234-debug"))
        assertTrue(ReleaseParser.isDifferentBuild(remote, "dev-older00-debug"))
    }

    @Test
    fun `normalizing leaves a release version untouched`() {
        assertEquals("dev-abc1234", ReleaseParser.normalizeInstalledVersion("dev-abc1234"))
        assertEquals("dev-abc1234", ReleaseParser.normalizeInstalledVersion("dev-abc1234-debug"))
    }
}
