package acr.browser.lightning.html.download

import acr.browser.lightning.R
import acr.browser.lightning.compose.toRgbHexString
import acr.browser.lightning.concurrency.CoroutineDispatchers
import acr.browser.lightning.constant.FILE
import acr.browser.lightning.database.downloads.DownloadEntry
import acr.browser.lightning.database.downloads.DownloadsRepository
import acr.browser.lightning.di.GeneratedHtmlDir
import acr.browser.lightning.html.HtmlPageFactory
import acr.browser.lightning.html.ListPageReader
import acr.browser.lightning.html.jsoup.andBuild
import acr.browser.lightning.html.jsoup.body
import acr.browser.lightning.html.jsoup.clone
import acr.browser.lightning.html.jsoup.findId
import acr.browser.lightning.html.jsoup.id
import acr.browser.lightning.html.jsoup.parse
import acr.browser.lightning.html.jsoup.removeElement
import acr.browser.lightning.html.jsoup.style
import acr.browser.lightning.html.jsoup.tag
import acr.browser.lightning.html.jsoup.title
import acr.browser.lightning.theme.ThemeProvider
import acr.browser.lightning.utils.ThreadSafeFileProvider
import android.app.Application
import android.app.DownloadManager
import android.database.Cursor
import android.provider.MediaStore
import android.text.format.Formatter
import androidx.core.net.toUri
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.text.DateFormat
import java.util.Date
import org.jsoup.nodes.Element
import javax.inject.Inject

/**
 * The factory for the downloads page.
 */
class DownloadPageFactory @Inject constructor(
    private val application: Application,
    private val manager: DownloadsRepository,
    private val downloadManager: DownloadManager,
    private val listPageReader: ListPageReader,
    private val themeProvider: ThemeProvider,
    private val coroutineDispatchers: CoroutineDispatchers,
    @GeneratedHtmlDir private val generatedHtmlDir: ThreadSafeFileProvider,
) : HtmlPageFactory {

    override suspend fun buildPage(): String = withContext(coroutineDispatchers.io) {
        val colorScheme = themeProvider.colorScheme()
        val downloads = manager.getAllDownloads().map { download ->
            download.resolveDownload()
        }
        val content = parse(listPageReader.provideHtml()) andBuild {
            title { application.getString(R.string.action_downloads) }
            style { content ->
                content.replace(
                    "--body-bg: {COLOR}",
                    "--body-bg: #${colorScheme.surface.toRgbHexString()};"
                ).replace(
                    "--divider-color: {COLOR}",
                    "--divider-color: #${colorScheme.outlineVariant.toRgbHexString()};"
                ).replace(
                    "--title-color: {COLOR}",
                    "--title-color: #${colorScheme.onSurface.toRgbHexString()};"
                ).replace(
                    "--subtitle-color: {COLOR}",
                    "--subtitle-color: #${colorScheme.onSurfaceVariant.toRgbHexString()};"
                )
            }
            body {
                val repeatableElement = findId("repeated").removeElement()
                id("content") {
                    downloads.forEach { resolvedDownload ->
                        val download = resolvedDownload.entry
                        appendChild(repeatableElement.clone {
                            tag("a") {
                                resolvedDownload.openUri?.let { attr("href", it) }
                                    ?: removeAttr("href")
                            }
                            id("title") { text(download.title) }
                            findId("url").before(
                                Element("p").attr("class", "font").text(createMetadata(download))
                            )
                            id("url") {
                                text("${resolvedDownload.statusText} — ${download.url}")
                            }
                        })
                    }
                }
            }
        }
        val page = createDownloadsPageFile()
        FileWriter(page, false).use { it.write(content) }

        "$FILE$page"
    }

    private suspend fun createDownloadsPageFile(): File {
        val generatedHtml = generatedHtmlDir.file()
        generatedHtml.mkdirs()
        return File(generatedHtml, FILENAME)
    }

    private fun createMetadata(download: DownloadEntry): String {
        val size = if (download.sizeBytes >= 0) {
            Formatter.formatFileSize(application, download.sizeBytes)
        } else {
            download.contentSize.takeIf(String::isNotBlank)
                ?: application.getString(R.string.unknown_size)
        }
        val time = if (download.downloadedAt > 0) {
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
                .format(Date(download.downloadedAt))
        } else {
            application.getString(R.string.download_time_unknown)
        }
        return application.getString(R.string.download_metadata, size, time)
    }

    private fun DownloadEntry.resolveDownload(): ResolvedDownload {
        if (downloadManagerId == DownloadEntry.MEDIA_STORE_DOWNLOAD_ID) {
            val actualSize = try {
                application.contentResolver.query(
                    location.toUri(), arrayOf(MediaStore.MediaColumns.SIZE), null, null, null
                )?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
                }
            } catch (_: SecurityException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
            return ResolvedDownload(
                entry = copy(sizeBytes = actualSize ?: sizeBytes),
                openUri = location.takeIf { actualSize != null },
                statusText = application.getString(
                    if (actualSize != null) R.string.download_status_complete
                    else R.string.download_status_unavailable
                )
            )
        }
        if (downloadManagerId < 0L) {
            val legacyLocation = location.takeIf {
                it.removePrefix(FILE).let(::File).exists()
            }
            return ResolvedDownload(
                entry = copy(sizeBytes = legacyLocation?.removePrefix(FILE)?.let(::File)?.length()
                    ?: sizeBytes),
                openUri = legacyLocation,
                statusText = if (legacyLocation != null) {
                    application.getString(R.string.download_status_complete)
                } else {
                    application.getString(R.string.download_status_unavailable)
                }
            )
        }

        val systemStatus = downloadManager.query(
            DownloadManager.Query().setFilterById(downloadManagerId)
        )?.use { cursor -> cursor.downloadStatus() } ?: SystemDownloadStatus.Unavailable

        val openUri = if (systemStatus.status == DownloadManager.STATUS_SUCCESSFUL) {
            downloadManager.getUriForDownloadedFile(downloadManagerId)?.toString()
        } else {
            null
        }
        return ResolvedDownload(
            copy(sizeBytes = systemStatus.totalBytes.takeIf { it >= 0 } ?: sizeBytes),
            openUri,
            systemStatus.displayText()
        )
    }

    private fun Cursor.downloadStatus(): SystemDownloadStatus = if (moveToFirst()) {
        SystemDownloadStatus(
            status = getInt(getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
            reason = getInt(getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
            totalBytes = getLong(getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
        )
    } else {
        SystemDownloadStatus.Unavailable
    }

    private fun SystemDownloadStatus.displayText(): String = when (status) {
        DownloadManager.STATUS_PENDING -> application.getString(R.string.download_status_pending)
        DownloadManager.STATUS_RUNNING -> application.getString(R.string.download_status_running)
        DownloadManager.STATUS_PAUSED -> application.getString(R.string.download_status_paused)
        DownloadManager.STATUS_SUCCESSFUL ->
            application.getString(R.string.download_status_complete)
        DownloadManager.STATUS_FAILED -> application.getString(
            R.string.download_status_failed,
            failureReason(reason)
        )
        else -> application.getString(R.string.download_status_unavailable)
    }

    private fun failureReason(reason: Int): String = when (reason) {
        DownloadManager.ERROR_CANNOT_RESUME -> "cannot resume"
        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "storage unavailable"
        DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "file already exists"
        DownloadManager.ERROR_FILE_ERROR -> "file error"
        DownloadManager.ERROR_HTTP_DATA_ERROR -> "HTTP data error"
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "insufficient storage"
        DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "too many redirects"
        DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "HTTP error"
        else -> "error $reason"
    }

    private data class ResolvedDownload(
        val entry: DownloadEntry,
        val openUri: String?,
        val statusText: String,
    )

    private data class SystemDownloadStatus(
        val status: Int,
        val reason: Int,
        val totalBytes: Long = -1L,
    ) {
        companion object {
            val Unavailable = SystemDownloadStatus(status = -1, reason = 0)
        }
    }

    companion object {

        const val FILENAME = "downloads.html"

    }

}
