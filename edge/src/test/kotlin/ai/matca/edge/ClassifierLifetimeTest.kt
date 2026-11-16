package ai.matca.edge

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** What must hold when work overlaps: checks share, closing waits its turn, and the app's scope stays the app's. */
internal class ClassifierLifetimeTest : ClassifierTestBase() {
    @Test
    fun aCheckDuringARunningCheckAwaitsItAndSharesItsOutcome() = runBlocking {
        val asks = AtomicInteger()
        val answerTheAsk = CompletableDeferred<Unit>()
        var failure: EdgeError? = null
        val edge = object : Delivery by delivery {
            override suspend fun manifest(model: String): SignedManifest {
                asks.incrementAndGet()
                answerTheAsk.await()
                failure?.let { throw it }
                return delivery.manifest(model)
            }
        }
        val classifier = classifier(edge = edge, workDispatcher = Dispatchers.Unconfined)
        offer(sequence = 7)
        val running = launch(start = CoroutineStart.UNDISPATCHED) { classifier.checkForUpdates() }
        val waiting = launch(start = CoroutineStart.UNDISPATCHED) { classifier.checkForUpdates() }
        assertFalse(waiting.isCompleted)
        answerTheAsk.complete(Unit)
        running.join()
        waiting.join()
        assertEquals(1, asks.get())
        assertEquals(3L, classifier.currentRelease)
        classifier.checkForUpdates()
        assertEquals(2, asks.get())

        failure = EdgeError.Edge(500, "broken")
        val first = async(start = CoroutineStart.UNDISPATCHED) { runCatching { classifier.checkForUpdates() }.exceptionOrNull() }
        assertSame(failure, first.await())
    }

    @Test
    fun closingWaitsForARunningClassification() = runBlocking<Unit> {
        val running = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val engine = ModelLoader {
            object : ModelRunner {
                override fun run(input: FloatArray): FloatArray {
                    running.countDown()
                    finish.await()
                    return floatArrayOf(0.9f, 0.1f)
                }

                override fun close() = closed.countDown()
            }
        }
        val classifier = classifier(engine = engine)
        delivery.manifest = root.signed(manifestText(root.publicKeyBase64))
        delivery.files["android/model.tflite"] = modelBytes
        classifier.checkForUpdates()
        val answer = async(Dispatchers.Default) { classifier.classify(solidFrame(100)) }
        running.await()
        classifier.close()
        val closedUnderTheClassification = closed.await(100, TimeUnit.MILLISECONDS)
        finish.countDown()
        assertFalse(closedUnderTheClassification)
        assertEquals("With Cap", answer.await().label)
        assertTrue(closed.await(5, TimeUnit.SECONDS))
        assertThrows(EdgeError.NoModel::class.java) { runBlocking { classifier.classify(solidFrame(100)) } }
    }

    @Test
    fun closingDuringACheckLeavesNoReleaseInUseAndNothingOpen() = runBlocking<Unit> {
        val answerTheAsk = CompletableDeferred<Unit>()
        val edge = object : Delivery by delivery {
            override suspend fun manifest(model: String): SignedManifest {
                answerTheAsk.await()
                return delivery.manifest(model)
            }
        }
        val classifier = classifier(edge = edge)
        offer(sequence = 7)
        val check = launch(Dispatchers.Default) { classifier.checkForUpdates() }
        delay(100)
        classifier.close()
        delay(100)
        answerTheAsk.complete(Unit)
        check.join()
        assertNull(classifier.currentRelease)
        assertEquals(loader.loaded, loader.closed)
        assertEquals(0, delivery.installs)
        assertEquals(emptyList<String>(), releaseDirectories())
        assertThrows(EdgeError.NoModel::class.java) { runBlocking { classifier.classify(solidFrame(100)) } }
    }

    @Test
    fun aClosedClassifierAsksNothing() = runBlocking {
        val classifier = classifier()
        offer(sequence = 7)
        classifier.close()
        classifier.checkForUpdates()
        assertEquals(0, delivery.fileRequests)
    }

    @Test
    fun anIntervalOfNothingStillAsksAtMostOnceASecond() = runBlocking {
        val asks = AtomicInteger()
        val edge = object : Delivery by delivery {
            override suspend fun manifest(model: String): SignedManifest {
                asks.incrementAndGet()
                return delivery.manifest(model)
            }
        }
        val classifier = classifier(
            configuration().copy(checkInterval = Duration.ZERO),
            edge = edge,
            clock = { 0L },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            workDispatcher = Dispatchers.Unconfined,
        )
        offer(sequence = 7)
        classifier.checkForUpdates()
        repeat(3) { classifier.classify(solidFrame(100)) }
        assertEquals(1, asks.get())
    }

    @Test
    fun closingLeavesTheAppsScopeRunningAndEndsItsOwnWork() = runBlocking {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val classifier = classifier(scope = appScope)
        classifier.close()
        assertTrue(appScope.isActive)
    }

    @Test
    fun theAppCancellingItsScopeEndsThePeriodicAsk() = runBlocking {
        var now = 0L
        val asks = AtomicInteger()
        val edge = object : Delivery by delivery {
            override suspend fun manifest(model: String): SignedManifest {
                asks.incrementAndGet()
                return delivery.manifest(model)
            }
        }
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val classifier = classifier(edge = edge, clock = { now }, scope = appScope, workDispatcher = Dispatchers.Unconfined)
        offer(sequence = 7)
        classifier.checkForUpdates()
        appScope.coroutineContext[Job]!!.cancel()
        now += 7.hours.inWholeMilliseconds
        classifier.classify(solidFrame(100))
        assertEquals(1, asks.get())
    }

    @Test
    fun theWorkRunsOnTheWorkDispatcherAndCreatingLoadsNothing() = runBlocking {
        val executor = Executors.newSingleThreadExecutor { Thread(it, "edge-work") }
        val threads = mutableSetOf<String>()
        fun record(work: String) {
            threads += work + " on " + Thread.currentThread().name.substringBefore(" @")
        }
        val engine = ModelLoader { modelFile ->
            record("load")
            val model = loader.load(modelFile)
            object : ModelRunner by model {
                override fun run(input: FloatArray): FloatArray {
                    record("run")
                    return model.run(input)
                }
            }
        }
        val decoder = JpegDecoder { jpeg ->
            record("decode")
            ImageIoDecoder.decode(jpeg)
        }
        executor.asCoroutineDispatcher().use { dispatcher ->
            val classifier = classifier(engine = engine, decoder = decoder, workDispatcher = dispatcher)
            offer(sequence = 7)
            classifier.checkForUpdates()
            assertEquals(setOf("load on edge-work", "decode on edge-work", "run on edge-work"), threads)
            threads.clear()
            val restarted = Classifier(
                "cap-detection", classifier.configuration, ModelStore(storeRoot), FakeDelivery(), engine, decoder,
                workDispatcher = dispatcher,
            )
            assertEquals(emptySet<String>(), threads)
            restarted.classify(solidFrame(100))
            assertEquals(setOf("load on edge-work", "run on edge-work"), threads)
            threads.clear()
            restarted.classify(referenceJpeg)
            assertEquals(setOf("decode on edge-work", "run on edge-work"), threads)
        }
    }

    @Test
    fun anUpdateNoAppAwaitsReachesNoHandler() = runBlocking {
        val asks = AtomicInteger()
        var failure: Throwable? = null
        val edge = object : Delivery by delivery {
            override suspend fun manifest(model: String): SignedManifest {
                asks.incrementAndGet()
                failure?.let { throw it }
                return delivery.manifest(model)
            }
        }
        val uncaught = mutableListOf<Throwable>()
        val handler = CoroutineExceptionHandler { _, error -> uncaught += error }
        var now = 0L
        val classifier = classifier(
            edge = edge,
            clock = { now },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + handler),
            workDispatcher = Dispatchers.Unconfined,
        )
        offer(sequence = 7)
        classifier.checkForUpdates()
        for (kind in listOf(IllegalStateException("broken"), OutOfMemoryError("no memory"))) {
            failure = kind
            classifier.start()
            now += 7.hours.inWholeMilliseconds
            assertEquals("With Cap", classifier.classify(referenceJpeg).label)
        }
        assertEquals(5, asks.get())
        assertEquals(emptyList<Throwable>(), uncaught)
    }
}
