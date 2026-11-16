package ai.matca.edge

import ai.matca.edge.preprocessing.PreprocessingSteps
import ai.matca.edge.preprocessing.RgbImage
import android.graphics.Bitmap
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.abs
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MODEL_INPUT_SIZE = 224

/**
 * What the JVM tests cannot reach: LiteRT's native engine with a real model, and `BitmapFactory` and `Bitmap`.
 * The expected values are the trainer's own (`matca_trainer/tiny_edge_model.py`, `edge-fixtures/index.json`).
 */
internal class PlatformAdaptersTest {
    private val context = InstrumentationRegistry.getInstrumentation().context

    @Test
    fun everyPictureGetsTheTrainersAnswerFromTheRealModel() {
        val expected = JSONObject(assetText("tiny-model/expected.json"))
        val classes = expected.getJSONArray("classes")
        val answers = expected.getJSONObject("answers")
        LiteRtModelLoader().load(tinyModelFile()).use { runner ->
            for (picture in answers.keys()) {
                val scores = runner.run(prepared(PreprocessingSteps(), Frame.fromJpeg(asset(picture))).toFloatArray())
                assertEquals(picture, classes.length(), scores.size)
                val best = scores.indices.maxBy { scores[it] }
                val answer = answers.getJSONObject(picture)
                assertEquals(picture, answer.getString("label"), classes.getString(best))
                assertEquals(picture, answer.getDouble("confidence"), scores[best].toDouble(), expected.getDouble("tolerance"))
            }
        }
    }

    @Test
    fun aSidewaysJpegComesOutUprightThroughTheLibrarysOwnSteps() {
        val frame = Frame.fromJpeg(asset("cap-sideways.jpg"))
        assertEquals(6, frame.orientation)
        assertEquals(180 to 320, frame.image.width to frame.image.height)

        val limit = JSONObject(assetText("index.json")).getJSONObject("tolerance").getDouble("mean_abs")
        val upright = BitmapJpegDecoder().decode(asset("cap-sideways.default.png"))
        val difference = meanAbsoluteDifference(prepared(PreprocessingSteps(), frame), upright)
        assertTrue("the picture differs by $difference, over $limit", difference <= limit)
        assertTrue(
            "the fixture must tell an upright picture from a sideways one",
            meanAbsoluteDifference(prepared(PreprocessingSteps(fixOrientation = false), frame), upright) > limit,
        )
    }

    @Test
    fun aBitmapBecomesAnUprightFrameWithEveryPixelInPlace() {
        val red = 0xFFFF0000.toInt()
        val green = 0xFF00FF00.toInt()
        val blue = 0xFF0000FF.toInt()
        val white = 0xFFFFFFFF.toInt()
        val bitmap = Bitmap.createBitmap(intArrayOf(red, green, blue, white, red, green), 3, 2, Bitmap.Config.ARGB_8888)

        val frame = bitmap.toFrame()

        assertEquals(1, frame.orientation)
        assertEquals(3 to 2, frame.image.width to frame.image.height)
        val rgb = frame.image.pixels.map { it.toInt() and 0xFF }
        assertEquals(listOf(255, 0, 0, 0, 255, 0, 0, 0, 255, 255, 255, 255, 255, 0, 0, 0, 255, 0), rgb)
    }

    @Test
    fun aHardwareBitmapBecomesAFrameToo() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val software = Bitmap.createBitmap(intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt()), 2, 1, Bitmap.Config.ARGB_8888)
        val hardware = checkNotNull(software.copy(Bitmap.Config.HARDWARE, false))

        val rgb = hardware.toFrame().image.pixels.map { it.toInt() and 0xFF }

        assertEquals(listOf(255, 0, 0, 0, 255, 0), rgb)
    }

    @Test
    fun theSystemsExitRecordsAreReadFromAndroidElevenAndNoneBefore() {
        val exits = ApplicationExitHistory(context).exits()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) assertEquals(emptyList<ProcessExit>(), exits)
        assertTrue(exits.all { it.timestampMillis >= 0 })
    }

    @Test
    fun bytesThatAreNoPictureAreAnEdgeError() {
        assertThrows(EdgeError.UndecodablePicture::class.java) { Frame.fromJpeg(ByteArray(4)) }
    }

    private fun prepared(steps: PreprocessingSteps, frame: Frame): RgbImage =
        steps.prepare(frame.image, frame.orientation, MODEL_INPUT_SIZE, MODEL_INPUT_SIZE)

    private fun meanAbsoluteDifference(first: RgbImage, second: RgbImage): Double =
        first.pixels.indices.sumOf { abs((first.pixels[it].toInt() and 0xFF) - (second.pixels[it].toInt() and 0xFF)) }
            .toDouble() / first.pixels.size

    /** LiteRT maps a model from a file, and an asset is not one. */
    private fun tinyModelFile(): File =
        File(context.cacheDir, "tiny-model.tflite").apply { writeBytes(asset("tiny-model/android/model.tflite")) }

    private fun asset(path: String): ByteArray = context.assets.open(path).use { it.readBytes() }

    private fun assetText(path: String): String = String(asset(path), Charsets.UTF_8)
}
