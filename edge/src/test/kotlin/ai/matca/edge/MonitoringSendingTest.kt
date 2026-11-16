package ai.matca.edge

import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Section 10, test 7: when a finished day is sent, what drops or keeps it, how the counts are kept, and the switch. */
internal class MonitoringSendingTest : ClassifierTestBase() {
    private val dayZero = Instant.parse("2026-11-03T10:00:00Z").toEpochMilli()
    private var now = dayZero
    private val unconfined = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var online = true

    /** The fake edge behind a connection the test can cut: offline, the manifest ask gets no answer at all. */
    private val edge = object : Delivery by delivery {
        override suspend fun manifest(model: String): SignedManifest {
            if (!online) throw EdgeError.Edge(0, "offline")
            return delivery.manifest(model)
        }
    }

    private fun monitored(configuration: EdgeConfiguration = configuration()) =
        classifier(configuration, edge = edge, clock = { now }, scope = unconfined, workDispatcher = Dispatchers.Unconfined)

    private fun dayText(daysFromZero: Int): String =
        Instant.ofEpochMilli(dayZero).atOffset(ZoneOffset.UTC).toLocalDate().plusDays(daysFromZero.toLong()).toString()

    private fun moveDays(days: Int) {
        now += days.days.inWholeMilliseconds
    }

    /** A classifier with release 3 that answered `answers` pictures on day zero. */
    private suspend fun classifierWithDayZeroHeld(answers: Int = 2): Classifier {
        val classifier = monitored()
        offer(sequence = 7)
        classifier.checkForUpdates()
        repeat(answers) { classifier.classify(solidFrame(100)) }
        return classifier
    }

    @Test
    fun aFinishedDayIsSentOnceAfterTheNextCheckAndTodayNever() = runBlocking {
        val classifier = classifierWithDayZeroHeld()
        classifier.checkForUpdates()
        assertEquals(emptyList<SentReport>(), delivery.reports)

        moveDays(1)
        classifier.checkForUpdates()
        val sent = delivery.reports.single()
        assertEquals(3L, sent.release)
        assertEquals(dayText(0), sent.day)
        assertEquals(2L, answersIn(sent.report))
        assertEquals(1L, sent.report.getLong("sessions"))
        classifier.classify(solidFrame(100))
        classifier.checkForUpdates()
        assertEquals(1, delivery.reports.size)
    }

    @Test
    fun aReportTheEdgeRefusesForGoodIsDroppedAndAnyOtherAnswerKeepsIt() = runBlocking {
        val dropped = listOf(400, 401, 404, 413)
        val kept = listOf(429, 500, 503, 0)
        for (status in dropped + kept) {
            delivery.reports.clear()
            delivery.reportStatus = status
            val classifier = classifierWithDayZeroHeld()
            moveDays(1)
            classifier.checkForUpdates()
            delivery.reportStatus = 204
            classifier.checkForUpdates()
            val expected = if (status in kept) listOf(status, 204) else listOf(status)
            assertEquals("status $status", expected, delivery.reports.map { it.status })
            now = dayZero
        }
    }

    @Test
    fun aKeptReportEndsTheSendingUntilTheNextCheckAndDaysGoOldestFirst() = runBlocking {
        val classifier = classifierWithDayZeroHeld()
        online = false
        moveDays(1)
        classifier.classify(solidFrame(100))
        moveDays(1)
        online = true
        delivery.reportStatus = 429
        classifier.checkForUpdates()
        assertEquals(listOf(dayText(0)), delivery.reports.map { it.day })
        delivery.reportStatus = 204
        classifier.checkForUpdates()
        assertEquals(listOf(dayText(0), dayText(0), dayText(1)), delivery.reports.map { it.day })
    }

    @Test
    fun aPhoneOffForEightDaysSendsTheLastSevenAndDropsTheEighth() = runBlocking {
        val classifier = classifierWithDayZeroHeld()
        online = false
        repeat(7) {
            moveDays(1)
            classifier.classify(solidFrame(100))
        }
        moveDays(1)
        online = true
        classifier.checkForUpdates()
        assertEquals((1..7).map(::dayText), delivery.reports.map { it.day })
        classifier.checkForUpdates()
        assertEquals(7, delivery.reports.size)
    }

    @Test
    fun atMostFourteenReportsGoPerCheck() = runBlocking {
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
    fun finishedDaysGoOnlyAfterAnAskTheEdgeAnswered() = runBlocking {
        val classifier = classifierWithDayZeroHeld()
        moveDays(1)
        online = false
        runCatching { classifier.checkForUpdates() }
        assertEquals(emptyList<SentReport>(), delivery.reports)
        online = true
        // Nothing on the channel: a 404 is still an answer from the edge.
        delivery.manifest = null
        runCatching { classifier.checkForUpdates() }
        assertEquals(listOf(dayText(0)), delivery.reports.map { it.day })
    }

    @Test
    fun theCountsAreKeptBeforeEachCheckAndAtMostOnceAMinute() = runBlocking {
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
        val classifier = classifierWithDayZeroHeld(answers = 2)
        classifier.checkForUpdates()
        val restarted = Classifier(
            "cap-detection", configuration(), ModelStore(storeRoot), delivery, loader, ImageIoDecoder,
            clock = { now }, scope = unconfined, workDispatcher = Dispatchers.Unconfined, systemVersion = SYSTEM_VERSION,
        )
        restarted.classify(solidFrame(100))
        moveDays(1)
        restarted.checkForUpdates()
        val sent = delivery.reports.single()
        assertEquals(3L, answersIn(sent.report))
        assertEquals(2L, sent.report.getLong("sessions"))
    }

    @Test
    fun anUnreadableKeptFileIsSetAsideAndCountingStartsAfresh() = runBlocking {
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

        val off = Classifier(
            "cap-detection", configuration(monitoring = false), ModelStore(storeRoot), delivery, loader, ImageIoDecoder,
            clock = { now }, scope = unconfined, workDispatcher = Dispatchers.Unconfined, systemVersion = SYSTEM_VERSION,
        )
        off.start()
        off.classify(solidFrame(100))
        moveDays(1)
        off.checkForUpdates()
        assertEquals(emptyList<SentReport>(), delivery.reports)
        assertFalse(File(storeRoot, "monitoring.json").exists())
        assertFalse(File(storeRoot, "session.json").exists())
    }

    private fun keptAnswers(): Long {
        val kept = keptDaysOf(JSONObject(File(storeRoot, "monitoring.json").readText()))
        return kept.values.sumOf { answersIn(it.toJson()) }
    }

    /** The fake model answers 0.9, below the gate: every answer of these tests is a `below_gate`. */
    private fun answersIn(report: JSONObject): Long =
        report.getJSONObject("outcomes").let { it.getLong("answered") + it.getLong("below_gate") }
}
