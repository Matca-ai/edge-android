package ai.matca.edge

import java.io.File
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Section 10, tests 2, 4 and 6: the steps of taking a release, with a fake edge and a fake model. */
internal class ClassifierUpdateTest : ClassifierTestBase() {
    @Test
    fun aVerifiedReleaseIsTakenAndClassifies() = runBlocking {
        val classifier = classifier()
        assertThrows(EdgeError.NoModel::class.java) { runBlocking { classifier.classify(solidFrame(100)) } }
        offer(sequence = 7)
        classifier.checkForUpdates()
        assertEquals(3L, classifier.currentRelease)
        assertEquals(7L, store.lastSequence())
        assertEquals(1, delivery.installs)
        val answer = classifier.classify(referenceJpeg)
        assertEquals("With Cap", answer.label)
        assertEquals(0.9, answer.confidence, 1e-6)
        assertEquals(listOf("With Cap", "Without Cap"), answer.all.map { it.label })
        assertEquals(3L, answer.release)
    }

    @Test
    fun aSequenceNotAboveTheLastAcceptedChangesNothing() = runBlocking {
        val classifier = classifier()
        offer(sequence = 7)
        classifier.checkForUpdates()
        val requestsSoFar = delivery.fileRequests
        offer(sequence = 7, release = 4)
        classifier.checkForUpdates()
        offer(sequence = 6, release = 5)
        classifier.checkForUpdates()
        assertEquals(3L, classifier.currentRelease)
        assertEquals(requestsSoFar, delivery.fileRequests)
        assertEquals(1, delivery.installs)
    }

    @Test
    fun theSwitchMovesThePointerAndDropsThePreviousRelease() = runBlocking {
        val classifier = classifier()
        offer(sequence = 7, release = 3)
        classifier.checkForUpdates()
        val first = store.current()!!.directory
        offer(sequence = 8, release = 4)
        classifier.checkForUpdates()
        val second = store.current()!!.directory
        assertEquals(4L, classifier.currentRelease)
        assertEquals(8L, store.lastSequence())
        assertFalse(first.exists())
        assertTrue(File(second, "model.tflite").isFile)
        assertEquals(listOf(File(first, "model.tflite")), loader.closed)
        assertEquals(2, delivery.installs)
        assertEquals(4L, Manifest.parse(ModelStore(storeRoot).current()!!.manifestText).release)
    }

    @Test
    fun aFailedDownloadThrowsAndLeavesThePreviousReleaseInPlace() = runBlocking {
        val classifier = classifier()
        offer(sequence = 7, release = 3)
        classifier.checkForUpdates()
        offer(sequence = 8, release = 4, files = listOf(ManifestFile("model.tflite", 5, sha256Hex("other".toByteArray()))))
        assertThrows(EdgeError.FileMismatch::class.java) { runBlocking { classifier.checkForUpdates() } }
        assertEquals(3L, classifier.currentRelease)
        assertEquals(7L, store.lastSequence())
        assertEquals(listOf("release-3-7"), releaseDirectories())
        assertEquals(1, delivery.installs)
        assertEquals("With Cap", classifier.classify(referenceJpeg).label)
    }

    @Test
    fun aFailedReferenceCheckIsCountedThrownAndLeavesThePreviousReleaseInPlace() = runBlocking {
        val classifier = classifier()
        offer(sequence = 7, release = 3)
        classifier.checkForUpdates()
        val wrongReference = reference.copy(expected = "Without Cap")
        delivery.manifest = root.signed(manifestText(root.publicKeyBase64, sequence = 8, release = 4, reference = listOf(wrongReference)))
        assertThrows(EdgeError.ReferenceCheckFailed::class.java) { runBlocking { classifier.checkForUpdates() } }
        assertEquals(3L, classifier.currentRelease)
        assertEquals(1, delivery.failedChecks)
        assertEquals(1, delivery.installs)
        assertEquals(listOf("release-3-7"), releaseDirectories())
    }

    @Test
    fun aReferencePictureThatIsNotTheListedOneStopsTheRelease() = runBlocking {
        val classifier = classifier()
        delivery.manifest = root.signed(
            manifestText(root.publicKeyBase64, reference = listOf(reference.copy(sha256 = sha256Hex("other".toByteArray())))),
        )
        assertThrows(EdgeError.FileMismatch::class.java) { runBlocking { classifier.checkForUpdates() } }
        assertNull(classifier.currentRelease)
        assertEquals(0, delivery.failedChecks)
    }

    @Test
    fun theRolloutShareDecidesByTheDeviceNumber() = runBlocking {
        val classifier = classifier()
        val number = store.rolloutNumber()
        assertEquals(number, ModelStore(storeRoot).rolloutNumber())
        offer(sequence = 7, release = 3, rolloutShare = number)
        classifier.checkForUpdates()
        assertNull(classifier.currentRelease)
        assertEquals(7L, store.lastSequence())
        offer(sequence = 8, release = 4, rolloutShare = number + 1)
        classifier.checkForUpdates()
        assertEquals(4L, classifier.currentRelease)
    }

    @Test
    fun aManifestThisLibraryCannotUseIsRefusedAndNothingIsRemembered() = runBlocking {
        val classifier = classifier(configuration(expectedLabels = listOf("With Cap")))
        val refusals = listOf(
            manifestText(root.publicKeyBase64, model = "other-model"),
            manifestText(root.publicKeyBase64, format = 2),
            manifestText(root.publicKeyBase64, requires = listOf("fix_orientation", "depth_map")),
            manifestText(root.publicKeyBase64, classes = listOf("Without Cap")),
        )
        for (text in refusals) {
            delivery.manifest = root.signed(text)
            assertThrows(text, EdgeError.Refused::class.java) { runBlocking { classifier.checkForUpdates() } }
        }
        assertNull(classifier.currentRelease)
        assertEquals(0L, store.lastSequence())
    }

    @Test
    fun aReleaseForTheOtherPlatformStopsQuietly() = runBlocking {
        val classifier = classifier()
        delivery.manifest = root.signed(manifestText(root.publicKeyBase64, files = emptyList()))
        val messages = loggedDuring { runBlocking { classifier.checkForUpdates() } }
        assertEquals(listOf("release not taken: release 3 has no files for this platform"), messages)
        assertNull(classifier.currentRelease)
    }

    @Test
    fun anUnsignedOrUntrustedManifestIsRefused() = runBlocking {
        val classifier = classifier()
        val text = manifestText(root.publicKeyBase64, reference = listOf(reference))
        delivery.manifest = SignedManifest(text, TestKey().sign(text))
        assertThrows(EdgeError.Untrusted::class.java) { runBlocking { classifier.checkForUpdates() } }
        val stranger = TestKey()
        delivery.manifest = stranger.signed(manifestText(stranger.publicKeyBase64, reference = listOf(reference)))
        assertThrows(EdgeError.Untrusted::class.java) { runBlocking { classifier.checkForUpdates() } }
        assertNull(classifier.currentRelease)
        assertEquals(0, delivery.fileRequests)
    }

    @Test
    fun nothingButTheSigningBlockIsReadBeforeTheSignatureVerifies() = runBlocking<Unit> {
        val classifier = classifier()
        val text = """{"signing":{"certificate":null,"key":"${root.publicKeyBase64}"}}"""
        delivery.manifest = SignedManifest(text, TestKey().sign(text))
        assertThrows(EdgeError.Untrusted::class.java) { runBlocking { classifier.checkForUpdates() } }
    }

    @Test
    fun theFilesComeFromTheSignedReleaseWhateverTheEdgeSaysBesideIt() = runBlocking {
        val classifier = classifier()
        offer(sequence = 7, release = 3)
        val edgeAnswer = org.json.JSONObject()
            .put("release", 99)
            .put("manifest", delivery.manifest!!.text)
            .put("signature", delivery.manifest!!.signature)
        delivery.manifest = SignedManifest.parse(edgeAnswer.toString())
        classifier.checkForUpdates()
        assertEquals(setOf(3L), delivery.requestedReleases)
        assertEquals(3L, classifier.currentRelease)
    }

    @Test
    fun aBundledReleaseIsTakenBeforeAnyNetworkAndCountsNoInstall() = runBlocking {
        var releaseAtTheFirstAsk: Long? = null
        lateinit var classifier: Classifier
        val edge = object : Delivery by delivery {
            override suspend fun manifest(model: String): SignedManifest {
                releaseAtTheFirstAsk = classifier.currentRelease
                return delivery.manifest(model)
            }
        }
        classifier = classifier(configuration(bundledRelease = bundle()), edge = edge)
        classifier.start()
        assertEquals(2L, releaseAtTheFirstAsk)
        assertEquals(2L, classifier.currentRelease)
        assertEquals(0, delivery.installs)
        assertEquals(0, delivery.fileRequests)
    }

    @Test
    fun aBundledReleaseThatFailsItsCheckIsNotCounted() = runBlocking {
        val classifier = classifier(configuration(bundledRelease = bundle()), engine = FakeModelLoader(floatArrayOf(0.1f, 0.9f)))
        classifier.start()
        assertNull(classifier.currentRelease)
        assertEquals(0, delivery.failedChecks)
    }

    @Test
    fun aBundledReleaseIsTakenWithWiFiOnlyAndNoWiFi() = runBlocking {
        val classifier = classifier(configuration(bundledRelease = bundle(), wifiOnly = true), networkPolicy = { false })
        classifier.start()
        assertEquals(2L, classifier.currentRelease)
    }

    @Test
    fun wiFiOnlyOffWiFiAsksTheManifestAndFailsAtTheFirstFile() = runBlocking {
        val classifier = classifier(configuration(wifiOnly = true), networkPolicy = { false })
        offer(sequence = 7)
        val error = assertThrows(EdgeError.Edge::class.java) { runBlocking { classifier.checkForUpdates() } }
        assertEquals(0, error.status)
        assertEquals(0, delivery.fileRequests)
        assertEquals(0L, store.lastSequence())
        assertEquals(emptyList<String>(), releaseDirectories())
    }

    @Test
    fun aClassificationWaitsForTheBundledRelease() = runBlocking {
        val gate = CountDownLatch(1)
        val slow = ModelLoader { file ->
            gate.await()
            loader.load(file)
        }
        val classifier = classifier(configuration(bundledRelease = bundle()), engine = slow)
        classifier.startInBackground()
        Thread.sleep(100)
        val answer = async(Dispatchers.Default) { classifier.classify(solidFrame(100)) }
        Thread.sleep(100)
        assertFalse(answer.isCompleted)
        gate.countDown()
        assertEquals(2L, answer.await().release)
    }

    @Test
    fun theInstalledReleaseIsRestoredOnTheNextStart() = runBlocking {
        val classifier = classifier()
        offer(sequence = 7)
        classifier.checkForUpdates()
        val restarted = restarted()
        assertEquals("With Cap", restarted.classify(referenceJpeg).label)
        assertEquals(3L, restarted.currentRelease)
    }
}
