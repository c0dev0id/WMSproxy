package de.codevoid.wmsproxy.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseParserTest {

    @Test
    fun `picks the apk asset and derives the version from its filename`() {
        val payload = """
            {"tag_name":"dev","assets":[
              {"name":"notes.txt","browser_download_url":"https://x/notes.txt"},
              {"name":"wmsproxy-dev-abc1234.apk","browser_download_url":"https://x/app.apk"}
            ]}
        """.trimIndent()

        val info = ReleaseParser.parseRelease(payload)!!

        assertEquals("dev-abc1234", info.versionName)
        assertEquals("https://x/app.apk", info.apkUrl)
        assertEquals("wmsproxy-dev-abc1234.apk", info.apkName)
    }

    @Test
    fun `returns null when the release carries no apk`() {
        assertNull(ReleaseParser.parseRelease("""{"assets":[{"name":"readme.txt"}]}"""))
    }

    @Test
    fun `returns null when there is no assets array`() {
        assertNull(ReleaseParser.parseRelease("""{"tag_name":"dev"}"""))
    }

    /** A truncated or non-JSON body must not crash the check. */
    @Test
    fun `returns null on malformed payloads`() {
        assertNull(ReleaseParser.parseRelease(""))
        assertNull(ReleaseParser.parseRelease("not json"))
        assertNull(ReleaseParser.parseRelease("""{"assets":["""))
    }

    /** GitHub sends many fields the parser does not model; none may break decoding. */
    @Test
    fun `ignores unknown fields`() {
        val payload = """
            {"id":1,"draft":false,"prerelease":true,"published_at":"2026-09-17T00:00:00Z",
             "assets":[{"id":9,"size":1234,"name":"wmsproxy-dev-abc1234.apk",
                        "browser_download_url":"https://x/app.apk","content_type":"application/vnd.android.package-archive"}]}
        """.trimIndent()

        assertEquals("dev-abc1234", ReleaseParser.parseRelease(payload)!!.versionName)
    }

    @Test
    fun `a differing published build counts as an update`() {
        val remote = ReleaseInfo("dev-new", "url", "wmsproxy-dev-new.apk")

        assertTrue(ReleaseParser.isDifferentBuild(remote, "dev-old"))
        assertFalse(ReleaseParser.isDifferentBuild(remote, "dev-new"))
    }

    /** Guards a malformed asset name; an empty remote version must never look installable. */
    @Test
    fun `an empty remote version is never offered`() {
        assertFalse(ReleaseParser.isDifferentBuild(ReleaseInfo("", "url", "name"), "dev-old"))
    }

    /**
     * The debug variant appends -debug to versionName while the published asset does
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
