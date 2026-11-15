package com.moontvplus.phone.update

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class UpdatePolicyTest {
    private fun release(version: String, name: String = "moontvplus-phone-$version-release-signed.apk") = GithubRelease(
        tag = "client-v$version", assets = listOf(ReleaseAsset(name,
            "https://github.com/Happier-X/MoonTVPlus/releases/download/client-v$version/$name", 1024)),
    )

    @Test fun numericComparisonAndMalformedVersions() {
        assertTrue(ClientVersion.parse("1.10.0")!! > ClientVersion.parse("1.9.99")!!)
        assertTrue(ClientVersion.parse("2.0.0")!! > ClientVersion.parse("1.99.99")!!)
        listOf("1.9", "1.9.5-beta", "v1.9.5", "1.09.5", "999999999999999999999999.1.1").forEach {
            assertNull(ClientVersion.parse(it))
        }
    }

    @Test fun mixedWebsiteDesktopDebugDraftAndPrereleaseAreNotAndroidUpdates() {
        val stable = release("1.9.5")
        val choices = listOf(
            GithubRelease("v302.999", assets = stable.assets),
            release("1.9.9").copy(draft = true), release("1.9.8").copy(prerelease = true),
            release("1.10.0", "moontvplus-phone-1.10.0-debug.apk"),
            release("9.0.0", "MoonTVPlus-windows.jar"), stable,
            release("1.9.7").copy(assets = listOf(stable.assets.first().copy(url = "https://evil.test/update.apk"))),
        )
        assertEquals("1.9.5", UpdatePolicy.select(choices, "1.9.4")?.version)
    }

    @Test fun picksReleaseWithApplicableApkWhenNewestIsWindowsOnly() {
        val choices = listOf(release("1.10.0", "moontvplus-desktop-1.10.0-windows.jar"), release("1.9.6"))
        assertEquals("1.9.6", UpdatePolicy.select(choices, "1.9.4")?.version)
    }

    @Test fun selectsHighestAcrossUnorderedPagesAndIgnoresEqualAndOlder() {
        assertEquals("1.10.0", UpdatePolicy.select(listOf(release("1.9.9"), release("1.10.0"), release("1.9.5")), "1.9.4")?.version)
        assertNull(UpdatePolicy.select(listOf(release("1.9.3"), release("1.9.4")), "1.9.4"))
        assertNull(UpdatePolicy.select(listOf(release("1.9.5", "windows.jar")), "1.9.4"))
    }

    @Test fun rejectsUntrustedDownloadAndRedirectAddresses() {
        listOf("http://github.com/Happier-X/MoonTVPlus/releases/download/client-v1.9.5/a.apk",
            "https://github.com.evil.test/Happier-X/MoonTVPlus/releases/download/x/a.apk",
            "https://github.com/Other/Repo/releases/download/x/a.apk",
            "https://github.com:8443/Happier-X/MoonTVPlus/releases/download/x/a.apk").forEach {
            assertFalse(UpdatePolicy.releaseUrl(it))
        }
        assertTrue(UpdatePolicy.redirectUrl("https://release-assets.githubusercontent.com/path?signature=example"))
        assertFalse(UpdatePolicy.redirectUrl("http://release-assets.githubusercontent.com/file"))
        assertFalse(UpdatePolicy.redirectUrl("https://evil.test/file"))
    }

    @Test fun checksumIsOptionalButPublishedInformationMustMatch() {
        val update = UpdatePolicy.select(listOf(release("1.9.5")), "1.9.4")!!
        val hash = "a".repeat(64)
        assertNull(UpdatePolicy.checksum(update, null))
        assertEquals(hash, UpdatePolicy.checksum(update, "$hash  ${update.apk.name}\n"))
        val withDigest = update.copy(apk = update.apk.copy(digest = "sha256:$hash"))
        assertEquals(hash, UpdatePolicy.checksum(withDigest, null))
        assertThrows(IOException::class.java) { UpdatePolicy.checksum(withDigest, "${"b".repeat(64)} *${update.apk.name}") }
        assertThrows(IOException::class.java) { UpdatePolicy.checksum(update, "$hash  other.apk") }
        assertThrows(IOException::class.java) { UpdatePolicy.checksum(update, "$hash  ${update.apk.name}\n$hash  ${update.apk.name}") }
    }

    @Test fun rejectsWrongPackageVersionCodeVersionNameAndSigner() {
        fun verify(pkg: String = "com.moontvplus.phone", code: Long = 15, version: String = "1.9.5", signer: Boolean = true) =
            UpdatePolicy.validateIdentity(pkg, "com.moontvplus.phone", code, 14, version, "1.9.5", signer)
        verify()
        assertThrows(IOException::class.java) { verify(pkg = "com.other") }
        assertThrows(IOException::class.java) { verify(code = 14) }
        assertThrows(IOException::class.java) { verify(code = 13) }
        assertThrows(IOException::class.java) { verify(version = "1.9.4") }
        assertThrows(IOException::class.java) { verify(signer = false) }
    }
}
