package ai.matca.edge

import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Section 10, test 5: the app's own steps, each with the numbers of section 7. */
internal class WatchTest {
    private val frameMillis = 250L

    private var classified = 0

    /** Answers scripted per classification, "With Cap" once the script runs out; every classification is counted. */
    private fun watch(answers: List<Answer>, configure: Watch.Builder.() -> Unit = {}): Watch {
        val queue = ArrayDeque(answers)
        val classify: suspend (Frame) -> Answer = {
            classified++
            queue.removeFirstOrNull() ?: answer("With Cap")
        }
        return Watch(classify, Watch.Builder().apply(configure).build())
    }

    private fun answer(label: String, confidence: Double = 0.99) =
        Answer(label, confidence, listOf(Score(label, confidence)), 3)

    /** `count` frames of one grey, their times `stepMillis` apart, the first at zero. */
    private fun frames(count: Int, grey: Int = 200, stepMillis: Long = frameMillis): Flow<Frame> = flow {
        repeat(count) { emit(solidFrame(grey, time = it * stepMillis)) }
    }

    @Test
    fun framesInsideTheIntervalAreDroppedByFrameTime() = runBlocking {
        watch(emptyList()) { ignoreFirstAnswers = 0 }.states(frames(10, stepMillis = 100)).toList()
        assertEquals(4, classified)
    }

    @Test
    fun theWarmUpDropsTheFirstAnswersAndSaysReadyOnTheLastOfThem() = runBlocking {
        assertEquals(emptyList<WatchState>(), watch(emptyList()).states(frames(3)).toList())
        assertEquals(listOf<WatchState>(WatchState.Ready), watch(emptyList()).states(frames(4)).toList())
        val states = watch(emptyList()).states(frames(8)).toList()
        assertEquals(listOf<WatchState>(WatchState.Ready, WatchState.Label("With Cap", 0.99, 3)), states)
    }

    @Test
    fun agreementNeedsFourAtTheStartAndThreeAfterAndReportsOnlyAChange() = runBlocking {
        val labels = List(3) { "With Cap" } + "Without Cap" + List(5) { "With Cap" } + List(4) { "Without Cap" }
        val states = watch(labels.map { answer(it) }) { ignoreFirstAnswers = 0 }.states(frames(labels.size)).toList()
        assertEquals(
            listOf(WatchState.Ready, WatchState.Label("With Cap", 0.99, 3), WatchState.Label("Without Cap", 0.99, 3)),
            states,
        )
    }

    @Test
    fun answersBelowTheConfidenceGateAreDropped() = runBlocking {
        val answers = List(4) { answer("With Cap", 0.94) } + answer("With Cap", 0.96)
        val states = watch(answers) { ignoreFirstAnswers = 0 }.states(frames(answers.size)).toList()
        assertEquals(listOf<WatchState>(WatchState.Ready), states)
    }

    @Test
    fun aFrameWithNoAnswerIsDroppedLoggedOnceAndTheWatchGoesOn() = runBlocking {
        var unanswered = 3
        val classify: suspend (Frame) -> Answer = {
            if (unanswered-- > 0) throw EdgeError.NoModel()
            answer("With Cap")
        }
        var states = emptyList<WatchState>()
        val settings = Watch.Builder().apply {
            ignoreFirstAnswers = 0
            agreementAtStart = 1
        }.build()
        val messages = loggedDuring { runBlocking { states = Watch(classify, settings).states(frames(4)).toList() } }
        assertEquals(listOf(WatchState.Ready, WatchState.Label("With Cap", 0.99, 3)), states)
        assertEquals(1, messages.count { it.startsWith("a frame was dropped") })
    }

    @Test
    fun theStabilityDetectorHoldsClassificationUntilTwelveSteadyFrames() = runBlocking {
        val watch = watch(emptyList()) {
            waitForStablePicture = true
            ignoreFirstAnswers = 0
        }
        watch.states(frames(14, grey = 200)).toList()
        assertEquals(2, classified)
    }

    @Test
    fun theWaitForASteadyPictureSeesEveryFrameNotOnlyThePacedOnes() = runBlocking {
        // A camera at 15 frames a second: the picture settles on its 13th frame (800 ms), which is answered at once;
        // the next answer would be due 250 ms later, after the last frame here.
        val watch = watch(emptyList()) {
            waitForStablePicture = true
            ignoreFirstAnswers = 0
        }
        watch.states(frames(15, grey = 200, stepMillis = 1000L / 15)).toList()
        assertEquals(1, classified)
    }

    @Test
    fun theStabilityDetectorGivesUpAfterFourSeconds() = runBlocking {
        val watch = watch(emptyList()) {
            waitForStablePicture = true
            ignoreFirstAnswers = 0
        }
        watch.states(frames(20, grey = 60)).toList()
        assertEquals(4, classified)
    }

    @Test
    fun theStabilityDetectorNumbers() {
        val detector = FrameStabilityDetector()
        val bright = FloatArray(1024) { 200f }
        val dim = FloatArray(1024) { 129f }
        val drifted = FloatArray(1024) { 202f }
        val stepMillis = 100L
        repeat(12) { assertTrue(!detector.observe(dim, it * stepMillis)) }
        val steady = (0 until 13).map { detector.observe(bright, (12 + it) * stepMillis) }
        assertEquals(List(12) { false } + true, steady)
        assertTrue(!detector.observe(drifted, 25 * stepMillis))
    }

    @Test
    fun theBlackScreenStopsTheWatchAfterTheGrace() = runBlocking {
        val states = watch(emptyList()) { detectBlackScreen = true }.states(frames(30, grey = 3)).toList()
        assertEquals(WatchState.BlackScreen, states.last())
        assertEquals(1, states.count { it == WatchState.BlackScreen })
        val detector = BlackScreenDetector()
        val dark = FloatArray(1024) { 5f }
        // Frames at 250 ms: the first 8 fall in the 2 s grace, then six dark frames in a row are needed.
        val black = (0 until 14).map { detector.observe(dark, it * frameMillis) }
        assertEquals(List(13) { false } + true, black)
    }

    @Test
    fun aDimButNotBlackScreenNeverCountsAsBlack() = runBlocking {
        val states = watch(emptyList()) { detectBlackScreen = true }.states(frames(30, grey = 6)).toList()
        assertTrue(WatchState.BlackScreen !in states)
    }

    @Test
    fun theThumbnailKeepsLumaAsFloatSoAFractionOverTheLimitIsNotDark() {
        // Rec. 601 of (6, 5, 5) is 5.299: truncated to a byte it would read as dark.
        val pixels = ByteArray(64 * 64 * 3) { if (it % 3 == 0) 6 else 5 }
        val image = ai.matca.edge.preprocessing.RgbImage(64, 64, pixels)
        val brightness = brightness(greyThumbnail(image))
        assertEquals(5.299, brightness, 0.001)
        val detector = BlackScreenDetector()
        assertFalse((0..20).map { detector.observe(greyThumbnail(image), it * frameMillis) }.any { it })
    }

    @Test
    fun aWaitThatEndsOnItsTimeoutAndABlackScreenAreToldToTheClassifierOnce() = runBlocking {
        val told = mutableListOf<String>()
        val events = object : WatchEvents {
            override fun stablePictureTimedOut() {
                told += "timed out"
            }

            override fun blackScreenEmitted() {
                told += "black screen"
            }
        }
        val classify: suspend (Frame) -> Answer = { answer("With Cap") }
        fun watch(configure: Watch.Builder.() -> Unit) = Watch(classify, Watch.Builder().apply(configure).build(), events)
        watch { waitForStablePicture = true }.states(frames(20, grey = 60)).toList()
        assertEquals(listOf("timed out"), told)
        told.clear()
        watch { waitForStablePicture = true }.states(frames(20, grey = 200)).toList()
        assertEquals(emptyList<String>(), told)
        watch { detectBlackScreen = true }.states(frames(30, grey = 3)).toList()
        assertEquals(listOf("black screen"), told)
    }

    @Test
    fun theDefaultsAreTheContracts() {
        val settings = Watch.Builder().build()
        assertEquals(250.milliseconds, settings.frameInterval)
        assertEquals(false, settings.waitForStablePicture)
        assertEquals(false, settings.detectBlackScreen)
        assertEquals(4, settings.ignoreFirstAnswers)
        assertEquals(0.95, settings.confidenceGate, 0.0)
        assertEquals(3, settings.agreement)
        assertEquals(4, settings.agreementAtStart)
    }
}
