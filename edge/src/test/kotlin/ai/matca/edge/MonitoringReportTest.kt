package ai.matca.edge

import ai.matca.edge.preprocessing.RgbImage
import java.time.Instant
import java.util.Locale
import kotlin.system.measureNanoTime
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val NANOS_PER_MILLI = 1_000_000L

// One value inside each of the 12 steps of the contract's section 11, for confidence and for milliseconds.
private val CONFIDENCE_IN_STEP = listOf(0.25, 0.55, 0.65, 0.75, 0.825, 0.875, 0.91, 0.935, 0.96, 0.975, 0.985, 0.995)
private val MILLIS_IN_STEP = listOf(5L, 15, 25, 40, 60, 90, 120, 175, 250, 400, 750, 1500)
private val UNDECODABLE = byteArrayOf(0)

/** One planned classification: what the model answers and how long reading the picture and the model take. */
private data class Planned(val label: String, val confidence: Double, val preprocessingMillis: Long, val modelMillis: Long)

/** Section 10, test 7: the first report of a scripted day is `sdks/test-fixtures/report-format-2.json`, and counting is cheap. */
internal class MonitoringReportTest : ClassifierTestBase() {
    private val dayOfTheFixture = Instant.parse("2026-11-03T08:00:00Z").toEpochMilli()
    private var now = dayOfTheFixture
    private var monotonic = 0L
    private val plan = ArrayDeque<Planned>()
    private val unconfined = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    /** The script's model: the planned answer, after the planned time on the monotonic clock. */
    private val scriptedEngine = ModelLoader {
        object : ModelRunner {
            override fun run(input: FloatArray): FloatArray {
                val next = plan.removeFirst()
                monotonic += next.modelMillis * NANOS_PER_MILLI
                val top = next.confidence.toFloat()
                return if (next.label == "With Cap") floatArrayOf(top, 0f) else floatArrayOf(0f, top)
            }

            override fun close() = Unit
        }
    }

    /** Reading a JPEG is where the script spends the planned preprocessing time. */
    private val scriptedDecoder = JpegDecoder { jpeg ->
        if (jpeg.contentEquals(UNDECODABLE)) throw EdgeError.UndecodablePicture()
        monotonic += plan.first().preprocessingMillis * NANOS_PER_MILLI
        RgbImage(8, 8, ByteArray(8 * 8 * RgbImage.CHANNELS) { 100 })
    }

    @Test
    fun aScriptedDayGivesTheFixtureReport() = runBlocking {
        val expected = fixtureJson("report-format-2.json")
        // The edge takes no report all day, so the day's first report carries every count of the script.
        delivery.reportStatus = 503
        plan += plannedAnswers(expected)
        val watchedTimeouts = expected.getJSONObject("outcomes").getInt("no_stable_picture")
        val sessions = expected.getInt("sessions")
        val answeredByJpeg = plan.size - watchedTimeouts
        val first = classifier(
            engine = scriptedEngine, decoder = scriptedDecoder, clock = { now }, scope = unconfined,
            workDispatcher = Dispatchers.Unconfined, monotonicNanos = { monotonic },
        )
        delivery.manifest = root.signed(manifestText(root.publicKeyBase64, sequence = 7, release = 3))
        first.checkForUpdates()
        watchUntilEachStopCounts(first, watchedTimeouts, expected.getJSONObject("outcomes").getInt("black_screen"))
        assertThrows(EdgeError.UndecodablePicture::class.java) { runBlocking { first.classify(UNDECODABLE) } }
        for (session in 0 until sessions) {
            val classifier = if (session == 0) first else session()
            repeat(answeredByJpeg / sessions + if (session < answeredByJpeg % sessions) 1 else 0) {
                classifier.classify(byteArrayOf(1))
            }
            classifier.checkForUpdates()
        }
        assertEquals(0, plan.size)

        // The next day's session counts on its own day, and its check is the first the edge takes a report after.
        now += 1.days.inWholeMilliseconds
        delivery.reportStatus = 204
        session().checkForUpdates()

        val sent = delivery.reports.single { it.status == 204 && it.day == expected.getString("day") }
        assertEquals(3L, sent.release)
        assertEquals(comparable(expected), comparable(sent.report))
    }

    /** The steps are the trainer's own, kept once in the shared fixtures (`sdks/test-fixtures/steps.json`). */
    @Test
    fun theStepsAreTheSharedOnes() {
        val steps = fixtureJson("steps.json")
        val shared = { name: String -> steps.getJSONArray(name).let { values -> List(values.length()) { values.getDouble(it) } } }
        assertEquals(shared("confidence_thresholds"), CONFIDENCE_STEPS.toList())
        assertEquals(shared("millisecond_thresholds"), SPEED_STEPS_MILLIS.map { it.toDouble() })
        assertEquals(steps.getDouble("confidence_gate"), CONFIDENCE_GATE, 0.0)
    }

    @Test
    fun aHundredThousandCountsTakeUnderFiftyMilliseconds() {
        val store = ModelStore(folder.newFolder())
        val monitoring = Monitoring(true, "cap-detection", store, delivery, SYSTEM_VERSION, ProcessExitHistory.NONE) { now }
        val answers = listOf(
            Answer("With Cap", 0.97, emptyList(), 3),
            Answer("Without Cap", 0.62, emptyList(), 3),
            Answer("With Cap", 0.999, emptyList(), 3),
        )
        fun countAHundredThousand() = repeat(100_000) { monitoring.countAnswer(3, answers[it % answers.size], it * 1_000L, it * 7_000L) }
        // The first rounds load and compile the counting code, which a phone does once per process.
        repeat(5) { countAHundredThousand() }
        val elapsedMillis = measureNanoTime { countAHundredThousand() } / NANOS_PER_MILLI.toDouble()
        println("100,000 counts took %.2f ms".format(Locale.ROOT, elapsedMillis))
        assertTrue("100,000 counts took $elapsedMillis ms", elapsedMillis < 50)
    }

    /** A restart over the same store, edge and script: one more session. */
    private fun session() = Classifier(
        "cap-detection", configuration(), ModelStore(storeRoot), delivery, scriptedEngine, scriptedDecoder,
        clock = { now }, scope = unconfined, workDispatcher = Dispatchers.Unconfined, systemVersion = SYSTEM_VERSION,
        monotonicNanos = { monotonic },
    )

    /**
     * Watches that wait for a stable picture on a dim feed, each answering the one frame its timeout lets through, and
     * watches that find a black screen first and answer nothing.
     */
    private suspend fun watchUntilEachStopCounts(classifier: Classifier, timeouts: Int, blackScreens: Int) {
        repeat(timeouts) {
            classifier.watch {
                waitForStablePicture = true
                ignoreFirstAnswers = 0
            }.states(frames(grey = 60, until = 4_000)).toList()
        }
        repeat(blackScreens) {
            classifier.watch {
                waitForStablePicture = true
                detectBlackScreen = true
            }.states(frames(grey = 3, until = 3_250)).toList()
        }
    }

    private fun frames(grey: Int, until: Long): Flow<Frame> = flow {
        for (time in 0..until step 250) emit(solidFrame(grey, time = time))
    }

    /** The fixture's steps spread back into single answers; the first ones, the watches' answers, read in under 10 ms. */
    private fun plannedAnswers(report: JSONObject): List<Planned> {
        val confidences = spread(report.getJSONArray("confidence"), CONFIDENCE_IN_STEP)
        val labelCounts = report.getJSONObject("labels")
        val labels = labelCounts.keys().asSequence().flatMap { label -> List(labelCounts.getInt(label)) { label } }.toList()
        val speed = report.getJSONObject("speed_ms")
        val preprocessing = spread(speed.getJSONArray("preprocessing"), MILLIS_IN_STEP)
        val model = spread(speed.getJSONArray("model"), MILLIS_IN_STEP)
        return confidences.indices.map { Planned(labels[it], confidences[it], preprocessing[it], model[it]) }
    }

    private fun <T> spread(countPerStep: JSONArray, valueInStep: List<T>): List<T> =
        (0 until countPerStep.length()).flatMap { step -> List(countPerStep.getInt(step)) { valueInStep[step] } }
}
