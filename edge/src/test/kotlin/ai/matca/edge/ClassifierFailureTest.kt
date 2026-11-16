package ai.matca.edge

import ai.matca.edge.preprocessing.PreprocessingOverrides
import ai.matca.edge.preprocessing.StepOverride
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** What must hold when things go wrong: the current model stays, the reason reaches the app, nothing is left behind. */
internal class ClassifierFailureTest : ClassifierTestBase() {
    @Test
    fun aRestartKeepsTheReleaseWhenTheRootKeyHasChanged() = runBlocking {
        val classifier = classifier()
        offer(sequence = 7)
        classifier.checkForUpdates()
        val rotated = EdgeConfiguration("http://edge.test", "mdk_a_b", TestKey().publicKeyBase64)
        assertEquals("With Cap", restarted(configuration = rotated).classify(referenceJpeg).label)
    }

    @Test
    fun aFailedCheckThatCannotBeCountedStillThrowsItsOwnReason() = runBlocking {
        val edge = object : Delivery by delivery {
            override suspend fun countFailedCheck(model: String, release: Long) = throw EdgeError.Edge(0, "offline")
        }
        val classifier = classifier(edge = edge)
        offer(sequence = 7, release = 3)
        classifier.checkForUpdates()
        val wrongReference = reference.copy(expected = "Without Cap")
        delivery.manifest = root.signed(manifestText(root.publicKeyBase64, sequence = 8, release = 4, reference = listOf(wrongReference)))
        assertThrows(EdgeError.ReferenceCheckFailed::class.java) { runBlocking { classifier.checkForUpdates() } }
        assertEquals(3L, classifier.currentRelease)
        assertEquals(listOf("release-3-7"), releaseDirectories())
    }

    @Test
    fun anInstallThatCannotBeCountedIsStillInstalled() = runBlocking {
        val edge = object : Delivery by delivery {
            override suspend fun countInstall(model: String, release: Long) = throw EdgeError.Edge(0, "offline")
        }
        val classifier = classifier(edge = edge)
        offer(sequence = 7)
        classifier.checkForUpdates()
        assertEquals(3L, classifier.currentRelease)
    }

    @Test
    fun anExplicitCheckThrowsAnEdgeErrorWhateverItMeets() = runBlocking {
        var failure: Throwable = EdgeError.Edge(401, "wrong key")
        val edge = object : Delivery by delivery {
            override suspend fun manifest(model: String): SignedManifest = throw failure
        }
        val classifier = classifier(edge = edge)
        val edgeError = assertThrows(EdgeError.Edge::class.java) { runBlocking { classifier.checkForUpdates() } }
        assertEquals(401, edgeError.status)
        for (kind in listOf(IllegalStateException("secret text"), OutOfMemoryError("secret text"), StackOverflowError())) {
            failure = kind
            val stopped = assertThrows(EdgeError.UpdateStopped::class.java) { runBlocking { classifier.checkForUpdates() } }
            assertFalse(stopped.message!!.contains("secret"))
        }
    }

    @Test
    fun aDeepDocumentIsAFailureNotACrash() = runBlocking {
        val classifier = classifier()
        val deep = "{\"a\":".repeat(100_000) + "1" + "}".repeat(100_000)
        delivery.manifest = SignedManifest(deep, "c2ln")
        assertThrows(EdgeError::class.java) { runBlocking { classifier.checkForUpdates() } }
        delivery.manifest = SignedManifest("[".repeat(200_000), "c2ln")
        assertThrows(EdgeError::class.java) { runBlocking { classifier.checkForUpdates() } }
        assertNull(classifier.currentRelease)
    }

    @Test
    fun aModelNameMustBeADnsLabel() {
        val configuration = EdgeConfiguration("http://edge.test", "mdk_a_b", root.publicKeyBase64)
        fun create(name: String) = Classifier(name, configuration, ModelStore(folder.newFolder()), delivery, loader, ImageIoDecoder)
        for (valid in listOf("cap-detection", "a", "1a", "a".repeat(63))) create(valid)
        for (invalid in listOf("", "-a", "Cap", "a_b", "a/b", "../other", "a.b", "é", "a\n", "a".repeat(64))) {
            assertThrows(invalid, EdgeError.InvalidModelName::class.java) { create(invalid) }
        }
    }

    @Test
    fun aSwitchThatCannotBeWrittenLeavesThePreviousReleaseAndNothingOfTheNewOne() = runBlocking {
        val classifier = classifier()
        offer(sequence = 7, release = 3)
        classifier.checkForUpdates()
        offer(sequence = 8, release = 4)
        // The state file cannot be replaced while its temporary name is taken by a directory.
        File(storeRoot, "state.properties.tmp").mkdirs()
        assertThrows(EdgeError.UpdateStopped::class.java) { runBlocking { classifier.checkForUpdates() } }
        assertEquals(3L, classifier.currentRelease)
        assertEquals(1, loader.closed.size)
        assertEquals(listOf("release-3-7"), releaseDirectories())
        assertEquals(1, delivery.installs)
    }

    @Test
    fun aReleaseThatNoLongerLoadsComesBackFromTheEdge() = runBlocking {
        val classifier = classifier()
        offer(sequence = 7, release = 3)
        classifier.checkForUpdates()
        var loads = false
        val flaky = ModelLoader { file -> if (loads) loader.load(file) else throw IllegalStateException("no") }
        val restarted = restarted(flaky, delivery)
        assertThrows(EdgeError.NoModel::class.java) { runBlocking { restarted.classify(solidFrame(100)) } }
        loads = true
        restarted.checkForUpdates()
        assertEquals(3L, restarted.currentRelease)
        assertEquals("With Cap", restarted.classify(referenceJpeg).label)
    }

    @Test
    fun theSameReleaseUnderANewSequenceAlsoComesBackAndLeavesOneDirectory() = runBlocking {
        val classifier = classifier()
        offer(sequence = 7, release = 3)
        classifier.checkForUpdates()
        var loads = false
        val flaky = ModelLoader { file -> if (loads) loader.load(file) else throw IllegalStateException("no") }
        val restarted = restarted(flaky, delivery)
        assertThrows(EdgeError.NoModel::class.java) { runBlocking { restarted.classify(solidFrame(100)) } }
        loads = true
        offer(sequence = 8, release = 3)
        restarted.checkForUpdates()
        assertEquals(3L, restarted.currentRelease)
        assertEquals(listOf("release-3-8"), releaseDirectories())
    }

    @Test
    fun whatAKilledDownloadLeftIsRemovedAtTheNextSwitch() = runBlocking {
        val classifier = classifier()
        val left = File(storeRoot, "release-1-1").apply { mkdirs() }
        File(left, "model.tflite").writeBytes(modelBytes)
        offer(sequence = 7, release = 3)
        classifier.checkForUpdates()
        assertFalse(left.exists())
        assertEquals(listOf("release-3-7"), releaseDirectories())
    }

    @Test
    fun anUnreadableStateIsSetAsideAndRebuiltFromTheKeptManifest() = runBlocking {
        val classifier = classifier()
        offer(sequence = 7, release = 3)
        classifier.checkForUpdates()
        val garbage = "\u0000not a state"
        File(storeRoot, "state.properties").writeText(garbage)
        val restarted = restarted(edge = delivery)
        assertEquals("With Cap", restarted.classify(referenceJpeg).label)
        assertEquals(3L, restarted.currentRelease)
        assertEquals(garbage, File(storeRoot, "state.properties.unreadable").readText())
        assertEquals(7L, ModelStore(storeRoot).lastSequence())
        offer(sequence = 6, release = 2)
        restarted.checkForUpdates()
        assertEquals(3L, restarted.currentRelease)
    }

    @Test
    fun aNaNScoreFailsTheReferenceCheck() = runBlocking {
        val classifier = classifier(engine = FakeModelLoader(floatArrayOf(Float.NaN, 0.1f)))
        offer(sequence = 7)
        assertThrows(EdgeError.ReferenceCheckFailed::class.java) { runBlocking { classifier.checkForUpdates() } }
        assertNull(classifier.currentRelease)
        assertEquals(1, delivery.failedChecks)
    }

    @Test
    fun aModelThatThrowsOrAnswersTheWrongSizeFailsTheReferenceCheck() = runBlocking {
        val throwing = ModelLoader {
            object : ModelRunner {
                override fun run(input: FloatArray): FloatArray = throw IllegalStateException("the engine broke")

                override fun close() = Unit
            }
        }
        for (engine in listOf(throwing, FakeModelLoader(floatArrayOf(0.9f)))) {
            delivery.failedChecks = 0
            val classifier = classifier(engine = engine)
            offer(sequence = 7)
            assertThrows(EdgeError.ReferenceCheckFailed::class.java) { runBlocking { classifier.checkForUpdates() } }
            assertEquals(1, delivery.failedChecks)
        }
    }

    @Test
    fun aRunningModelThatThrowsIsAPredictionFailure() = runBlocking<Unit> {
        var answer: () -> FloatArray = { floatArrayOf(0.9f, 0.1f) }
        val engine = ModelLoader {
            object : ModelRunner {
                override fun run(input: FloatArray): FloatArray = answer()

                override fun close() = Unit
            }
        }
        val classifier = classifier(engine = engine)
        offer(sequence = 7)
        classifier.checkForUpdates()
        val brokenAnswers = listOf<() -> FloatArray>({ throw IllegalStateException("the engine broke") }, { floatArrayOf(0.9f) })
        for (broken in brokenAnswers) {
            answer = broken
            assertThrows(EdgeError.PredictionFailed::class.java) { runBlocking { classifier.classify(solidFrame(100)) } }
        }
    }

    @Test
    fun aNativeLibraryThatFailsToLoadIsAFailedLoad() = runBlocking<Unit> {
        var nativeLibraryLoads = true
        val engine = ModelLoader { modelFile ->
            if (!nativeLibraryLoads) throw UnsatisfiedLinkError("no libLiteRt.so")
            loader.load(modelFile)
        }
        val classifier = classifier(engine = engine)
        offer(sequence = 7, release = 3)
        classifier.checkForUpdates()
        nativeLibraryLoads = false
        offer(sequence = 8, release = 4)
        assertThrows(EdgeError.ModelLoadFailed::class.java) { runBlocking { classifier.checkForUpdates() } }
        assertEquals(3L, classifier.currentRelease)
        assertEquals(listOf("release-3-7"), releaseDirectories())
        val restarted = restarted(engine)
        assertThrows(EdgeError.NoModel::class.java) { runBlocking { restarted.classify(referenceJpeg) } }
    }

    @Test
    fun aReleaseWithNoTfliteDoesNotLoad() = runBlocking<Unit> {
        val classifier = classifier()
        delivery.files["android/other.bin"] = modelBytes
        delivery.manifest = root.signed(
            manifestText(root.publicKeyBase64, files = listOf(ManifestFile("other.bin", 5, sha256Hex(modelBytes)))),
        )
        assertThrows(EdgeError.ModelLoadFailed::class.java) { runBlocking { classifier.checkForUpdates() } }
    }

    @Test
    fun aNewSequenceForTheRunningReleaseOnlyMovesTheSequence() = runBlocking {
        val classifier = classifier()
        offer(sequence = 7, release = 3)
        classifier.checkForUpdates()
        val directory = store.current()!!.directory
        val requestsSoFar = delivery.fileRequests
        offer(sequence = 8, release = 3)
        classifier.checkForUpdates()
        assertEquals(3L, classifier.currentRelease)
        assertEquals(8L, store.lastSequence())
        assertEquals(directory, store.current()!!.directory)
        assertTrue(File(directory, "model.tflite").isFile)
        assertEquals(requestsSoFar, delivery.fileRequests)
        assertEquals(1, delivery.installs)
        assertEquals(1, loader.loaded.size)
        assertEquals(emptyList<File>(), loader.closed)
    }

    @Test
    fun eachOverriddenStepIsWarnedAboutOnce() = runBlocking {
        val classifier = classifier()
        offer(sequence = 7)
        classifier.checkForUpdates()
        val warnings = loggedDuring {
            runBlocking {
                classifier.preprocessing = PreprocessingOverrides(circleMask = StepOverride.On(true))
                repeat(2) { classifier.classify(solidFrame(100)) }
                classifier.preprocessing = PreprocessingOverrides(circleMask = StepOverride.On(true), centreSquareCrop = StepOverride.Off)
                repeat(2) { classifier.classify(solidFrame(100)) }
            }
        }.filter { it.startsWith("the app overrides") }
        assertEquals(
            listOf(
                "the app overrides circle_mask: pictures differ from what the model learned with",
                "the app overrides centre_square_crop: pictures differ from what the model learned with",
            ),
            warnings,
        )
    }

    @Test
    fun anOverrideOutOfRangeIsAnEdgeError() = runBlocking<Unit> {
        val classifier = classifier()
        offer(sequence = 7)
        classifier.checkForUpdates()
        classifier.preprocessing = PreprocessingOverrides(cropFactor = StepOverride.On(1.5))
        assertThrows(EdgeError.InvalidPreprocessing::class.java) { runBlocking { classifier.classify(solidFrame(100)) } }
    }

    @Test
    fun anUndecodablePictureIsAnEdgeError() = runBlocking<Unit> {
        val classifier = classifier()
        assertThrows(EdgeError.UndecodablePicture::class.java) { runBlocking { classifier.classify(ByteArray(4)) } }
    }
}
