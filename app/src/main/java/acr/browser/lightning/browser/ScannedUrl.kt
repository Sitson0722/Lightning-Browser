package acr.browser.lightning.browser

import androidx.core.net.toUri

/** Only ordinary web URLs may be opened automatically from QR content. */
internal fun scannedUrl(content: String): String? {
    val value = content.trim()
    if (value.isEmpty() || value.any { it.isWhitespace() || it.isISOControl() }) return null
    val uri = value.toUri().normalizeScheme()
    return if ((uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank()) {
        uri.toString()
    } else {
        null
    }
}
