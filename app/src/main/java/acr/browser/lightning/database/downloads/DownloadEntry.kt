package acr.browser.lightning.database.downloads

/**
 * An entry in the downloads database.
 *
 * @param url The URL of the original download.
 * @param location The location of the download on disk.
 * @param title The file name.
 * @param contentSize The user readable content size.
 * @param downloadManagerId The ID assigned by Android's DownloadManager.
 */
data class DownloadEntry(
    val url: String,
    val location: String,
    val title: String,
    val contentSize: String,
    val downloadManagerId: Long = -1L,
)
