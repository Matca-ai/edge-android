package ai.matca.edge

import ai.matca.edge.preprocessing.Illumination
import ai.matca.edge.preprocessing.PreprocessingSteps
import ai.matca.edge.preprocessing.RgbImage
import ai.matca.edge.preprocessing.cropByFactor
import ai.matca.edge.preprocessing.exifOrientation
import ai.matca.edge.preprocessing.fixOrientation
import ai.matca.edge.preprocessing.illuminationNormalised
import ai.matca.edge.preprocessing.resizeBilinear
import java.util.Locale
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every fixture of the contract's section 6 through this library's steps, against the trainer's output:
 * the mean absolute difference per channel must stay within the tolerance `index.json` sets.
 */
internal class PreprocessingParityTest {
    private val index = fixtureJson("index.json")
    private val expected = index.getJSONObject("expected")
    private val tolerance = index.getJSONObject("tolerance")

    @Test
    fun everyFixtureIsWithinTolerance() {
        val differences = expected.keys().asSequence().toList().sorted().associateWith { name -> meanAbsoluteDifference(name) }
        differences.forEach { (name, difference) ->
            println("parity $name: mean abs difference ${"%.3f".format(Locale.ROOT, difference)}")
        }
        differences.forEach { (name, difference) ->
            val limit = tolerance.getDouble(if (name.endsWith(".illumination.png")) "mean_abs_illumination" else "mean_abs")
            assertTrue("$name differs by $difference, over $limit", difference <= limit)
        }
    }

    @Test
    fun exifOrientationIsReadFromTheFixtures() {
        assertEquals(6, exifOrientation(fixture("cap-sideways.jpg")))
        assertEquals(1, exifOrientation(fixture("cap-upright.jpg")))
        assertEquals(1, exifOrientation(byteArrayOf()))
        assertEquals(1, exifOrientation(fixture("cap-sideways.jpg").copyOf(40)))
    }

    private fun meanAbsoluteDifference(name: String): Double {
        val entry = expected.getJSONObject(name)
        val input = fixture("${entry.getString("input")}.jpg")
        val steps = stepsNamed(entry.getString("steps"))
        val produced = steps.prepare(ImageIoDecoder.decode(input), exifOrientation(input), 224, 224)
        val reference = ImageIoDecoder.decode(fixture(name))
        assertEquals(reference.width, produced.width)
        assertEquals(reference.height, produced.height)
        var sum = 0.0
        for (index in produced.pixels.indices) {
            sum += abs((produced.pixels[index].toInt() and 0xFF) - (reference.pixels[index].toInt() and 0xFF))
        }
        return sum / produced.pixels.size
    }

    private fun stepsNamed(name: String): PreprocessingSteps {
        val document = index.getJSONObject("steps").getJSONObject(name)
        return PreprocessingSteps(
            fixOrientation = document.getBoolean("fix_orientation"),
            centreSquareCrop = document.getBoolean("centre_square_crop"),
            cropFactor = if (document.isNull("crop_factor")) null else document.getDouble("crop_factor"),
            circleMask = document.getBoolean("circle_mask"),
            illumination = document.optJSONObject("illumination")?.let {
                Illumination(
                    sigmaPercent = it.getDouble("sigma_percent"),
                    percentileLow = it.getDouble("percentile_low"),
                    percentileHigh = it.getDouble("percentile_high"),
                    smoothing = it.getBoolean("smoothing"),
                )
            },
        )
    }

    @Test
    fun theResizeAveragesWhenItShrinksAndInterpolatesWhenItGrowsAsPillowDoes() {
        // Pillow 12.3.0's answers for the same rows, checked on 1 November 2026: a plain bilinear sample gives 0 and 255 here.
        fun row(vararg values: Int) = RgbImage.fromGrey(values.size, 1, ByteArray(values.size) { values[it].toByte() })
        fun greys(image: RgbImage) = List(image.width * image.height) { image.channel(it % image.width, it / image.width, 0) }
        assertEquals(listOf(36, 219), greys(resizeBilinear(row(0, 0, 255, 255), 2, 1)))
        val column = RgbImage.fromGrey(1, 4, byteArrayOf(0, 0, -1, -1))
        assertEquals(listOf(36, 219), greys(resizeBilinear(column, 1, 2)))
        assertEquals(listOf(0, 128, 255), greys(resizeBilinear(row(0, 255), 3, 1)))
    }

    @Test
    fun everyExifOrientationTurnsATwoByOnePictureUpright() {
        // A red pixel left of a green one; each orientation says where the red one went.
        val picture = RgbImage(2, 1, byteArrayOf(-1, 0, 0, 0, -1, 0))
        fun redAt(image: RgbImage) = (0 until image.height).flatMap { y -> (0 until image.width).map { x -> x to y } }
            .single { (x, y) -> image.channel(x, y, 0) == 255 }
        assertEquals(1 to 0, redAt(fixOrientation(picture, 2)))
        assertEquals(1 to 0, redAt(fixOrientation(picture, 3)))
        assertEquals(0 to 0, redAt(fixOrientation(picture, 4)))
        for (orientation in 5..8) assertEquals(1 to 2, fixOrientation(picture, orientation).let { it.width to it.height })
        assertEquals(0 to 0, redAt(fixOrientation(picture, 5)))
        assertEquals(0 to 0, redAt(fixOrientation(picture, 6)))
        assertEquals(0 to 1, redAt(fixOrientation(picture, 7)))
        assertEquals(0 to 1, redAt(fixOrientation(picture, 8)))
    }

    @Test
    fun aCropFactorSoSmallItLeavesNoPixelKeepsOne() {
        val cropped = cropByFactor(RgbImage(8, 8, ByteArray(8 * 8 * 3)), 0.001)
        assertEquals(1 to 1, cropped.width to cropped.height)
    }

    @Test
    fun aLittleEndianExifAndAnXmpSegmentBeforeItAreRead() {
        val exif = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0, 0) +
            byteArrayOf(0x49, 0x49, 0x2A, 0, 8, 0, 0, 0) +
            byteArrayOf(1, 0) +
            byteArrayOf(0x12, 0x01, 3, 0, 1, 0, 0, 0, 6, 0, 0, 0)
        val start = byteArrayOf(-1, 0xD8.toByte())
        fun segment(body: ByteArray) = byteArrayOf(-1, 0xE1.toByte(), 0, (body.size + 2).toByte()) + body
        assertEquals(6, exifOrientation(start + segment(exif)))
        assertEquals(6, exifOrientation(start + segment("http://ns.adobe.com/xap/".toByteArray()) + segment(exif)))
    }

    @Test
    fun aUniformPictureCannotBeIlluminationNormalised() {
        val flat = RgbImage(8, 8, ByteArray(8 * 8 * 3) { 100 })
        assertThrows(EdgeError.UniformPicture::class.java) { illuminationNormalised(flat, Illumination()) }
    }

    @Test
    fun theModelTakesChannelsLastFloatPixels() {
        val image = RgbImage(1, 1, byteArrayOf(255.toByte(), 0, 128.toByte()))
        assertEquals(listOf(255f, 0f, 128f), image.toFloatArray().toList())
    }
}
