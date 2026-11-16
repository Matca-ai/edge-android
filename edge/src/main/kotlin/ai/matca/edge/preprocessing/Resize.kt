package ai.matca.edge.preprocessing

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Bilinear resampling as Pillow does it: a triangle filter whose support grows with the shrink factor,
 * applied along each axis in turn with 8-bit rounding between the passes. Enlarging is plain bilinear
 * sampling at pixel centres; shrinking averages, which is what makes a phone agree with the trainer.
 */
internal fun resizeBilinear(image: RgbImage, width: Int, height: Int): RgbImage {
    val horizontal = resampleAxis(image, width, image.height, horizontal = true)
    return resampleAxis(horizontal, width, height, horizontal = false)
}

private class Taps(val first: Int, val weights: DoubleArray)

private fun resampleAxis(image: RgbImage, outWidth: Int, outHeight: Int, horizontal: Boolean): RgbImage {
    val inSize = if (horizontal) image.width else image.height
    val outSize = if (horizontal) outWidth else outHeight
    val taps = Array(outSize) { triangleTaps(it, inSize, outSize) }
    val out = ByteArray(outWidth * outHeight * RgbImage.CHANNELS)
    var at = 0
    for (y in 0 until outHeight) {
        for (x in 0 until outWidth) {
            val tap = taps[if (horizontal) x else y]
            for (channel in 0 until RgbImage.CHANNELS) {
                var sum = 0.0
                for (offset in tap.weights.indices) {
                    val position = tap.first + offset
                    val value = if (horizontal) image.channel(position, y, channel) else image.channel(x, position, channel)
                    sum += tap.weights[offset] * value
                }
                out[at++] = sum.roundToInt().coerceIn(0, 255).toByte()
            }
        }
    }
    return RgbImage(outWidth, outHeight, out)
}

private fun triangleTaps(outIndex: Int, inSize: Int, outSize: Int): Taps {
    val scale = inSize.toDouble() / outSize
    val filterScale = maxOf(scale, 1.0)
    val support = filterScale
    val centre = (outIndex + 0.5) * scale
    val first = maxOf((centre - support + 0.5).toInt(), 0)
    val last = minOf((centre + support + 0.5).toInt(), inSize)
    val weights = DoubleArray(last - first) { triangle((it + first - centre + 0.5) / filterScale) }
    val total = weights.sum()
    for (index in weights.indices) weights[index] /= total
    return Taps(first, weights)
}

private fun triangle(x: Double): Double = if (abs(x) < 1.0) 1.0 - abs(x) else 0.0
