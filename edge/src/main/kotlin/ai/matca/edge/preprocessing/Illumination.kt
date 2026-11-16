package ai.matca.edge.preprocessing

import kotlin.math.ceil
import kotlin.math.exp

import ai.matca.edge.EdgeError

public data class Illumination(
    val sigmaPercent: Double = 20.0,
    val percentileLow: Double = 1.0,
    val percentileHigh: Double = 99.0,
    val smoothing: Boolean = true,
)

// Below this spread between the two percentiles there is nothing to stretch, as in the trainer.
private const val FLAT_CONTRAST = 1e-6f

/**
 * Rec. 601 luma divided by a Gaussian estimate of the illumination, stretched between two percentiles to
 * 8-bit grey, optionally smoothed with [1 2 1]/4: the trainer's flat-field pipeline in float32 so the
 * phone's picture agrees with the training picture.
 */
internal fun illuminationNormalised(image: RgbImage, settings: Illumination): RgbImage {
    val width = image.width
    val height = image.height
    val luma = FloatArray(width * height) { image.luma(it % width, it / width) }
    val sigma = (settings.sigmaPercent / 100.0 * minOf(width, height)).toFloat()
    val field = separableFilter(luma, width, height, gaussianKernel(sigma))
    val flat = FloatArray(luma.size) { luma[it] / (field[it] + 1f) }
    val low = percentile(flat, settings.percentileLow)
    val high = percentile(flat, settings.percentileHigh)
    if (!(high - low > FLAT_CONTRAST)) throw EdgeError.UniformPicture()
    var stretched = FloatArray(flat.size) { ((flat[it] - low) / (high - low) * 255f).coerceIn(0f, 255f) }
    if (settings.smoothing) {
        stretched = separableFilter(stretched, width, height, floatArrayOf(0.25f, 0.5f, 0.25f))
    }
    return RgbImage.fromGrey(width, height, ByteArray(stretched.size) { stretched[it].toInt().toByte() })
}

internal fun gaussianKernel(sigma: Float): FloatArray {
    // Truncated at 4 sigma as SciPy does, leaving under 1e-4 of the mass outside the window.
    val radius = maxOf(1, ceil(4 * sigma).toInt())
    val kernel = FloatArray(2 * radius + 1) { exp(-((it - radius).toFloat() * (it - radius)) / (2 * sigma * sigma)) }
    val total = kernel.sum()
    for (index in kernel.indices) kernel[index] /= total
    return kernel
}

/** The kernel along each axis in turn, edges extended. */
internal fun separableFilter(plane: FloatArray, width: Int, height: Int, kernel: FloatArray): FloatArray {
    val radius = kernel.size / 2
    val rows = FloatArray(plane.size)
    for (y in 0 until height) {
        for (x in 0 until width) {
            var sum = 0f
            for (offset in kernel.indices) {
                val sourceX = (x + offset - radius).coerceIn(0, width - 1)
                sum += kernel[offset] * plane[y * width + sourceX]
            }
            rows[y * width + x] = sum
        }
    }
    val out = FloatArray(plane.size)
    for (y in 0 until height) {
        for (x in 0 until width) {
            var sum = 0f
            for (offset in kernel.indices) {
                val sourceY = (y + offset - radius).coerceIn(0, height - 1)
                sum += kernel[offset] * rows[sourceY * width + x]
            }
            out[y * width + x] = sum
        }
    }
    return out
}

/** The linear-interpolated percentile, numpy's default. */
internal fun percentile(values: FloatArray, percent: Double): Float {
    val sorted = values.copyOf()
    sorted.sort()
    val position = percent / 100.0 * (sorted.size - 1)
    val below = position.toInt()
    val above = minOf(below + 1, sorted.size - 1)
    val fraction = (position - below).toFloat()
    return sorted[below] + fraction * (sorted[above] - sorted[below])
}
