package ai.matca.edge

import ai.matca.edge.preprocessing.RgbImage
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build

// The trainer's bound on a picture's pixels, so a crafted header cannot ask for the memory.
private const val MAX_PIXELS = 50_000_000L

/** The bitmap's pixels as the picture steps read them; a bitmap carries no EXIF, so it is taken as upright. */
public fun Bitmap.toFrame(time: Long = System.currentTimeMillis()): Frame = Frame(readablePixels(), time = time)

/** The JPEG's pixels and its EXIF orientation. Throws `EdgeError.UndecodablePicture` when they cannot be read. */
public fun Frame.Companion.fromJpeg(jpeg: ByteArray, time: Long = System.currentTimeMillis()): Frame =
    decoded(jpeg, BitmapJpegDecoder(), time)

public suspend fun Classifier.classify(bitmap: Bitmap): Answer = classifyPicture { bitmap.toFrame() }

// A hardware bitmap, what ImageDecoder and the image libraries hand out by default, refuses pixel access.
private fun Bitmap.readablePixels(): RgbImage {
    val isHardware = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && config == Bitmap.Config.HARDWARE
    val readable = if (isHardware) copy(Bitmap.Config.ARGB_8888, false) else this
    val argb = IntArray(width * height)
    readable.getPixels(argb, 0, width, 0, 0, width, height)
    return RgbImage.fromArgb(width, height, argb)
}

/** JPEG bytes through `BitmapFactory`, which ignores EXIF: the orientation is read apart and applied by the steps. */
internal class BitmapJpegDecoder : JpegDecoder {
    override fun decode(jpeg: ByteArray): RgbImage {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw EdgeError.UndecodablePicture()
        if (bounds.outWidth.toLong() * bounds.outHeight > MAX_PIXELS) throw EdgeError.UndecodablePicture()
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: throw EdgeError.UndecodablePicture()
        return try {
            bitmap.readablePixels()
        } finally {
            bitmap.recycle()
        }
    }
}
