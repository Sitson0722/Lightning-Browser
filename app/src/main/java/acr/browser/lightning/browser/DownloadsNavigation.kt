package acr.browser.lightning.browser

import acr.browser.lightning.utils.isDownloadsUrl

/** Select a destination before deleting the downloads tab, including restored normal tabs. */
internal suspend fun BrowserContract.Model.returnFromDownloads(
    downloadTabId: Int,
    originId: Int?,
    select: suspend (Int) -> Unit,
    openHome: suspend () -> Unit,
) {
    val returnId = originId?.takeIf { id -> tabsList.any { it.id == id && id != downloadTabId } }
        ?: tabsList.lastOrNull { it.id != downloadTabId && !it.url.isDownloadsUrl() }?.id
        ?: tabsList.lastOrNull { it.id != downloadTabId }?.id
    if (returnId != null) select(returnId) else openHome()
    deleteTab(downloadTabId)
}
