package com.example.juke.services

import com.example.juke.models.GithubRelease
import com.example.juke.models.GithubReleaseAsset
import org.junit.Assert.*
import org.junit.Test

class ReleasePolicyTest {
    private fun release(tag: String, beta: Boolean = false, apk: Boolean = true) = GithubRelease(
        tagName = tag, htmlUrl = "https://github.com/example/release", isPrerelease = beta,
        assets = if (apk) listOf(GithubReleaseAsset("Music-Box.apk", "https://example/apk")) else emptyList())

    @Test fun betaCanUpgradeToStableWithoutUninstalling() {
        assertEquals("v2.4.0", selectUpdateRelease(listOf(release("v2.4.0")), "2.3.37-beta")?.tagName)
        assertTrue(isNewerVersion("2.4.0-beta", "v2.4.0"))
    }
    @Test fun stableSkipsNewerBetaAndFindsTheStableApk() {
        val releases = listOf(release("v2.5.0-beta", true), release("v2.4.1"))
        assertEquals("v2.4.1", selectUpdateRelease(releases, "2.4.0")?.tagName)
    }
    @Test fun stableNeverOffersDowngradeOrSameVersion() {
        assertNull(selectUpdateRelease(listOf(release("v2.3.0"), release("v2.4.0")), "2.4.0"))
    }
    @Test fun missingApkAndInvalidVersionAreIgnored() {
        assertNull(selectUpdateRelease(listOf(release("v2.5.0", apk = false), release("bad")), "2.4.0"))
    }
    @Test fun highestVersionWinsEvenIfReleasesArePublishedOutOfOrder() {
        assertEquals("v2.10.0", selectUpdateRelease(listOf(release("v2.5.0"), release("v2.10.0")), "2.4.0")?.tagName)
    }
    @Test fun prereleaseTagCannotSlipIntoStableChannelWithWrongFlag() {
        assertNull(selectUpdateRelease(listOf(release("v2.5.0-rc.1")), "2.4.0"))
    }
}
