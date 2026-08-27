package acr.browser.lightning.download

import acr.browser.lightning.R
import acr.browser.lightning.concurrency.CoroutineDispatchers
import acr.browser.lightning.constant.FILE
import acr.browser.lightning.database.downloads.DownloadEntry
import acr.browser.lightning.database.downloads.DownloadsRepository
import acr.browser.lightning.di.NoCacheClient
import acr.browser.lightning.log.Logger
import acr.browser.lightning.preference.UserPreferencesDataStore
import acr.browser.lightning.resources.ResourceProvider
import acr.browser.lightning.utils.FileUtils
import android.app.Application
import android.app.DownloadManager
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.format.Formatter
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import androidx.core.net.toUri
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject

/**
 * Default implementation of [FileDownloader] backed by [DownloadManager].
 */
class DefaultFileDownloader @Inject constructor(
    private val application: Application,
    private val logger: Logger,
    private val downloadsRepository: DownloadsRepository,
    private val resourceProvider: ResourceProvider,
    private val downloadManager: DownloadManager,
    private val coroutineDispatchers: CoroutineDispatchers,
    @NoCacheClient
    private val okHttpClient: Deferred<@JvmSuppressWildcards OkHttpClient>,
    private val userPreferencesDataStore: UserPreferencesDataStore,
) : FileDownloader {

    override suspend fun download(pendingDownload: PendingDownload) =
        withContext(coroutineDispatchers.io) {
            logger.log("DefaultFileDownloader", "Pending download: $pendingDownload")

            val cookie = CookieManager.getInstance().getCookie(pendingDownload.url)

            val normalizedPendingDownload = fetchFileInfo(cookie, pendingDownload)

            val guessExtension = normalizedPendingDownload.mimeType?.let {
                MimeTypeMap.getSingleton().getExtensionFromMimeType(it)
            } ?: MimeTypeMap.getFileExtensionFromUrl(normalizedPendingDownload.url)

            val guessMimeType = normalizedPendingDownload.mimeType
                ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(guessExtension)
                    ?.takeIf { it.isNotEmpty() }
                ?: "text/plain"

            val guessFileName = URLUtil.guessFileName(
                normalizedPendingDownload.url,
                normalizedPendingDownload.contentDisposition,
                guessMimeType
            )

            val fileSubPath =
                when (val downloadDirectory = userPreferencesDataStore.downloadDirectory.get()) {
                    "" -> guessFileName
                    else -> "$downloadDirectory/$guessFileName"
                }

            val contentSize = if (normalizedPendingDownload.contentLength > 0) {
                Formatter.formatFileSize(application, normalizedPendingDownload.contentLength)
            } else {
                resourceProvider.stringResource(R.string.unknown_size)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                downloadIntoMediaStore(
                    pendingDownload = normalizedPendingDownload,
                    cookie = cookie,
                    fileName = guessFileName,
                    fileSubPath = fileSubPath,
                    mimeType = guessMimeType,
                    contentSize = contentSize,
                )
                return@withContext
            }

            val request = DownloadManager.Request(normalizedPendingDownload.url.toUri())
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setTitle(guessFileName)
                .setDescription(
                    normalizedPendingDownload.contentDisposition ?: normalizedPendingDownload.url
                )
                .setMimeType(guessMimeType)
                .setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    fileSubPath
                )

            cookie?.takeIf(String::isNotBlank)?.let { request.addRequestHeader("Cookie", it) }
            normalizedPendingDownload.userAgent?.takeIf(String::isNotBlank)?.let {
                request.addRequestHeader("User-Agent", it)
            }

            val downloadManagerId = downloadManager.enqueue(request)

            downloadsRepository.addDownloadIfNotExists(
                DownloadEntry(
                    url = normalizedPendingDownload.url,
                    location = "${FILE}${FileUtils.DEFAULT_DOWNLOAD_PATH}/$fileSubPath",
                    title = guessFileName,
                    contentSize = contentSize,
                    downloadManagerId = downloadManagerId,
                )
            )
            Unit
        }

    private suspend fun downloadIntoMediaStore(
        pendingDownload: PendingDownload,
        cookie: String?,
        fileName: String,
        fileSubPath: String,
        mimeType: String,
        contentSize: String,
    ) = withContext(coroutineDispatchers.network) {
        val relativeDirectory = fileSubPath.substringBeforeLast('/', missingDelimiterValue = "")
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(
                MediaStore.Downloads.RELATIVE_PATH,
                listOf(Environment.DIRECTORY_DOWNLOADS, relativeDirectory)
                    .filter(String::isNotBlank)
                    .joinToString("/")
            )
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val contentResolver = application.contentResolver
        val destination = requireNotNull(
            contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ) { "Unable to create download destination" }

        try {
            val request = Request.Builder().url(pendingDownload.url).get().apply {
                cookie?.takeIf(String::isNotBlank)?.let { addHeader("Cookie", it) }
                pendingDownload.userAgent?.takeIf(String::isNotBlank)?.let {
                    addHeader("User-Agent", it)
                }
            }.build()

            okHttpClient.await().newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Download failed with HTTP ${response.code}" }
                val body = requireNotNull(response.body) { "Download response had no body" }
                contentResolver.openOutputStream(destination, "w").use { output ->
                    requireNotNull(output) { "Unable to open download destination" }
                    body.byteStream().use { input -> input.copyTo(output) }
                }
            }

            contentResolver.update(
                destination,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null
            )
            downloadsRepository.addDownloadIfNotExists(
                DownloadEntry(
                    url = pendingDownload.url,
                    location = destination.toString(),
                    title = fileName,
                    contentSize = contentSize,
                    downloadManagerId = DownloadEntry.MEDIA_STORE_DOWNLOAD_ID,
                )
            )
        } catch (error: Exception) {
            contentResolver.delete(destination, null, null)
            logger.log(TAG, "MediaStore download failed: ${error.message}")
            throw error
        }
    }

    private suspend fun fetchFileInfo(
        cookie: String?,
        pendingDownload: PendingDownload,
    ): PendingDownload = withContext(coroutineDispatchers.network) {
        if (pendingDownload.mimeType != null && pendingDownload.contentLength != 0L) {
            return@withContext pendingDownload
        }

        okHttpClient.await().newCall(
            Request.Builder()
                .url(pendingDownload.url)
                .head()
                .addHeader("Cookie", cookie.orEmpty())
                .addHeader("User-Agent", pendingDownload.userAgent.orEmpty())
                .build()
        ).execute().use { response ->
            logger.log(TAG, "HEAD: ${response.headers}")

            pendingDownload.copy(
                mimeType = response.header("content-type") ?: pendingDownload.mimeType,
                contentLength = response.header("content-length")?.toLongOrNull()
                    ?: pendingDownload.contentLength,
                contentDisposition = response.header("content-disposition")
                    ?: pendingDownload.contentDisposition ?: "attachment"
            )
        }
    }

    companion object {
        private const val TAG = "DefaultFileDownloader"
    }
}
