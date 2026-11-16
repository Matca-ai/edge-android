package ai.matca.edge

import ai.matca.edge.preprocessing.PreprocessingOverrides
import ai.matca.edge.preprocessing.PreprocessingSteps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private val MODEL_NAME = Regex("[a-z0-9][a-z0-9-]{0,62}")
private const val SHORTEST_CHECK_INTERVAL_MILLIS = 1_000L

/** The model name forms URLs and a directory: a DNS label, as the control plane names models. */
internal fun requireValidModelName(model: String) {
    if (!MODEL_NAME.matches(model)) throw EdgeError.InvalidModelName()
}

/**
 * One device model on this phone: it answers pictures with the newest verified release and keeps itself
 * up to date through the steps of the contract's section 4. An app makes one with `Classifier.create`.
 * Every suspending call does its work on `workDispatcher`, so an app may call it from its main thread.
 */
public class Classifier internal constructor(
    public val model: String,
    public val configuration: EdgeConfiguration,
    private val store: ModelStore,
    private val delivery: Delivery,
    loader: ModelLoader,
    private val decoder: JpegDecoder,
    private val networkPolicy: NetworkPolicy = NetworkPolicy.ALWAYS,
    private val clock: () -> Long = System::currentTimeMillis,
    scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    internal val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
    systemVersion: String = "",
    processExits: ProcessExitHistory = ProcessExitHistory.NONE,
    private val monotonicNanos: () -> Long = System::nanoTime,
) : AutoCloseable {
    init {
        requireValidModelName(model)
    }

    // A child, because the scope may be the app's own and closing a classifier must not end it.
    private val ownScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val updater = Updater(model, configuration, store, loader, decoder, clock)
    private val edgeSource = DeliverySource(delivery, model) { !configuration.wifiOnly || networkPolicy.downloadsAllowed() }
    private val monitoring = Monitoring(configuration.monitoring, model, store, delivery, systemVersion, processExits, clock)
    private val watchEvents = object : WatchEvents {
        override fun stablePictureTimedOut() {
            currentRelease?.let(monitoring::countNoStablePicture)
        }

        override fun blackScreenEmitted() {
            currentRelease?.let(monitoring::countBlackScreen)
        }
    }
    private val inUse = Mutex()
    private val checking = Mutex()
    private val localStartup = Mutex()

    @Volatile
    private var current: LoadedRelease? = null

    @Volatile
    private var localReleasesLoaded = false

    @Volatile
    private var closed = false

    @Volatile
    private var checksFinished = 0L
    private var lastCheckFailure: EdgeError? = null

    @Volatile
    private var lastAskedAt: Long = clock()
    private val overridesLogged = mutableSetOf<String>()

    /** The app's own switches over the release's picture steps. */
    @Volatile
    public var preprocessing: PreprocessingOverrides = PreprocessingOverrides()

    /** The release in use; null until the first classification or check has loaded the one on disk. */
    public val currentRelease: Long?
        get() = current?.manifest?.release

    public suspend fun classify(frame: Frame): Answer = classifyPicture { frame }

    public suspend fun classify(jpeg: ByteArray): Answer = classifyPicture { Frame.decoded(jpeg, decoder) }

    /**
     * Asks the edge for the channel's manifest now and takes the release when every step passes. A check
     * already running is awaited instead of doubled, and shares its outcome. Throws the reason as an
     * `EdgeError`, the current model still working. Kotlin does not force a caller to handle it: a bare
     * `launch { checkForUpdates() }` with no handler crashes the app when the phone is offline.
     */
    public suspend fun checkForUpdates() {
        withContext(workDispatcher) {
            loadLocalReleases()
            if (closed) return@withContext
            val finishedBeforeWaiting = checksFinished
            checking.withLock {
                if (checksFinished == finishedBeforeWaiting) runCheck()
                lastCheckFailure?.let { throw it }
            }
        }
    }

    public fun watch(configure: Watch.Builder.() -> Unit = {}): Watch =
        Watch({ frame -> classify(frame) }, Watch.Builder().apply(configure).build(), watchEvents)

    /**
     * Returns at once; the model closes when a running classification, which holds the lock, is done with it.
     * From then on nothing is taken and `classify` throws `EdgeError.NoModel`.
     */
    override fun close() {
        closed = true
        ownScope.cancel()
        CoroutineScope(workDispatcher).launch {
            inUse.withLock {
                runCatching { current?.close() }
                current = null
            }
        }
    }

    /** The anchor for the platform factory, `Classifier.create(context, …)`. */
    public companion object {}

    /**
     * The bundled release first, before any network, then this session recorded for monitoring, then the first ask;
     * nothing it meets reaches the app.
     */
    internal suspend fun start() {
        withContext(workDispatcher) {
            loadLocalReleases()
            monitorQuietly { monitoring.beginSession() }
            logFailures { checkForUpdates() }
        }
    }

    /**
     * Every `classify` comes here with its picture still to be read, so reading it counts as preprocessing time.
     * Monitoring adds to counts in memory here; the minute's write it may start runs in its own coroutine.
     */
    internal suspend fun classifyPicture(readPicture: () -> Frame): Answer = withContext(workDispatcher) {
        loadLocalReleases()
        askIfDue()
        try {
            answerCounted(readPicture)
        } finally {
            if (monitoring.claimKeepIfDue()) inBackground { monitoring.keep() }
        }
    }

    internal fun startInBackground() {
        ownScope.launch { start() }
    }

    /** The release on disk and the bundled one, once, before the first classification or check. */
    private suspend fun loadLocalReleases() {
        if (localReleasesLoaded) return
        localStartup.withLock {
            if (localReleasesLoaded) return@withLock
            logFailures {
                inUse.withLock {
                    if (!closed && current == null) current = store.current()?.let(updater::restore)
                    current?.let { monitoring.releaseInUse(it.manifest.release) }
                }
            }
            configuration.bundledRelease?.let { directory ->
                logFailures {
                    val bundled = BundledSource(directory)
                    take(bundled.signedManifest(), bundled)
                }
            }
            localReleasesLoaded = true
        }
    }

    private suspend fun runCheck() {
        lastAskedAt = clock()
        monitorQuietly { monitoring.keep() }
        var edgeAnswered = true
        lastCheckFailure = try {
            val signed = try {
                delivery.manifest(model)
            } catch (error: EdgeError.Edge) {
                edgeAnswered = error.status != 0
                throw error
            }
            take(signed, edgeSource)
            null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // Errors too, as a download can run out of memory and a deep document can exhaust the stack.
            error as? EdgeError ?: EdgeError.UpdateStopped(describe(error))
        }
        // After the take rather than straight after the ask, so the sending's own write keeps the session a take began.
        if (edgeAnswered) inBackground { monitoring.sendFinishedDays() }
        checksFinished++
    }

    /** Monitoring's own coroutine, on the work dispatcher whatever the app's scope runs on; a failure is only logged. */
    private fun inBackground(work: suspend () -> Unit) {
        ownScope.launch(workDispatcher) { monitorQuietly(work) }
    }

    /** One classification timed and counted for monitoring; a failure is counted against the release in use. */
    private suspend fun answerCounted(readPicture: () -> Frame): Answer {
        val startedAt = monotonicNanos()
        try {
            val frame = readPicture()
            val readNanos = monotonicNanos() - startedAt
            return inUse.withLock {
                val release = current ?: throw EdgeError.NoModel()
                val preparingAt = monotonicNanos()
                val pixels = release.prepared(frame, stepsFor(release))
                val modelStartedAt = monotonicNanos()
                val answer = release.scored(pixels)
                val preprocessingNanos = readNanos + modelStartedAt - preparingAt
                monitoring.countAnswer(release.manifest.release, answer, preprocessingNanos, monotonicNanos() - modelStartedAt)
                answer
            }
        } catch (error: Throwable) {
            if (error !is EdgeError.NoModel && error !is CancellationException) currentRelease?.let(monitoring::countFailure)
            throw error
        }
    }

    /** Monitoring's reading and writing never reaches the app; a failure is logged and counting goes on in memory. */
    private suspend fun monitorQuietly(work: suspend () -> Unit) {
        try {
            work()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.info("monitoring could not keep or send its counts: ${describe(error)}")
        }
    }

    /** Where an update that no app is waiting for stops, the current model still working. */
    private suspend fun logFailures(update: suspend () -> Unit) {
        try {
            update()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.warning("the update stopped: ${describe(error)}")
        }
    }

    /** Steps 2 to 8 for one manifest. */
    private suspend fun take(signed: SignedManifest, source: ReleaseSource) {
        val candidate = updater.prepare(signed, source, currentRelease) ?: return
        if (!switchTo(candidate)) return
        log.info("release ${candidate.manifest.release} of $model is now in use")
        monitorQuietly { monitoring.recordRunningRelease() }
        countQuietly { source.installed(candidate.manifest.release) }
    }

    /** Step 8; false when the release did not take over, and then nothing of it is left. */
    private suspend fun switchTo(candidate: LoadedRelease): Boolean {
        var previous: LoadedRelease? = null
        var switched = false
        try {
            inUse.withLock {
                if (!closed) {
                    store.switchTo(candidate.directory, candidate.manifestText, candidate.manifest.sequence)
                    previous = current
                    current = candidate
                    switched = true
                    monitoring.releaseInUse(candidate.manifest.release)
                }
            }
        } finally {
            if (!switched) {
                candidate.close()
                store.discard(candidate.directory)
            }
        }
        if (switched) {
            runCatching { previous?.close() }
            store.discardAllExcept(candidate.directory)
        }
        return switched
    }

    private fun askIfDue() {
        val interval = configuration.checkInterval.inWholeMilliseconds.coerceAtLeast(SHORTEST_CHECK_INTERVAL_MILLIS)
        if (clock() - lastAskedAt < interval || checking.isLocked) return
        lastAskedAt = clock()
        ownScope.launch { logFailures { checkForUpdates() } }
    }

    private fun stepsFor(release: LoadedRelease): PreprocessingSteps {
        val (steps, changed) = release.manifest.preprocessing.overridden(preprocessing)
        for (step in changed) {
            if (overridesLogged.add(step)) {
                log.warning("the app overrides $step: pictures differ from what the model learned with")
            }
        }
        return steps
    }
}
