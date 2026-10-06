package acr.browser.lightning.browser.access

import android.app.Application
import android.net.Uri
import javax.inject.Inject
import javax.inject.Singleton

/** A fixed blacklist loaded completely before any tab can start browsing. */
@Singleton
class SiteAccessPolicy internal constructor(private val blacklist: DomainBlacklist) {
    @Inject
    constructor(application: Application) : this(
        application.assets.open("blacklist.txt")
            .bufferedReader(Charsets.UTF_8).use { DomainBlacklist.parse(it) }
    )

    fun isUrlAllowed(url: String): Boolean {
        val uri = Uri.parse(url)
        if (!uri.scheme.equals("http", ignoreCase = true) &&
            !uri.scheme.equals("https", ignoreCase = true)
        ) return true
        return !blacklist.blocksHost(uri.host.orEmpty())
    }

    fun blockedPageHtml(url: String): String {
        val host = Uri.parse(url).host.orEmpty().escapeHtml()
        return """
            <!doctype html>
            <html>
              <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <style>
                  body { font-family: sans-serif; margin: 3rem 1.5rem; color: #202124; }
                  h1 { font-size: 1.5rem; }
                  p { line-height: 1.5; }
                </style>
              </head>
              <body>
                <h1>该网站已被封锁 / Site blocked</h1>
                <p><strong>$host</strong></p>
                <p>此网站在内置黑名单中。 / This site is on the built-in blacklist.</p>
              </body>
            </html>
        """.trimIndent()
    }

    private fun String.escapeHtml(): String =
        replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
