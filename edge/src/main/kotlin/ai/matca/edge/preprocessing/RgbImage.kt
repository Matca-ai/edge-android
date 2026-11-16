package ai.matca.edge.preprocessing

/** A picture as interleaved 8-bit RGB rows, the one form every picture step reads and writes. */
internal class RgbImage(val width: Int, val height: Int, val pixels: ByteArray) {
    init {
        require(width > 0 && height > 0) { "a picture needs at least one pixel" }
        require(pixels.size == width * height * CHANNELS) { "the pixel buffer does not match the size" }
    }

    fun channel(x: Int, y: Int, channel: Int): Int = pixels[(y * width + x) * CHANNELS + channel].toInt() and 0xFF

    /** Rec. 601 luma of one pixel, as the trainer computes it. */
    fun luma(x: Int, y: Int): Float =
        0.299f * channel(x, y, 0) + 0.587f * channel(x, y, 1) + 0.114f * channel(x, y, 2)

    /** A new picture whose pixel (x, y) is this picture's pixel at `source(x, y)`. */
    fun mapped(newWidth: Int, newHeight: Int, source: (x: Int, y: Int) -> Pair<Int, Int>): RgbImage {
        val out = ByteArray(newWidth * newHeight * CHANNELS)
        var at = 0
        for (y in 0 until newHeight) {
            for (x in 0 until newWidth) {
                val (sourceX, sourceY) = source(x, y)
                val from = (sourceY * width + sourceX) * CHANNELS
                out[at++] = pixels[from]
                out[at++] = pixels[from + 1]
                out[at++] = pixels[from + 2]
            }
        }
        return RgbImage(newWidth, newHeight, out)
    }

    fun cropped(left: Int, top: Int, newWidth: Int, newHeight: Int): RgbImage =
        mapped(newWidth, newHeight) { x, y -> (left + x) to (top + y) }

    /** The pixels as the model takes them: height, width, RGB, 0 to 255 as float32. */
    fun toFloatArray(): FloatArray = FloatArray(pixels.size) { (pixels[it].toInt() and 0xFF).toFloat() }

    companion object {
        const val CHANNELS = 3

        fun fromGrey(width: Int, height: Int, grey: ByteArray): RgbImage {
            val out = ByteArray(width * height * CHANNELS)
            for (index in grey.indices) {
                out[index * CHANNELS] = grey[index]
                out[index * CHANNELS + 1] = grey[index]
                out[index * CHANNELS + 2] = grey[index]
            }
            return RgbImage(width, height, out)
        }

        /** From packed 0xAARRGGBB pixels, the form `Bitmap.getPixels` and `BufferedImage.getRGB` share. */
        fun fromArgb(width: Int, height: Int, argb: IntArray): RgbImage {
            val out = ByteArray(width * height * CHANNELS)
            for (index in argb.indices) {
                val pixel = argb[index]
                out[index * CHANNELS] = (pixel shr 16).toByte()
                out[index * CHANNELS + 1] = (pixel shr 8).toByte()
                out[index * CHANNELS + 2] = pixel.toByte()
            }
            return RgbImage(width, height, out)
        }
    }
}
