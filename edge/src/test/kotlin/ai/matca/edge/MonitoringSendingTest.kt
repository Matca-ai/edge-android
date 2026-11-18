package ai.matca.edge

import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Section 10, test 7: when the counts are sent, what drops a report or puts it back, how they are kept, and the switch. */
internal class MonitoringSendingTest : ClassifierTestBase() {
    private val dayZero = Instant.parse("2026-11-03T10:00:00Z").toEpochMilli()
    private var now = dayZero
    private val unconfined = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var online = true
    private var reportsTried = 0
    private var reportHeldBack: CompletableDeferred<Unit>? = null

    /**
     * The fake edge behind a connection the test can cut, where nothing gets an answer at all, and with a report it
     * can hold back on its way.
     */
    private val edge = object : Delivery by delivery {
        override suspend fun manifest(model: String): SignedManifest {
            if (!online) throw EdgeError.Edge(0, "offline")
            return delivery.manifest(model)
        }

        override suspend fun sendReport(model: String, release: Long, report: String) {
            reportsTried++
            if (!online) throw EdgeError.Edge(0, "offline")
            reportHeldBack?.await()
            delivery.sendReport(model, release, report)
        }
    }

    private fun monitored(configuration: EdgeConfiguration = configuration()) =
        classifier(configuration, edge = edge, clock = { now }, scope = unconfined, workDispatcher = Dispatchers.Unconfined)

    /** The classifier a restart makes over the same store, edge and clock. */
    private fun restartedNow(configuration: EdgeConfiguration = configuration()) = Classifier(
        "cap-detection", configuration, ModelStore(storeRoot), edge, loader, ImageIoDecoder,
        clock = { now }, scope = unconfined, workDispatcher = Dispatchers.Unconfined, systemVersion = SYSTEM_VERSION,
    )

    private fun dayText(daysFromZero: Int): String =
        Instant.ofEpochMilli(dayZero).atOffset(ZoneOffset.UTC).toLocalDate().plusDays(daysFromZero.toLong()).toString()

    private fun moveDays(days: Int) {
        now += days.days.inWholeMilliseconds
    }

    /**
     * A classifier with release 3 that answered `answers` pictures on day zero. Its first check sent the session it
     * counted, the first report of release 3's day zero; the answers came after and are still held.
     */
    private suspend fun classifierWithDayZeroHeld(answers: Int = 2): Classifier {
        val classifier = monitored()
        offer(sequence = 7)
        classifier.checkForUpdates()
        repeat(answers) { classifier.classify(solidFrame(100)) }
        return classifier
    }

    @Test
    fun todaysCountsGoAfterEachCheckAsTheCountsSinceTheLastReport() = runBlocking {
        val classifier = classifierWithDayZeroHeld()
        classifier.checkForUpdates()
        classifier.classify(solidFrame(100))
        classifier.checkForUpdates()
        classifier.checkForUpdates()
        assertEquals(listOf(3L, 3L, 3L), delivery.reports.map { it.release })
        assertEquals(List(3) { dayText(0) }, delivery.reports.map { it.day })
        assertEquals(listOf(0L, 2L, 1L), delivery.reports.map { answersIn(it.report) })
        assertEquals(listOf(1L, 0L, 0L), delivery.reports.map { it.report.getLong("sessions") })
    }

    @Test
    fun aReportIsFirstOncePerReleaseAndDayAlsoAfterARestart() = runBlocking {
        val classifier = classifierWithDayZeroHeld()
        classifier.checkForUpdates()
        val restarted = restartedNow()
        restarted.classify(solidFrame(100))
        restarted.checkForUpdates()
        moveDays(1)
        restarted.classify(solidFrame(100))
        restarted.checkForUpdates()
        offer(sequence = 8, release = 4)
        restarted.checkForUpdates()
        val expected = listOf(
            Triple(3L, dayText(0), true), Triple(3L, dayText(0), false), Triple(3L, dayText(0), false),
            Triple(3L, dayText(1), true), Triple(4L, dayText(1), true),
        )
        assertEquals(expected, delivery.reports.map { Triple(it.release, it.day, it.report.getBoolean("first")) })
    }

    @Test
    fun whatIsCountedWhileAReportIsOnItsWayGoesWithTheNextNeitherLostNorDoubled() = runBlocking {
        val classifier = classifierWithDayZeroHeld(answers = 2)
        delivery.reports.clear()
        val heldBack = CompletableDeferred<Unit>().also { reportHeldBack = it }
        classifier.checkForUpdates()
        repeat(3) { classifier.classify(solidFrame(100)) }
        classifier.checkForUpdates()
        assertEquals(emptyList<SentReport>(), delivery.reports)
        assertEquals(5L, keptAnswers())

        reportHeldBack = null
        heldBack.complete(Unit)
        assertEquals(listOf(2L), delivery.reports.map { answersIn(it.report) })
        assertEquals(3L, keptAnswers())
        classifier.checkForUpdates()
        assertEquals(listOf(2L, 3L), delivery.reports.map { answersIn(it.report) })
        assertEquals(0L, keptAnswers())
    }

    @Test
    fun aReportTheEdgeRefusesForGoodIsDroppedAndAnyOtherAnswerPutsItsCountsBack() = runBlocking {
        val dropped = listOf(400, 401, 404, 413)
        val putBack = listOf(429, 500, 503, 0)
        for (status in dropped + putBack) {
            delivery.reports.clear()
            delivery.reportStatus = status
            val classifier = classifierWithDayZeroHeld()
            delivery.reportStatus = 204
            classifier.checkForUpdates()
            classifier.classify(solidFrame(100))
            classifier.checkForUpdates()
            // The session went with the report the edge did not take: back with the next one, or dropped with it.
            // Until the edge takes one, every report of the day is still its first.
            val sessionsInTheNext = if (status in putBack) 1L else 0L
            val expected = listOf(Triple(status, 1L, true), Triple(204, sessionsInTheNext, true), Triple(204, 0L, false))
            val sent = delivery.reports.map { Triple(it.status, it.report.getLong("sessions"), it.report.getBoolean("first")) }
            assertEquals("status $status", expected, sent)
            assertEquals("status $status", listOf(0L, 2L, 1L), delivery.reports.map { answersIn(it.report) })
        }
    }

    @Test
    fun aReportPutBackEndsTheSendingUntilTheNextAndDaysGoOldestFirst() = runBlocking {
        val classifier = classifierWithDayZeroHeld()
        online = false
        moveDays(1)
        classifier.classify(solidFrame(100))
        moveDays(1)
        online = true
        delivery.reports.clear()
        delivery.reportStatus = 429
        classifier.checkForUpdates()
        assertEquals(listOf(dayText(0)), delivery.reports.map { it.day })
        delivery.reportStatus = 204
        classifier.checkForUpdates()
        assertEquals(listOf(dayText(0), dayText(0), dayText(1)), delivery.reports.map { it.day })
    }

    @Test
    fun aPhoneOffForEightDaysSendsTheLastSevenAndDropsTheEighthFromTheCountsAndTheSentDays() = runBlocking {
        val classifier = classifierWithDayZeroHeld()
        classifier.checkForUpdates()
        assertEquals(setOf(dayText(0)), kept().sent.map { utcDayText(it.day) }.toSet())
        classifier.classify(solidFrame(100))
        delivery.reports.clear()
        online = false
        repeat(7) {
            moveDays(1)
            classifier.classify(solidFrame(100))
        }
        moveDays(1)
        online = true
        classifier.checkForUpdates()
        assertEquals((1..7).map(::dayText), delivery.reports.map { it.day })
        assertEquals((1..7).map(::dayText).toSet(), kept().sent.map { utcDayText(it.day) }.toSet())
        assertEquals(emptySet<ReleaseDay>(), kept().days.keys)
        classifier.checkForUpdates()
        assertEquals(7, delivery.reports.size)
    }

    @Test
    fun atMostFourteenReportsGoPerSending() = runBlocking {
        val classifier = monitored()
        delivery.reportStatus = 429
        var sequence = 7L
        for (day in 0 until 7) {
            for (release in 3L..5L) {
                offer(sequence = sequence++, release = release)
                classifier.checkForUpdates()
                classifier.classify(solidFrame(100))
            }
            moveDays(1)
        }
        delivery.reports.clear()
        delivery.reportStatus = 204
        classifier.checkForUpdates()
        val expectedOrder = (0 until 7).flatMap { day -> (3L..5L).map { dayText(day) to it } }
        assertEquals(expectedOrder.take(14), delivery.reports.map { it.day to it.release })
        classifier.checkForUpdates()
        assertEquals(expectedOrder, delivery.reports.map { it.day to it.release })
    }

    @Test
    fun aCheckSendsOnlyAfterAnAskTheEdgeAnswered() = runBlocking {
        val classifier = classifierWithDayZeroHeld()
        delivery.reports.clear()
        reportsTried = 0
        online = false
        runCatching { classifier.checkForUpdates() }
        assertEquals(0, reportsTried)
        online = true
        // Nothing on the channel: a 404 is still an answer from the edge.
        delivery.manifest = null
        runCatching { classifier.checkForUpdates() }
        assertEquals(listOf(dayText(0)), delivery.reports.map { it.day })
    }

    @Test
    fun aClassificationStartsASendingAMinuteAfterCreationAndThenAQuarterOfAnHourAfterTheLast() = runBlocking {
        val classifier = classifierWithDayZeroHeld(answers = 1)
        delivery.reports.clear()
        suspend fun reportsAfterAnAnswer(later: Duration): Int {
            now += later.inWholeMilliseconds
            classifier.classify(solidFrame(100))
            return delivery.reports.size
        }
        assertEquals(0, reportsAfterAnAnswer(later = 59.seconds))
        assertEquals(1, reportsAfterAnAnswer(later = 1.seconds))
        assertEquals(listOf(3L), delivery.reports.map { answersIn(it.report) })
        assertEquals(1, reportsAfterAnAnswer(later = 15.minutes - 1.seconds))
        assertEquals(2, reportsAfterAnAnswer(later = 1.seconds))

        classifier.classify(solidFrame(100))
        now += 10.minutes.inWholeMilliseconds
        classifier.checkForUpdates()
        assertEquals(3, delivery.reports.size)
        assertEquals(3, reportsAfterAnAnswer(later = 15.minutes - 1.seconds))
        assertEquals(4, reportsAfterAnAnswer(later = 1.seconds))
    }

    @Test
    fun theCountsAreKeptBeforeEachCheckAndAtMostOnceAMinute() = runBlocking {
        // The edge takes no report, so every count stays on the phone to be read from the file.
        delivery.reportStatus = 503
        val classifier = classifierWithDayZeroHeld(answers = 1)
        classifier.checkForUpdates()
        assertEquals(1L, keptAnswers())
        classifier.classify(solidFrame(100))
        now += 30.seconds.inWholeMilliseconds
        classifier.classify(solidFrame(100))
        assertEquals(1L, keptAnswers())
        now += 31.seconds.inWholeMilliseconds
        classifier.classify(solidFrame(100))
        assertEquals(4L, keptAnswers())
        classifier.classify(solidFrame(100))
        assertEquals(4L, keptAnswers())
    }

    @Test
    fun aRestartGoesOnFromTheKeptCounts() = runBlocking {
        delivery.reportStatus = 503
        val classifier = classifierWithDayZeroHeld(answers = 2)
        classifier.checkForUpdates()
        val restarted = restartedNow()
        restarted.classify(solidFrame(100))
        delivery.reportStatus = 204
        restarted.checkForUpdates()
        val sent = delivery.reports.single { it.status == 204 }
        assertEquals(3L, answersIn(sent.report))
        assertEquals(2L, sent.report.getLong("sessions"))
        assertTrue(sent.report.getBoolean("first"))
    }

    @Test
    fun aFileKeptByALibraryBeforeTheSentDaysStillLoads() = runBlocking {
        val classifier = monitored()
        val yesterdaysCounts = DayCounts().apply { failed = 4 }.toJson().put("release", 3).put("day", dayText(-1))
        File(storeRoot, "monitoring.json").writeText("{\"format\":1,\"days\":[$yesterdaysCounts]}")
        offer(sequence = 7)
        classifier.checkForUpdates()
        val sent = delivery.reports.first()
        assertEquals(dayText(-1), sent.day)
        assertEquals(4L, sent.report.getJSONObject("outcomes").getLong("failed"))
        assertTrue(sent.report.getBoolean("first"))
        assertFalse(File(storeRoot, "monitoring.json.unreadable").exists())
    }

    @Test
    fun anUnreadableKeptFileIsSetAsideAndCountingStartsAfresh() = runBlocking {
        delivery.reportStatus = 503
        val classifier = monitored()
        val garbage = "{\"format\":1,\"days\":[{\"release\":3}]}"
        File(storeRoot, "monitoring.json").writeText(garbage)
        offer(sequence = 7)
        classifier.checkForUpdates()
        classifier.classify(solidFrame(100))
        classifier.checkForUpdates()
        assertEquals(garbage, File(storeRoot, "monitoring.json.unreadable").readText())
        assertEquals(1L, keptAnswers())
    }

    @Test
    fun monitoringOffCountsAndSendsNothingAndDeletesWhatWasKept() = runBlocking {
        val classifier = classifierWithDayZeroHeld()
        classifier.start()
        classifier.checkForUpdates()
        assertTrue(File(storeRoot, "monitoring.json").isFile)
        assertTrue(File(storeRoot, "session.json").isFile)

        delivery.reports.clear()
        val off = restartedNow(configuration(monitoring = false))
        off.start()
        off.classify(solidFrame(100))
        moveDays(1)
        off.checkForUpdates()
        assertEquals(emptyList<SentReport>(), delivery.reports)
        assertFalse(File(storeRoot, "monitoring.json").exists())
        assertFalse(File(storeRoot, "session.json").exists())
    }

    private fun kept() = KeptMonitoring.of(JSONObject(File(storeRoot, "monitoring.json").readText()))

    private fun keptAnswers(): Long = kept().days.values.sumOf { answersIn(it.toJson()) }

    /** The fake model answers 0.9, below the gate: every answer of these tests is a `below_gate`. */
    private fun answersIn(report: JSONObject): Long =
        report.getJSONObject("outcomes").let { it.getLong("answered") + it.getLong("below_gate") }
}
