package acr.browser.lightning.browser.access

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Test
import java.io.StringReader
import java.util.Locale

class DomainBlacklistTest {
    @Test
    fun `matches exact domains and nested subdomains without substring matches`() {
        val list = parse("example.com\nnews.example.org")
        assertThat(list.blocksHost("example.com")).isTrue()
        assertThat(list.blocksHost("a.b.example.com")).isTrue()
        assertThat(list.blocksHost("news.example.org")).isTrue()
        assertThat(list.blocksHost("a.news.example.org")).isTrue()
        assertThat(list.blocksHost("otherexample.com")).isFalse()
        assertThat(list.blocksHost("example.com.evil.org")).isFalse()
        assertThat(list.blocksHost("example.org")).isFalse()
        assertThat(list.blocksHost("mail.example.org")).isFalse()
    }

    @Test
    fun `supports comments BOM CRLF duplicate domains and no final newline`() {
        val list = parse("\uFEFF# comment\r\n\r\n Example.COM. # note\r\nexample.com")
        assertThat(list.blocksHost("WWW.EXAMPLE.COM.")).isTrue()
        assertThat(parse("# empty list\n ").blocksHost("example.com")).isFalse()
    }

    @Test
    fun `www entry does not implicitly block its parent`() {
        val list = parse("www.example.com")
        assertThat(list.blocksHost("www.example.com")).isTrue()
        assertThat(list.blocksHost("example.com")).isFalse()
    }

    @Test
    fun `normalizes unicode and punycode in both directions`() {
        val unicode = parse("例子.测试")
        val ascii = parse("xn--fsqu00a.xn--0zwm56d")
        assertThat(unicode.blocksHost("www.xn--fsqu00a.xn--0zwm56d")).isTrue()
        assertThat(ascii.blocksHost("www.例子.测试")).isTrue()
    }

    @Test
    fun `case normalization is independent of device locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertThat(parse("INSTAGRAM.COM").blocksHost("instagram.com")).isTrue()
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `invalid input reports the line and never silently drops an entry`() {
        for (invalid in listOf(
            "https://example.com", "example.com/path", "*.example.com", "example.com:443",
            "not a domain", "localhost", "-bad.example", "bad..example", "bad_.example"
        )) {
            assertThatThrownBy { parse("# comment\n$invalid") }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("blacklist.txt:2:")
        }
    }

    @Test
    fun `unknown hosts and IPv6 addresses are allowed`() {
        val list = parse("example.com")
        assertThat(list.blocksHost("")).isFalse()
        assertThat(list.blocksHost("localhost")).isFalse()
        assertThat(list.blocksHost("[::1]")).isFalse()
        assertThat(list.blocksHost("192.0.2.1")).isFalse()
    }

    private fun parse(text: String): DomainBlacklist = DomainBlacklist.parse(StringReader(text))
}
