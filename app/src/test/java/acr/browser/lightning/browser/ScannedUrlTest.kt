package acr.browser.lightning.browser

import acr.browser.lightning.SDK_VERSION
import acr.browser.lightning.TestApplication
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [SDK_VERSION])
class ScannedUrlTest {
    @Test
    fun `web URLs trim whitespace and normalize scheme without modifying path`() {
        assertThat(scannedUrl(" \nHTTPS://example.org/CaseSensitive?q=One#Two\t"))
            .isEqualTo("https://example.org/CaseSensitive?q=One#Two")
        assertThat(scannedUrl("hTtP://example.org:8080/path"))
            .isEqualTo("http://example.org:8080/path")
        assertThat(scannedUrl("https://example.org/a%20b"))
            .isEqualTo("https://example.org/a%20b")
    }

    @Test
    fun `text malformed web URLs and executable schemes never open automatically`() {
        for (content in listOf(
            "", " \n", "普通文本", "example.org", "https:///", "https://",
            "https://example.org/a b", "https://example.org/\npath",
            "javascript:alert(1)", "file:///secret", "content://files/private",
            "intent://example.org#Intent;scheme=https;end", "mailto:a@example.org",
        )) {
            assertThat(scannedUrl(content)).describedAs(content).isNull()
        }
    }
}
