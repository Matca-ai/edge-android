package ai.matca.edge

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile

public sealed interface WatchState {
    /** The warm-up answers are behind; what follows is reported. */
    public data object Ready : WatchState

    /** The device shows nothing; the watch has stopped. */
    public data object BlackScreen : WatchState

    public data class Label(val label: String, val confidence: Double, val release: Long) : WatchState
}

/** What a watch tells the classifier it watches for, so monitoring counts them (contract, section 11). */
internal interface WatchEvents {
    fun stablePictureTimedOut()

    fun blackScreenEmitted()

    companion object {
        val NONE = object : WatchEvents {
            override fun stablePictureTimedOut() = Unit

            override fun blackScreenEmitted() = Unit
        }
    }
}

/** A stream of frames turned into a stream of states with the app's own steps (contract, section 7). */
public class Watch internal constructor(
    private val classify: suspend (Frame) -> Answer,
    private val settings: Settings,
    private val events: WatchEvents = WatchEvents.NONE,
) {
    internal class Settings(
        val frameInterval: Duration,
        val waitForStablePicture: Boolean,
        val stablePictureFrames: Int,
        val stablePictureMinimumBrightness: Double,
        val stablePictureTimeout: Duration,
        val detectBlackScreen: Boolean,
        val ignoreFirstAnswers: Int,
        val confidenceGate: Double,
        val agreement: Int,
        val agreementAtStart: Int,
    )

    public class Builder {
        public var frameInterval: Duration = 250.milliseconds
        public var waitForStablePicture: Boolean = false

        /** The steady frames in a row that settle the picture. */
        public var stablePictureFrames: Int = 12

        /** The mean luma (0 to 255) a steady frame needs; 0 counts a dark picture as steady too. */
        public var stablePictureMinimumBrightness: Double = 130.0

        /** How long the wait lasts at most before the picture is taken as it is. */
        public var stablePictureTimeout: Duration = 4.seconds
        public var detectBlackScreen: Boolean = false
        public var ignoreFirstAnswers: Int = 4
        public var confidenceGate: Double = 0.95
        public var agreement: Int = 3
        public var agreementAtStart: Int = 4

        internal fun build() = Settings(
            frameInterval, waitForStablePicture, stablePictureFrames.coerceAtLeast(1), stablePictureMinimumBrightness,
            stablePictureTimeout.coerceAtLeast(Duration.ZERO), detectBlackScreen, ignoreFirstAnswers, confidenceGate, agreement,
            agreementAtStart,
        )
    }

    /** A cold flow: each collection is its own run, from the grace period to the first agreement. */
    public fun states(frames: Flow<Frame>): Flow<WatchState> = flow {
        val run = Run()
        emitAll(frames.transformWhile { frame -> run.take(frame, this) })
    }

    /** One watch's memory between frames; `take` answers whether the watch goes on. */
    private inner class Run {
        private val stability = FrameStabilityDetector(
            frames = settings.stablePictureFrames,
            minBrightness = settings.stablePictureMinimumBrightness,
            timeoutMillis = settings.stablePictureTimeout.inWholeMilliseconds,
        )
        private val blackScreen = BlackScreenDetector()
        private var stable = !settings.waitForStablePicture
        private var lastFrameAt: Long? = null
        private var answersSeen = 0
        private var ready = false
        private var streakLabel: String? = null
        private var streak = 0
        private var reported: String? = null
        private var lastFailure: String? = null

        /**
         * The wait for a steady picture sees every frame, so twelve steady frames take as long as the camera needs to
         * deliver them; the interval paces the classifications and the black-screen check only.
         */
        suspend fun take(frame: Frame, states: FlowCollector<WatchState>): Boolean {
            val due = due(frame.time)
            val waiting = !stable
            if (!due && !waiting) return true
            val thumbnail = if ((due && settings.detectBlackScreen) || waiting) greyThumbnail(frame.image) else null
            if (thumbnail != null && due && settings.detectBlackScreen && blackScreen.observe(thumbnail, frame.time)) {
                events.blackScreenEmitted()
                states.emit(WatchState.BlackScreen)
                return false
            }
            if (thumbnail != null && waiting) {
                stable = stability.observe(thumbnail, frame.time)
                if (stability.timedOut) events.stablePictureTimedOut()
                if (!stable) return true
                // The frame that settled the picture is answered at once, and the interval runs from it.
                lastFrameAt = frame.time
            }
            answered(frame)?.let { answer -> report(answer).forEach { states.emit(it) } }
            return true
        }

        private fun due(time: Long): Boolean {
            val last = lastFrameAt
            if (last != null && time - last < settings.frameInterval.inWholeMilliseconds) return false
            lastFrameAt = time
            return true
        }

        /** A frame that gets no answer is dropped and the watch goes on: a release may still be on its way. */
        private suspend fun answered(frame: Frame): Answer? = try {
            classify(frame)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val failure = describe(error)
            if (failure != lastFailure) log.warning("a frame was dropped: $failure")
            lastFailure = failure
            null
        }

        /** The warm-up, the gate and the agreement for one answer. */
        private fun report(answer: Answer): List<WatchState> {
            answersSeen++
            val states = mutableListOf<WatchState>()
            if (!ready && answersSeen >= settings.ignoreFirstAnswers) {
                ready = true
                states += WatchState.Ready
            }
            if (answersSeen <= settings.ignoreFirstAnswers || answer.confidence < settings.confidenceGate) return states
            streak = if (answer.label == streakLabel) streak + 1 else 1
            streakLabel = answer.label
            val needed = if (reported == null) settings.agreementAtStart else settings.agreement
            if (streak >= needed && answer.label != reported) {
                reported = answer.label
                states += WatchState.Label(answer.label, answer.confidence, answer.release)
            }
            return states
        }
    }
}
