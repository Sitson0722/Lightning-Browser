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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [SDK_VERSION])
class SiteAccessPolicyTest {

    private lateinit var policy: SiteAccessPolicy

    @Before
    fun setUp() {
        val application = RuntimeEnvironment.getApplication() as Application
        application.getSharedPreferences("site_access_policy", 0).edit().clear().commit()
        policy = SiteAccessPolicy(application)
    }

    @Test
    fun `editing window uses fixed UTC plus eight boundaries`() {
        assertThat(policy.isEditingWindowOpen(clockAt("2026-01-01T13:59:59Z"))).isFalse()
        assertThat(policy.isEditingWindowOpen(clockAt("2026-01-01T14:00:00Z"))).isTrue()
        assertThat(policy.isEditingWindowOpen(clockAt("2026-01-01T14:59:59Z"))).isTrue()
        assertThat(policy.isEditingWindowOpen(clockAt("2026-01-01T15:00:00Z"))).isFalse()
    }

    @Test
    fun `unrestricted browsing includes midday and editing windows`() {
        assertThat(
            policy.isUnrestrictedBrowsingWindowOpen(clockAt("2026-01-01T03:59:59Z"))
        ).isFalse()
        assertThat(
            policy.isUnrestrictedBrowsingWindowOpen(clockAt("2026-01-01T04:00:00Z"))
        ).isTrue()
        assertThat(
            policy.isUnrestrictedBrowsingWindowOpen(clockAt("2026-01-01T04:59:59Z"))
        ).isTrue()
        assertThat(
            policy.isUnrestrictedBrowsingWindowOpen(clockAt("2026-01-01T05:00:00Z"))
        ).isFalse()
        assertThat(
            policy.isUnrestrictedBrowsingWindowOpen(clockAt("2026-01-01T14:30:00Z"))
        ).isTrue()
    }

    @Test
    fun `site can only be added during editing window`() {
        val closedResult = policy.allowUrl("https://example.com", clockAt("2026-01-01T13:00:00Z"))
        val middayResult = policy.allowUrl("https://example.com", clockAt("2026-01-01T04:30:00Z"))
        val openResult = policy.allowUrl("https://example.com", clockAt("2026-01-01T14:30:00Z"))

        assertThat(closedResult).isEqualTo(SiteAccessPolicy.AddResult.WindowClosed)
        assertThat(middayResult).isEqualTo(SiteAccessPolicy.AddResult.WindowClosed)
        assertThat(openResult).isEqualTo(SiteAccessPolicy.AddResult.Added("example.com"))
    }

    @Test
    fun `unsaved sites are allowed during midday browsing window`() {
        assertThat(
            policy.isUrlAllowed("https://unsaved.example", clockAt("2026-01-01T04:30:00Z"))
        ).isTrue()
    }

    @Test
    fun `saved domain and its subdomains are allowed outside editing window`() {
        policy.allowUrl("https://www.example.com/page", clockAt("2026-01-01T14:30:00Z"))
        val closedClock = clockAt("2026-01-01T16:00:00Z")

        assertThat(policy.isUrlAllowed("https://example.com", closedClock)).isTrue()
        assertThat(policy.isUrlAllowed("https://news.example.com", closedClock)).isTrue()
        assertThat(policy.isUrlAllowed("https://example.org", closedClock)).isFalse()
    }

    @Test
    fun `built in domains and their subdomains are always allowed`() {
        val closedClock = clockAt("2026-01-01T16:00:00Z")

        assertThat(policy.isUrlAllowed("https://sitson.pages.dev/path", closedClock)).isTrue()
        assertThat(policy.isUrlAllowed("https://sixfeet6.github.io/app", closedClock)).isTrue()
        assertThat(policy.isUrlAllowed("https://my.lzu.edu.cn/portal", closedClock)).isTrue()
        assertThat(policy.isUrlAllowed("https://login.my.lzu.edu.cn/", closedClock)).isTrue()
        assertThat(policy.isUrlAllowed("https://notmy.lzu.edu.cn/", closedClock)).isFalse()
    }

    @Test
    fun `internal browser URLs remain allowed`() {
        val closedClock = clockAt("2026-01-01T16:00:00Z")

        assertThat(policy.isUrlAllowed("about:blank", closedClock)).isTrue()
        assertThat(policy.isUrlAllowed("file:///internal/homepage.html", closedClock)).isTrue()
    }

    @Test
    fun `direct file downloads are allowed from unsaved domains`() {
        val closedClock = clockAt("2026-01-01T16:00:00Z")

        assertThat(
            policy.isUrlAllowed("https://files.example.org/report.pdf", closedClock)
        ).isTrue()
        assertThat(
            policy.isUrlAllowed("https://files.example.org/archive.ZIP?source=mail", closedClock)
        ).isTrue()
        assertThat(
            policy.isUrlAllowed("https://files.example.org/ordinary-page", closedClock)
        ).isFalse()
    }

    private fun clockAt(instant: String): Clock =
        Clock.fixed(Instant.parse(instant), ZoneOffset.UTC)
}
