package acr.browser.lightning.download

import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Resolves server filenames without letting a generic MIME type replace their extension. */
internal object DownloadFileName {
    private val parameter = Regex("""(?:^|;)\s*(filename\*?)\s*=\s*(?:"((?:\\.|[^"\\])*)"|([^;]*))""", RegexOption.IGNORE_CASE)
    private val escapedCharacter = Regex("""\\(.)""")

    fun resolve(url: String, contentDisposition: String?, fallbackExtension: String?): String {
        val parameters = parameter.findAll(contentDisposition.orEmpty()).associate { match ->
            match.groupValues[1].lowercase(java.util.Locale.ROOT) to
                (match.groups[2]?.value?.replace(escapedCharacter, "$1")
                    ?: match.groupValues[3].trim())
        }
        val extended = parameters["filename*"]?.let { value ->
            val parts = value.split('\'', limit = 3)
            if (parts.size == 3) decode(parts[2], parts[0]) else null
        }
        val plain = parameters["filename"]?.let { repairUtf8(decode(it, "UTF-8") ?: it) }
        val urlName = url.substringBefore('#').substringBefore('?').substringAfterLast('/')
            .let { decode(it, "UTF-8") ?: it }
        val name = sequenceOf(extended, plain, urlName).filterNotNull()
            .map(::sanitize).firstOrNull(String::isNotEmpty) ?: "download"
        val hasExtension = name.lastIndexOf('.').let { it > 0 && it < name.lastIndex }
        val extension = fallbackExtension?.trim('.')?.takeIf {
            it.isNotEmpty() && it.all(Char::isLetterOrDigit)
        }
        return if (!hasExtension && extension != null) "$name.$extension" else name
    }

    private fun decode(value: String, charset: String): String? = runCatching {
        // '+' is a literal in a filename/path, unlike form URL encoding. Decode only once.
        URLDecoder.decode(value.replace("+", "%2B"), Charset.forName(charset).name())
    }.getOrNull()

    private fun repairUtf8(value: String): String {
        if (value.none { it.code in 128..255 } || value.any { it.code > 255 }) return value
        return runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(value.toByteArray(Charsets.ISO_8859_1))).toString()
        }.getOrDefault(value)
    }

    private fun sanitize(value: String): String = value
        .replace(Regex("""[\p{Cntrl}/\\:*?"<>|]"""), "_")
        .trim().trim('.')
}
