package acr.browser.lightning.browser.access

import acr.browser.lightning.SDK_VERSION
import acr.browser.lightning.TestApplication
import android.app.Application
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.StringReader

@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [SDK_VERSION])
class SiteAccessPolicyTest {
    private lateinit var policy: SiteAccessPolicy

    @Before
    fun setUp() {
        val application = RuntimeEnvironment.getApplication() as Application
        // An upgraded installation can retain an old site allowlist.
        application.getSharedPreferences("site_access_policy", 0).edit()
            .putStringSet("allowed_domains", setOf("reddit.com"))
            .commit()
        policy = SiteAccessPolicy(
            DomainBlacklist.parse(StringReader("reddit.com\nnetflix.com\nqzone.qq.com"))
        )
    }

    @Test
    fun `blacklist is active immediately despite saved allowlist`() {
        assertThat(policy.isUrlAllowed("https://reddit.com")).isFalse()
        assertThat(policy.isUrlAllowed("https://old.reddit.com/r/test")).isFalse()
        assertThat(policy.isUrlAllowed("https://www.netflix.com")).isFalse()
    }

    @Test
    fun `every domain in the generated APK asset is blocked on construction`() {
        val application = RuntimeEnvironment.getApplication() as Application
        val entries = application.assets.open("blacklist.txt").bufferedReader().use { reader ->
            reader.lineSequence().map { it.removePrefix("\uFEFF").substringBefore('#').trim() }
                .filter(String::isNotEmpty).toSet()
        }
        application.getSharedPreferences("site_access_policy", 0).edit()
            .putStringSet("allowed_domains", entries).commit()
        val bundledPolicy = SiteAccessPolicy(application)
        for (domain in entries) {
            assertThat(bundledPolicy.isUrlAllowed("https://$domain")).isFalse()
            assertThat(bundledPolicy.isUrlAllowed("https://child.$domain/path")).isFalse()
        }
    }

    @Test
    fun `unlisted sites including formerly restricted sites are allowed`() {
        assertThat(policy.isUrlAllowed("https://example.org")).isTrue()
        assertThat(policy.isUrlAllowed("https://my.lzu.edu.cn")).isTrue()
        assertThat(policy.isUrlAllowed("https://notmy.lzu.edu.cn")).isTrue()
        assertThat(policy.isUrlAllowed("https://otherreddit.com")).isTrue()
        assertThat(policy.isUrlAllowed("https://reddit.com.example.org")).isTrue()
    }

    @Test
    fun `a listed subdomain does not block its parent or siblings`() {
        assertThat(policy.isUrlAllowed("https://qzone.qq.com")).isFalse()
        assertThat(policy.isUrlAllowed("https://a.qzone.qq.com")).isFalse()
        assertThat(policy.isUrlAllowed("https://qq.com")).isTrue()
        assertThat(policy.isUrlAllowed("https://mail.qq.com")).isTrue()
    }

    @Test
    fun `download extensions do not exempt blacklisted hosts`() {
        assertThat(policy.isUrlAllowed("https://reddit.com/report.pdf")).isFalse()
        assertThat(policy.isUrlAllowed("https://reddit.com/archive.ZIP?source=mail")).isFalse()
        assertThat(policy.isUrlAllowed("https://files.example.org/report.pdf")).isTrue()
    }

    @Test
    fun `matching handles case ports trailing dots encoded hosts and credentials`() {
        assertThat(policy.isUrlAllowed("HTTPS://REDDIT.COM.:8443/path")).isFalse()
        assertThat(policy.isUrlAllowed("https://%72eddit.com/path")).isFalse()
        assertThat(policy.isUrlAllowed("https://example.org@reddit.com/path")).isFalse()
        assertThat(policy.isUrlAllowed("https://reddit.com@example.org/path")).isTrue()
    }

    @Test
    fun `internal URLs remain accessible`() {
        assertThat(policy.isUrlAllowed("about:blank")).isTrue()
        assertThat(policy.isUrlAllowed("file:///internal/homepage.html")).isTrue()
        assertThat(policy.isUrlAllowed("data:text/html,hello")).isTrue()
    }

    @Test
    fun `blocked page explains the fixed blacklist and escapes host markup`() {
        val html = policy.blockedPageHtml("https://%3Cscript%3E.example.org")
        assertThat(html).contains("Site blocked", "built-in blacklist", "&lt;script&gt;")
        assertThat(html).doesNotContain("<script>", "12:00", "22:00", "allowed-sites")
    }
}
