package acr.browser.lightning.html.download

import acr.browser.lightning.AppTheme
import acr.browser.lightning.R
import acr.browser.lightning.SDK_VERSION
import acr.browser.lightning.TestApplication
import acr.browser.lightning.concurrency.AppCoroutineScope
import acr.browser.lightning.concurrency.FakeCoroutineDispatchers
import acr.browser.lightning.database.downloads.DownloadEntry
import acr.browser.lightning.database.downloads.DownloadsDatabase
import acr.browser.lightning.html.ListPageReader
import acr.browser.lightning.theme.ThemeProvider
import acr.browser.lightning.utils.ThreadSafeFileProvider
import android.app.Application
import android.app.DownloadManager
import android.text.format.Formatter
import androidx.compose.material3.lightColorScheme
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.jsoup.Jsoup
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.text.DateFormat
import java.util.Date

@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [SDK_VERSION])
class DownloadPageFactoryTest {
    @Test
    fun `page shows actual file size local download time and safe filenames`() = runTest {
        val app = RuntimeEnvironment.getApplication() as Application
        app.deleteDatabase("downloadManager")
        val dispatchers = FakeCoroutineDispatchers(testScheduler)
        val file = File(app.cacheDir, "notes.md").apply { writeBytes(ByteArray(2048)) }
        val time = 1791334800000L
        DownloadsDatabase(app, dispatchers).use { database ->
            database.addDownloadIfNotExists(DownloadEntry(
                "https://example.org/notes.md", "file://$file", "<script>结果.md", "Unknown size",
                downloadedAt = time,
            ))
            database.addDownloadIfNotExists(DownloadEntry(
                "https://example.org/old.md", "file:///missing.md", "old.md", "",
            ))
            val factory = DownloadPageFactory(
                app, database, app.getSystemService(DownloadManager::class.java),
                object : ListPageReader {
                    override fun provideHtml() = """
                        <html><head><title></title><style>
                        :root { --body-bg: {COLOR} --divider-color: {COLOR} --title-color: {COLOR} --subtitle-color: {COLOR} }
                        </style></head><body><div id="content"><div id="repeated" class="box">
                        <a></a><p id="title"></p><p id="url"></p></div></div></body></html>
                    """.trimIndent()
                },
                object : ThemeProvider {
                    override fun appThemeValues() = flowOf(AppTheme.LIGHT)
                    override suspend fun appTheme() = AppTheme.LIGHT
                    override suspend fun colorScheme() = lightColorScheme()
                },
                dispatchers,
                ThreadSafeFileProvider(AppCoroutineScope(this), dispatchers) { File(app.cacheDir, "html") },
            )
            val html = File(factory.buildPage().removePrefix("file://")).readText()
            val page = Jsoup.parse(html)
            val rows = page.select(".box")
            assertThat(rows).hasSize(2)
            val current = rows.last()!!
            val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(time))
            assertThat(current.select("p")[1].text()).isEqualTo(
                app.getString(R.string.download_metadata, Formatter.formatFileSize(app, 2048L), date)
            )
            assertThat(current.select("a").attr("href")).isEqualTo("file://$file")
            assertThat(current.select("#title").text()).isEqualTo("<script>结果.md")
            assertThat(page.select("script")).isEmpty()
            assertThat(rows.first()!!.select("p")[1].text()).contains(
                app.getString(R.string.unknown_size), app.getString(R.string.download_time_unknown)
            )
            assertThat(rows.first()!!.select("a").hasAttr("href")).isFalse()
        }
    }
}
