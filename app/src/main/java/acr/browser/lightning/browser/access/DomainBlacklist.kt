package acr.browser.lightning.browser.access

import java.io.Reader
import java.net.IDN
import java.util.Locale

/** Exact domain matching, including descendants separated by a dot. */
class DomainBlacklist private constructor(private val domains: Set<String>) {
    fun blocksHost(host: String): Boolean {
        var domain = runCatching { normalizeDomain(host) }.getOrNull() ?: return false
        while (true) {
            if (domain in domains) return true
            val separator = domain.indexOf('.')
            if (separator < 0) return false
            domain = domain.substring(separator + 1)
        }
    }

    companion object {
        fun parse(reader: Reader): DomainBlacklist {
            val domains = mutableSetOf<String>()
            reader.buffered().lineSequence().forEachIndexed { index, line ->
                val value = line.removePrefix("\uFEFF").substringBefore('#').trim()
                if (value.isNotEmpty()) {
                    try {
                        domains += normalizeDomain(value)
                    } catch (exception: IllegalArgumentException) {
                        throw IllegalArgumentException(
                            "blacklist.txt:${index + 1}: invalid domain '$value'", exception
                        )
                    }
                }
            }
            return DomainBlacklist(domains.toSet())
        }

        private fun normalizeDomain(value: String): String {
            require(value.isNotBlank() && value == value.trim())
            require(value.none { it == '/' || it == ':' || it.isWhitespace() })
            val domain = IDN.toASCII(value.trimEnd('.'), IDN.USE_STD3_ASCII_RULES)
                .lowercase(Locale.ROOT)
            require(domain.length <= 253 && '.' in domain)
            require(domain.split('.').all { label ->
                label.isNotEmpty() && label.length <= 63 &&
                    label.first() != '-' && label.last() != '-'
            })
            return domain
        }
    }
}
