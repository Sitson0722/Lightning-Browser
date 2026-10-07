package acr.browser.lightning.browser.tab

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.webkit.ValueCallback
import androidx.activity.result.ActivityResult

/** A single upload request, bound to its originating WebView rather than the selected tab. */
class FileUploadRequest(
    val intent: Intent,
    private var callback: ValueCallback<Array<Uri>>?,
) {
    val isPending: Boolean get() = callback != null

    fun cancel() = complete(null)

    fun onResult(result: ActivityResult) {
        if (!isPending) return
        val data = result.data
        val uris = if (result.resultCode == Activity.RESULT_OK && data != null) {
            val clipData = data.clipData
            if (clipData != null) {
                (0 until clipData.itemCount).mapNotNull { clipData.getItemAt(it).uri }
                    .distinct().toTypedArray().takeIf { it.isNotEmpty() }
            } else {
                data.data?.let { arrayOf(it) }
            }
        } else {
            null
        }
        complete(uris)
    }

    private fun complete(uris: Array<Uri>?) {
        val receiver = callback
        callback = null
        receiver?.onReceiveValue(uris)
    }
}
