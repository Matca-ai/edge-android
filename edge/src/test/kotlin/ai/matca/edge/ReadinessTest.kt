package ai.matca.edge

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** A first launch before the model reached the phone: the classifier asks again soon, and an app can wait for it. */
internal class ReadinessTest : ClassifierTestBase() {
    @Test
    fun waitUntilReadyAnswersOnceTheEdgeHasARelease() = runBlocking {
        offer(sequence = 1)
        val classifier = classifier()
        assertEquals(3L, classifier.waitUntilReady(5.seconds))
        assertEquals("at once when the model is already on the phone", 3L, classifier.waitUntilReady(0.seconds))
    }

    @Test
    fun withoutAModelTheClassifierAsksAgainSoonNotAfterTheCheckInterval() = runBlocking {
        // Nothing released yet: the edge answers 404 until the release appears a moment later.
        val classifier = classifier(retryDelays = listOf(100.milliseconds))
        classifier.startInBackground()
        delay(300)
        assertNull(classifier.currentRelease)
        offer(sequence = 1)
        delay(500)
        assertEquals("taken by the automatic asks alone", 3L, classifier.currentRelease)
        classifier.close()
    }

    @Test
    fun aWaiterGetsTheReleaseTheAutomaticAsksBring() = runBlocking {
        val classifier = classifier(retryDelays = listOf(100.milliseconds))
        classifier.startInBackground()
        val waited = async { classifier.waitUntilReady(5.seconds) }
        delay(300)
        offer(sequence = 1)
        assertEquals(3L, waited.await())
        classifier.close()
    }

    @Test
    fun aWaitThatTimesOutSaysWhyNoModelCame() = runBlocking {
        val classifier = classifier(retryDelays = listOf(1.minutes))
        try {
            classifier.waitUntilReady(200.milliseconds)
            fail("expected the edge's refusal")
        } catch (error: EdgeError.Edge) {
            assertEquals(404, error.status)
        }
        assertTrue(classifier.currentRelease == null)
    }
}
