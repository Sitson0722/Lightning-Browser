package acr.browser.lightning.browser.tab

import acr.browser.lightning.SDK_VERSION
import acr.browser.lightning.TestApplication
import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.webkit.ValueCallback
import androidx.activity.result.ActivityResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [SDK_VERSION])
class FileUploadRequestTest {
    @Test
    fun `result completes only its originating request and only once`() {
        val firstResults = mutableListOf<Array<Uri>?>()
        val otherResults = mutableListOf<Array<Uri>?>()
        val first = request(firstResults)
        val other = request(otherResults)
        val uri = Uri.parse("content://files/report.pdf")

        first.onResult(ActivityResult(Activity.RESULT_OK, Intent().setData(uri)))
        first.cancel()
        first.onResult(ActivityResult(Activity.RESULT_CANCELED, null))

        assertThat(firstResults).hasSize(1)
        assertThat(firstResults.single()!!.toList()).containsExactly(uri)
        assertThat(otherResults).isEmpty()
        assertThat(first.isPending).isFalse()
        assertThat(other.isPending).isTrue()
    }

    @Test
    fun `multi select returns every URI without duplicates`() {
        val results = mutableListOf<Array<Uri>?>()
        val request = request(results)
        val first = Uri.parse("content://files/one.png")
        val second = Uri.parse("content://files/two.png")
        val clip = ClipData.newRawUri("files", first).apply {
            addItem(ClipData.Item(second))
            addItem(ClipData.Item(first))
        }

        request.onResult(ActivityResult(Activity.RESULT_OK, Intent().apply { clipData = clip }))

        assertThat(results.single()!!.toList()).containsExactly(first, second)
    }

    @Test
    fun `closed tab cancels once and ignores late picker results`() {
        val results = mutableListOf<Array<Uri>?>()
        val request = request(results)
        request.cancel()
        request.cancel()
        request.onResult(ActivityResult(Activity.RESULT_OK,
            Intent().setData(Uri.parse("content://files/late.txt"))))

        assertThat(results).hasSize(1)
        assertThat(results.single()).isNull()
    }

    @Test
    fun `cancel missing data and empty selection never return stale URIs`() {
        for (result in listOf(
            ActivityResult(Activity.RESULT_CANCELED,
                Intent().setData(Uri.parse("content://files/ignored.txt"))),
            ActivityResult(Activity.RESULT_OK, null),
            ActivityResult(Activity.RESULT_OK, Intent()),
        )) {
            val results = mutableListOf<Array<Uri>?>()
            request(results).onResult(result)
            assertThat(results).hasSize(1)
            assertThat(results.single()).isNull()
        }
    }

    @Test
    fun `callback is cleared before invoking receiver`() {
        var calls = 0
        lateinit var request: FileUploadRequest
        request = FileUploadRequest(Intent(), ValueCallback {
            calls++
            request.cancel()
        })
        request.cancel()
        assertThat(calls).isEqualTo(1)
    }

    private fun request(results: MutableList<Array<Uri>?>) =
        FileUploadRequest(Intent(), ValueCallback { results.add(it) })
}
