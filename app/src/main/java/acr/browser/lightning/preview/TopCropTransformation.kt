package acr.browser.lightning.preview

import android.graphics.Bitmap
import androidx.core.graphics.scale
import coil3.size.Dimension
import coil3.size.Size
import coil3.size.isOriginal
import coil3.transform.Transformation
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Scales a [Bitmap] to cover the destination, then crops from the top and horizontal center.
 */
object TopCropTransformation : Transformation() {
    override val cacheKey: String = "TopCropTransformation:v2"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        if (size.isOriginal) return input
        val width = (size.width as? Dimension.Pixels)?.px
        val height = (size.height as? Dimension.Pixels)?.px
        val targetWidth = (width ?: (input.width * (height ?: input.height).toDouble() /
            input.height).roundToInt()).coerceAtLeast(1)
        val targetHeight = (height ?: (input.height * targetWidth.toDouble() /
            input.width).roundToInt()).coerceAtLeast(1)
        val multiplier = max(
            targetWidth.toDouble() / input.width,
            targetHeight.toDouble() / input.height
        )

        val scaled = input.scale(
            ceil(input.width * multiplier).toInt().coerceAtLeast(targetWidth),
            ceil(input.height * multiplier).toInt().coerceAtLeast(targetHeight),
            false
        )
        val output = Bitmap.createBitmap(
            scaled, (scaled.width - targetWidth) / 2, 0, targetWidth, targetHeight
        )
        if (scaled !== input && scaled !== output) scaled.recycle()
        return output
    }
}
