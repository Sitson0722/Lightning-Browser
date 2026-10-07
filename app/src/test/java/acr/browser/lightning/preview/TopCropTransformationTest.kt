package acr.browser.lightning.preview

import acr.browser.lightning.SDK_VERSION
import acr.browser.lightning.TestApplication
import android.graphics.Bitmap
import android.graphics.Color
import coil3.size.Dimension
import coil3.size.Size
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [SDK_VERSION])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TopCropTransformationTest {
    @Test
    fun `extreme aspect ratios and upscaling fit within crop bounds`() = runTest {
        for ((width, height) in listOf(400 to 20, 20 to 400, 1 to 1, 73 to 41)) {
            val input = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            for ((targetWidth, targetHeight) in listOf(150 to 200, 200 to 150, 1 to 1)) {
                val output = TopCropTransformation.transform(input, Size(targetWidth, targetHeight))
                assertThat(output.width).isEqualTo(targetWidth)
                assertThat(output.height).isEqualTo(targetHeight)
                assertThat(input.isRecycled).isFalse()
                if (output !== input) output.recycle()
            }
            input.recycle()
        }
    }

    @Test
    fun `tall screenshot keeps the top rather than vertically centering`() = runTest {
        val input = Bitmap.createBitmap(20, 80, Bitmap.Config.ARGB_8888)
        input.eraseColor(Color.BLUE)
        for (x in 0 until 20) for (y in 0 until 20) input.setPixel(x, y, Color.RED)
        val output = TopCropTransformation.transform(input, Size(10, 10))
        assertThat(output.getPixel(5, 5)).isEqualTo(Color.RED)
        assertThat(input.isRecycled).isFalse()
    }

    @Test
    fun `unspecified dimension preserves aspect ratio and original reuses input`() = runTest {
        val input = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
        val output = TopCropTransformation.transform(input,
            Size(Dimension.Pixels(10), Dimension.Undefined))
        assertThat(output.width).isEqualTo(10)
        assertThat(output.height).isEqualTo(5)
        assertThat(TopCropTransformation.transform(input, Size.ORIGINAL)).isSameAs(input)
    }
}
