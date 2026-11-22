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
) : UpdateSource {
    override suspend fun check(currentVersion: String): ClientUpdate? {
        try {
            return site.check(currentVersion)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return github.check(currentVersion)
        }
    }

    override suspend fun download(update: ClientUpdate, destination: File, progress: (Long, Long) -> Unit): String =
        if (UpdatePolicy.siteUrl(update.apk.url)) site.download(update, destination, progress)
        else github.download(update, destination, progress)
}