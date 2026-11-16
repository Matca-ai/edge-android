package ai.matca.edge

import ai.matca.edge.preprocessing.RgbImage
import ai.matca.edge.preprocessing.resizeBilinear
import kotlin.math.abs

private const val THUMBNAIL_SIDE = 32
private const val CENTRE_SHARE = 2

/** A 32×32 grey thumbnail of the centre half of a frame: what the detectors compare and measure. */
internal fun greyThumbnail(image: RgbImage): FloatArray {
    val width = maxOf(1, image.width / CENTRE_SHARE)
    val height = maxOf(1, image.height / CENTRE_SHARE)
    val centre = image.cropped((image.width - width) / 2, (image.height - height) / 2, width, height)
    val small = resizeBilinear(centre, THUMBNAIL_SIDE, THUMBNAIL_SIDE)
    return FloatArray(THUMBNAIL_SIDE * THUMBNAIL_SIDE) { small.luma(it % THUMBNAIL_SIDE, it / THUMBNAIL_SIDE) }
}

internal fun brightness(thumbnail: FloatArray): Double = thumbnail.sumOf { it.toDouble() } / thumbnail.size

/**
 * Stable once `frames` in a row each differ little from the one before, are bright enough and drift
 * little across the window; or once the timeout has passed.
 */
internal class FrameStabilityDetector(
    private val frames: Int = 12,
    private val maxDifference: Double = 2.0,
    private val minBrightness: Double = 130.0,
    private val maxDrift: Double = 1.5,
    private val timeoutMillis: Long = 4_000,
) {
    private var previous: FloatArray? = null
    private var startedAt: Long? = null
    private val window = ArrayDeque<Double>()

    /** Whether the wait ended on the timeout rather than on a stable picture. */
    var timedOut: Boolean = false
        private set

    /** Whether the picture counts as stable after this frame. */
    fun observe(thumbnail: FloatArray, nowMillis: Long): Boolean {
        val started = startedAt ?: nowMillis.also { startedAt = it }
        if (nowMillis - started >= timeoutMillis) {
            timedOut = true
            return true
        }
        val last = previous
        previous = thumbnail
        val bright = brightness(thumbnail)
        val steady = last != null && meanAbsoluteDifference(thumbnail, last) <= maxDifference && bright >= minBrightness
        if (!steady) {
            window.clear()
            return false
        }
        window.addLast(bright)
        if (window.size > frames) window.removeFirst()
        return window.size == frames && window.max() - window.min() <= maxDrift
    }
}

/** After a grace period, `frames` dark frames in a row mean the device shows nothing. */
internal class BlackScreenDetector(
    private val frames: Int = 6,
    private val maxBrightness: Double = 5.0,
    private val graceMillis: Long = 2_000,
) {
    private var startedAt: Long? = null
    private var darkInARow = 0

    fun observe(thumbnail: FloatArray, nowMillis: Long): Boolean {
        val started = startedAt ?: nowMillis.also { startedAt = it }
        if (nowMillis - started < graceMillis) return false
        darkInARow = if (brightness(thumbnail) <= maxBrightness) darkInARow + 1 else 0
        return darkInARow >= frames
    }
}

private fun meanAbsoluteDifference(a: FloatArray, b: FloatArray): Double {
    var sum = 0.0
    for (index in a.indices) sum += abs(a[index] - b[index])
    return sum / a.size
}
