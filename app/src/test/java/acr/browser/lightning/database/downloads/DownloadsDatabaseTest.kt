package acr.browser.lightning.database.downloads

import acr.browser.lightning.SDK_VERSION
import acr.browser.lightning.TestApplication
import acr.browser.lightning.concurrency.FakeCoroutineDispatchers
import android.app.Application
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [SDK_VERSION])
class DownloadsDatabaseTest {
    @Test
    fun `size and download time survive database reopen including empty files`() = runTest {
        val app = RuntimeEnvironment.getApplication() as Application
        app.deleteDatabase("downloadManager")
        val dispatchers = FakeCoroutineDispatchers(testScheduler)
        val entry = DownloadEntry("https://example.org/结果.md", "content://downloads/1", "结果.md",
            "0 B", DownloadEntry.MEDIA_STORE_DOWNLOAD_ID, 0L, 1791334800000L)
        DownloadsDatabase(app, dispatchers).use { db ->
            assertThat(db.addDownloadIfNotExists(entry)).isTrue()
        }
        DownloadsDatabase(app, dispatchers).use { db ->
            assertThat(db.getAllDownloads()).containsExactly(entry)
        }
    }

    @Test
    fun `upgrading v2 and v3 retains downloads and leaves unknown metadata unset`() = runTest {
        val app = RuntimeEnvironment.getApplication() as Application
        for (version in listOf(2, 3)) {
            app.deleteDatabase("downloadManager")
            val path = app.getDatabasePath("downloadManager").apply { parentFile?.mkdirs() }
            SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
                val systemIdColumn = if (version == 3) ", download_manager_id INTEGER NOT NULL DEFAULT -1" else ""
                db.execSQL("CREATE TABLE download (id INTEGER PRIMARY KEY, url TEXT, location TEXT, title TEXT, size TEXT$systemIdColumn)")
                db.execSQL("INSERT INTO download (url, location, title, size) VALUES ('https://example.org/old.md', 'file:///old.md', 'old.md', '2 KB')")
                if (version == 3) db.execSQL("UPDATE download SET download_manager_id = 123")
                db.version = version
            }
            DownloadsDatabase(app, FakeCoroutineDispatchers(testScheduler)).use { db ->
                val entry = db.getAllDownloads().single()
                assertThat(entry.title).isEqualTo("old.md")
                assertThat(entry.contentSize).isEqualTo("2 KB")
                assertThat(entry.downloadManagerId).isEqualTo(if (version == 3) 123L else -1L)
                assertThat(entry.sizeBytes).isEqualTo(-1L)
                assertThat(entry.downloadedAt).isZero()
            }
        }
    }
}
