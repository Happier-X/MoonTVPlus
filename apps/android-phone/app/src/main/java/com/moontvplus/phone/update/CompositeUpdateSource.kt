package com.moontvplus.phone.update

import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * Prefer the site-hosted update source, then fall back to GitHub for users who can reach it.
 * A successful "no update" answer from the site is authoritative; only transport/format failures
 * fall through to GitHub.
 */
class CompositeUpdateSource(
    private val site: UpdateSource = SiteUpdateSource(),
    private val github: UpdateSource = GithubUpdateSource(),
    private val downloader: MirrorDownloader = MirrorDownloader(),
) : UpdateSource {
    override suspend fun check(currentVersion: String): ClientUpdate? {
        try {
            return site.check(currentVersion)?.withMirrors()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return github.check(currentVersion)?.withMirrors()
        }
    }

    override suspend fun download(update: ClientUpdate, destination: File, progress: (Long, Long) -> Unit): String =
        downloader.download(update, destination, progress)
}

/**
 * Add the other line as a mirror. The site mirror is always the moving latest APK, so a
 * stale site release is detected by size/hash during download and the GitHub mirror wins.
 */
private fun ClientUpdate.withMirrors(): ClientUpdate {
    val githubUrl = "https://github.com/" + UpdatePolicy.REPOSITORY +
        "/releases/download/client-v" + version +
        "/moontvplus-phone-" + version + "-release-signed.apk"
    val githubAsset = ReleaseAsset(
        name = "moontvplus-phone-" + version + "-release-signed.apk",
        url = githubUrl,
        size = apk.size,
        digest = apk.digest,
    )
    val siteAsset = ReleaseAsset(
        name = "moontvplus-phone-latest.apk",
        url = UpdatePolicy.SITE_APK_URL,
        size = apk.size,
        digest = apk.digest,
    )
    val extra = buildList {
        addAll(mirrors)
        if (!UpdatePolicy.siteUrl(apk.url)) add(siteAsset)
        if (!UpdatePolicy.releaseUrl(apk.url) && UpdatePolicy.releaseUrl(githubUrl)) add(githubAsset)
    }.filter { it.url != apk.url && UpdatePolicy.downloadUrl(it.url) }.distinctBy { it.url }
    return copy(mirrors = extra)
}