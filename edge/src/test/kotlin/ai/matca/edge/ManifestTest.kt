package ai.matca.edge

import ai.matca.edge.preprocessing.Illumination
import ai.matca.edge.preprocessing.PreprocessingOverrides
import ai.matca.edge.preprocessing.PreprocessingSteps
import ai.matca.edge.preprocessing.StepOverride
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

internal class ManifestTest {
    private val text = fixtureJson("manifest.json").getString("manifest")

    @Test
    fun theFixtureManifestIsRead() {
        val manifest = Manifest.parse(text)
        assertEquals("cap-detection", manifest.model)
        assertEquals(7L, manifest.sequence)
        assertEquals(3L, manifest.release)
        assertEquals(100L, manifest.rolloutShare)
        assertEquals(listOf("With Cap", "Without Cap"), manifest.classes)
        assertEquals(224, manifest.inputWidth)
        val modelFile = ManifestFile("model.tflite", 5, "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08")
        assertEquals(listOf(modelFile), manifest.files)
        assertEquals(listOf(ReferencePicture("abc", 3, "abc", "With Cap", 0.5)), manifest.reference)
        assertEquals(PreprocessingSteps(), manifest.preprocessing)
        assertEquals(null, SigningBlock.of(text).certificate)
    }

    @Test
    fun optionalStepsAreRead() {
        val illumination = """{"sigma_percent":20,"percentile_low":1,"percentile_high":99,"smoothing":true}"""
        val illuminated = text
            .replace(""""illumination":null""", """"illumination":$illumination""")
            .replace(""""crop_factor":null""", """"crop_factor":0.8""")
        val steps = Manifest.parse(illuminated).preprocessing
        assertEquals(Illumination(20.0, 1.0, 99.0, true), steps.illumination)
        assertEquals(0.8, steps.cropFactor!!, 0.0)
    }

    @Test
    fun aPathThatCouldLeaveTheReleaseDirectoryIsRefused() {
        assertThrows(EdgeError.InvalidManifest::class.java) { Manifest.parse(text.replace("model.tflite", "../model.tflite")) }
        assertThrows(EdgeError.InvalidManifest::class.java) { Manifest.parse(text.replace("model.tflite", "/etc/passwd")) }
    }

    @Test
    fun aReferencePathThatCouldLeaveTheReleaseIsRefused() {
        assertThrows(EdgeError.InvalidManifest::class.java) { Manifest.parse(text.replace(""""path":"abc"""", """"path":"../abc"""")) }
    }

    @Test
    fun aTextThatIsNoManifestIsRefused() {
        assertThrows(EdgeError.InvalidManifest::class.java) { Manifest.parse("[1, 2") }
        assertThrows(EdgeError.InvalidManifest::class.java) { Manifest.parse(text.replace(""""sequence":7,""", "")) }
    }

    @Test
    fun aFieldTheLibraryDoesNotReadMayComeOrGo() {
        val without = text.replace(""""title":"Cap detection"""", """"added":{"x":1}""")
            .replace(""""channel":"test",""", "").replace(""""issued_at":"2026-11-01T15:00:00+00:00",""", "")
        assertEquals(7L, Manifest.parse(without).sequence)
    }

    @Test
    fun overridesSwitchStepsAndNameWhatChanged() {
        val steps = PreprocessingSteps()
        val (changed, names) = steps.overridden(PreprocessingOverrides(centreSquareCrop = StepOverride.Off, cropFactor = StepOverride.On(0.5)))
        assertEquals(PreprocessingSteps(centreSquareCrop = false, cropFactor = 0.5), changed)
        assertEquals(listOf("centre_square_crop", "crop_factor"), names)
        assertEquals(steps to emptyList<String>(), steps.overridden(PreprocessingOverrides(fixOrientation = StepOverride.On(true))))
    }
}
