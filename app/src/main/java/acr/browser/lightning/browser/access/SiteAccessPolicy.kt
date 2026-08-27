package acr.browser.lightning.browser.access

import android.app.Application
import android.net.Uri
import java.time.Clock
import java.time.LocalTime
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Restricts top-level browsing to saved domains outside the daily editing window.
 *
 * The editing window is fixed to UTC+8 and does not follow the device time zone.
 */
@Singleton
class SiteAccessPolicy @Inject constructor(
    application: Application,
) {
    private val preferences = application.getSharedPreferences(PREFERENCES_NAME, 0)

    fun isEditingWindowOpen(clock: Clock = Clock.systemUTC()): Boolean {
        val time = LocalTime.now(clock.withZone(UTC_PLUS_EIGHT))
        return !time.isBefore(EDITING_WINDOW_START) && time.isBefore(EDITING_WINDOW_END)
    }

    fun isUrlAllowed(url: String, clock: Clock = Clock.systemUTC()): Boolean {
        val host = normalizedHost(url) ?: return true
        if (isEditingWindowOpen(clock)) return true

        return allowedDomains().any { allowedDomain ->
            host == allowedDomain || host.endsWith(".$allowedDomain")
        }
    }

    /** Saves the HTTP(S) domain when the editing window is open. */
    fun allowUrl(url: String, clock: Clock = Clock.systemUTC()): AddResult {
        if (!isEditingWindowOpen(clock)) return AddResult.WindowClosed
        val host = normalizedHost(url) ?: return AddResult.InvalidUrl
        val updatedDomains = allowedDomains() + host.removePrefix("www.")
        preferences.edit().putStringSet(ALLOWED_DOMAINS, updatedDomains).apply()
        return AddResult.Added(host.removePrefix("www."))
    }

    fun blockedPageHtml(url: String): String {
        val host = normalizedHost(url).orEmpty().escapeHtml()
        return """
            <!doctype html>
            <html>
              <head>
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <style>
                  body { font-family: sans-serif; margin: 3rem 1.5rem; color: #202124; }
                  h1 { font-size: 1.5rem; }
                  p { line-height: 1.5; }
                </style>
              </head>
              <body>
                <h1>Site blocked</h1>
                <p><strong>$host</strong> is not on your allowed-sites list.</p>
                <p>You can browse freely and add sites from 14:00 to 15:00 (UTC+8). Outside that hour, only saved sites can be opened.</p>
              </body>
            </html>
        """.trimIndent()
    }

    private fun allowedDomains(): Set<String> =
        preferences.getStringSet(ALLOWED_DOMAINS, emptySet())?.toSet().orEmpty()

    private fun normalizedHost(url: String): String? {
        val uri = Uri.parse(url)
        if (uri.scheme != "http" && uri.scheme != "https") return null
        return uri.host?.lowercase()?.trimEnd('.')?.takeIf(String::isNotBlank)
    }

    sealed interface AddResult {
        data class Added(val domain: String) : AddResult
        data object WindowClosed : AddResult
        data object InvalidUrl : AddResult
    }

    private fun String.escapeHtml(): String =
        replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private companion object {
        const val PREFERENCES_NAME = "site_access_policy"
        const val ALLOWED_DOMAINS = "allowed_domains"
        val UTC_PLUS_EIGHT: ZoneOffset = ZoneOffset.ofHours(8)
        val EDITING_WINDOW_START: LocalTime = LocalTime.of(14, 0)
        val EDITING_WINDOW_END: LocalTime = LocalTime.of(15, 0)
    }
}
