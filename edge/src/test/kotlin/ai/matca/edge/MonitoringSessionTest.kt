package ai.matca.edge

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Instant
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.measureNanoTime
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Section 10, test 7: crashes counted once against the release a session ran, and a report that never gets an answer. */
internal class MonitoringSessionTest : ClassifierTestBase() {
    private val dayZero = Instant.parse("2026-11-03T10:00:00Z").toEpochMilli()
    private var now = dayZero
    private val unconfined = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val exits = mutableListOf<ProcessExit>()

    /** A classifier created as an app process creates it, over the same store each time. */
    private suspend fun created(first: Boolean = false): Classifier {
        val classifier = if (first) {
            classifier(clock = { now }, scope = unconfined, workDispatcher = Dispatchers.Unconfined, processExits = { exits.toList() })
        } else {
            Classifier(
                "cap-detection", configuration(), ModelStore(storeRoot), delivery, loader, ImageIoDecoder,
                clock = { now }, scope = unconfined, workDispatcher = Dispatchers.Unconfined, systemVersion = SYSTEM_VERSION,
                processExits = { exits.toList() },
            )
        }
        classifier.start()
        return classifier
    }

    @Test
    fun aCrashIsCountedOnceAgainstTheReleaseItsSessionRanOnTheDayItHappened() = runBlocking {
        offer(sequence = 7, release = 3)
        created(first = true)
        exits += ProcessExit(crashed = true, timestampMillis = now - 1.hours.inWholeMilliseconds)
        exits += ProcessExit(crashed = true, timestampMillis = now + 1.hours.inWholeMilliseconds)
        exits += ProcessExit(crashed = false, timestampMillis = now + 2.hours.inWholeMilliseconds)

        now += 1.days.inWholeMilliseconds
        offer(sequence = 8, release = 4)
        created()
        now += 1.days.inWholeMilliseconds
        created()
        now += 1.days.inWholeMilliseconds
        created()

        val crashes = delivery.reports.filter { it.report.getLong("crashes") > 0 }
        assertEquals(listOf(Triple(3L, "2026-11-03", 1L)), crashes.map { Triple(it.release, it.day, it.report.getLong("crashes")) })
        assertEquals(1L, delivery.reports.sumOf { it.report.getLong("crashes") })
    }

    @Test
    fun aSessionWithNoReleaseCountsNoCrash() = runBlocking {
        created(first = true)
        exits += ProcessExit(crashed = true, timestampMillis = now + 1.hours.inWholeMilliseconds)
        now += 1.days.inWholeMilliseconds
        offer(sequence = 7, release = 3)
        created()
        now += 1.days.inWholeMilliseconds
        created()
        assertEquals(0L, delivery.reports.sumOf { it.report.getLong("crashes") })
        assertEquals(listOf("2026-11-04" to 1L, "2026-11-05" to 1L), delivery.reports.map { it.day to it.report.getLong("sessions") })
    }

    @Test
    fun aReportTheEdgeNeverAnswersDelaysNoClassificationAndNoCheck() = runBlocking {
        val reportArrived = CountDownLatch(1)
        val answerTheReport = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            if (exchange.requestURI.path.endsWith("/report")) {
                reportArrived.countDown()
                answerTheReport.await()
            }
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        server.executor = Executors.newCachedThreadPool()
        server.start()
        try {
            val edge = HttpDelivery("http://127.0.0.1:${server.address.port}", "mdk_a_b", BuildConfig.VERSION)
            val stuck = timedClassifier(configuration(bundledRelease = bundle()), edge)
            stuck.classify(solidFrame(100))
            now += 1.days.inWholeMilliseconds
            val checkOutcome = withTimeout(5_000) { runCatching { stuck.checkForUpdates() }.exceptionOrNull() }
            assertEquals(404, (checkOutcome as EdgeError.Edge).status)
            assertTrue("the report was never sent", reportArrived.await(5, TimeUnit.SECONDS))
            val off = timedClassifier(configuration(bundledRelease = bundle(), monitoring = false), edge)

            repeat(200) {
                stuck.classify(solidFrame(100))
                off.classify(solidFrame(100))
            }
            val rounds = (1..5).map { millisForClassifications(stuck) to millisForClassifications(off) }
            val withTheReportWaiting = rounds.minOf { it.first }
            val withMonitoringOff = rounds.minOf { it.second }
            val measured = "100 classifications, best of 5: %.1f ms with a report waiting, %.1f ms with monitoring off"
            println(measured.format(Locale.ROOT, withTheReportWaiting, withMonitoringOff))
            assertTrue("$withTheReportWaiting ms against $withMonitoringOff ms", withTheReportWaiting <= withMonitoringOff * 1.5 + 20)
        } finally {
            answerTheReport.countDown()
            server.stop(0)
        }
    }

    private suspend fun timedClassifier(configuration: EdgeConfiguration, edge: Delivery): Classifier {
        val classifier = classifier(configuration, edge = edge, clock = { now })
        classifier.start()
        return classifier
    }

    private suspend fun millisForClassifications(classifier: Classifier): Double =
        measureNanoTime { repeat(100) { classifier.classify(solidFrame(100)) } } / 1e6
}
