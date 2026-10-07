package acr.browser.lightning.download

import acr.browser.lightning.SDK_VERSION
import acr.browser.lightning.TestApplication
import acr.browser.lightning.concurrency.FakeCoroutineDispatchers
import acr.browser.lightning.database.downloads.DownloadEntry
import acr.browser.lightning.database.downloads.DownloadsDatabase
import acr.browser.lightning.device.ScreenSize
import acr.browser.lightning.log.NoOpLogger
import acr.browser.lightning.preference.UserPreferencesDataStore
import acr.browser.lightning.resources.DefaultResourceProvider
import android.app.Application
import android.app.DownloadManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [SDK_VERSION])
class DefaultFileDownloaderTest {
    @Test
    fun `MediaStore uses GET response filename and actual bytes even without content length`() = runTest {
        val app = RuntimeEnvironment.getApplication() as Application
        app.deleteDatabase("downloadManager")
        val provider = TestDownloadsProvider().apply {
            attachInfo(app, ProviderInfo().apply { authority = "media" })
        }
        ShadowContentResolver.registerProviderInternal("media", provider)
        val payload = "# 下载结果\n".toByteArray(Charsets.UTF_8)
        val methods = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            methods += chain.request().method
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .header("Content-Type", "application/octet-stream")
                .header("Content-Disposition", "attachment; filename=result.bin; filename*=UTF-8''%E7%BB%93%E6%9E%9C.md")
                .body(payload.toResponseBody()).build()
        }.build()
        val dispatchers = FakeCoroutineDispatchers(testScheduler)
        val database = DownloadsDatabase(app, dispatchers)
        try {
            val downloader = DefaultFileDownloader(app, NoOpLogger(), database, DefaultResourceProvider(app),
                app.getSystemService(DownloadManager::class.java), dispatchers, CompletableDeferred(client),
                UserPreferencesDataStore(app, ScreenSize(app)))
            downloader.download(PendingDownload("https://example.org/export", "test-agent", null,
                "application/octet-stream", -1L))
            val entry = database.getAllDownloads().single()
            assertThat(methods).containsExactly("GET")
            assertThat(entry.title).isEqualTo("结果.md")
            assertThat(entry.sizeBytes).isEqualTo(payload.size.toLong())
            assertThat(entry.downloadedAt).isPositive()
            assertThat(entry.downloadManagerId).isEqualTo(DownloadEntry.MEDIA_STORE_DOWNLOAD_ID)
            assertThat(provider.values.getAsString(MediaStore.Downloads.DISPLAY_NAME)).isEqualTo("结果.md")
            assertThat(provider.values.getAsString(MediaStore.Downloads.MIME_TYPE)).isEqualTo("text/markdown")
            assertThat(provider.values.getAsInteger(MediaStore.Downloads.IS_PENDING)).isZero()
            assertThat(provider.file.readBytes()).isEqualTo(payload)
        } finally {
            database.close()
        }
    }

    @Test
    @Config(sdk = [28])
    fun `DownloadManager preserves markdown URL names with generic content types`() = runTest {
        val app = RuntimeEnvironment.getApplication() as Application
        app.deleteDatabase("downloadManager")
        val dispatchers = FakeCoroutineDispatchers(testScheduler)
        val client = OkHttpClient.Builder().addInterceptor { error("No HEAD required with supplied metadata") }.build()
        val database = DownloadsDatabase(app, dispatchers)
        try {
            val manager = app.getSystemService(DownloadManager::class.java)
            val downloader = DefaultFileDownloader(app, NoOpLogger(), database, DefaultResourceProvider(app),
                manager, dispatchers, CompletableDeferred(client), UserPreferencesDataStore(app, ScreenSize(app)))
            downloader.download(PendingDownload("https://example.org/%E7%BB%93%E6%9E%9C.md", null,
                null, "application/octet-stream", 1024L))
            val entry = database.getAllDownloads().single()
            assertThat(entry.title).isEqualTo("结果.md")
            assertThat(entry.location).endsWith("/结果.md")
            assertThat(entry.sizeBytes).isEqualTo(1024L)
            assertThat(entry.downloadedAt).isPositive()
            manager.query(DownloadManager.Query().setFilterById(entry.downloadManagerId)).use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)))
                    .isEqualTo("结果.md")
            }
        } finally {
            database.close()
        }
    }

    /** A local provider exercises ContentResolver streaming without network or device storage. */
    private class TestDownloadsProvider : ContentProvider() {
        lateinit var file: File
        lateinit var values: ContentValues
        private val uri = Uri.parse("content://media/external/downloads/1")
        override fun onCreate(): Boolean {
            file = File(requireNotNull(context).cacheDir, "downloaded.md")
            return true
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            this.values = ContentValues(requireNotNull(values))
            return this.uri
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = ParcelFileDescriptor.open(
            file, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_WRITE_ONLY
        )
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): MatrixCursor {
            val columns = requireNotNull(projection)
            return MatrixCursor(columns).apply {
                addRow(columns.map { column ->
                    if (column == MediaStore.MediaColumns.SIZE) file.length() else values.getAsString(column)
                }.toTypedArray())
            }
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            this.values.putAll(requireNotNull(values))
            return 1
        }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            file.delete()
            return 1
        }
        override fun getType(uri: Uri): String = "text/markdown"
    }
}
