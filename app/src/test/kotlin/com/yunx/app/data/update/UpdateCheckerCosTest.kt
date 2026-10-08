package com.yunx.app.data.update

import org.junit.Assert.*
import org.junit.Test

class UpdateCheckerCosTest {
    @Test fun comparesStableAndPrereleaseNaturally() {
        assertTrue(UpdateChecker.compareVersions("1.1.1", "1.1.1-rc.2") > 0)
        assertTrue(UpdateChecker.compareVersions("v1.1.1-beta.10", "1.1.1-beta.2") > 0)
        assertTrue(UpdateChecker.compareVersions("1.1.1-rc.1", "1.1.1-beta.9") > 0)
        assertEquals(0, UpdateChecker.compareVersions("v1.1.1", "1.1.1+build.2"))
        assertEquals(0, UpdateChecker.compareVersions("1.1.1-gh1", "1.1.1"))
    }
    @Test fun cosCodeIsAuthoritativeAndShareLinkIsNotAnAsset() {
        val r = UpdateChecker.parseCosVersion("""{"schemaVersion":1,"enabled":true,"versionName":"1.1.1","versionCode":1001012,"downloadUrl":"https://pan.quark.cn/s/test","changelog":"修复","publishedAt":"2026-10-08"}""", false)!!
        assertTrue(UpdateChecker.isNewer(r, "1.1.1", 1001011))
        assertFalse(UpdateChecker.isNewer(r, "1.1.1", 1001012))
        assertFalse(UpdateChecker.isNewer(r, "1.1.0", 1001013))
        assertEquals("https://pan.quark.cn/s/test", r.downloadPageUrl)
        assertTrue(r.assets.isEmpty())
    }
    @Test fun disabledPreReleaseAndInvalidCosFallBack() {
        assertNull(UpdateChecker.parseCosVersion("""{"schemaVersion":1,"enabled":false}""", false))
        val pre = """{"schemaVersion":1,"enabled":true,"versionName":"1.2.0-beta.1","versionCode":1002010,"downloadUrl":""}"""
        assertNull(UpdateChecker.parseCosVersion(pre, false))
        assertTrue(UpdateChecker.parseCosVersion(pre, true)!!.prerelease)
        assertFalse(UpdateChecker.hasDownload(UpdateChecker.parseCosVersion(pre, true)!!))
        assertThrows(IllegalArgumentException::class.java) { UpdateChecker.parseCosVersion("""{"schemaVersion":1,"enabled":true,"versionName":"1.2.0","versionCode":0}""", true) }
        assertNull(UpdateChecker.safeWebUrl("javascript:alert(1)"))
        assertNull(UpdateChecker.safeWebUrl("https://user:secret@example.com"))
        assertNull(UpdateChecker.safeWebUrl("null"))
    }
}
