package ai.matca.edge

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject

private const val KEPT_FILE = "monitoring.json"
private const val SESSION_FILE = "session.json"
private const val KEEP_INTERVAL_MILLIS = 60_000L
private const val REPORTS_PER_CHECK = 14
private const val DAYS_HELD = 7

// Answers that end a report: the edge took it, or refused it in a way it would refuse again.
private val FINAL_REFUSALS = setOf(400, 401, 404, 413)

/** The session a classifier recorded at its creation: the release its process ran and when the session started. */
private class RecordedSession(val release: Long?, val startedAt: Long) {
    companion object {
        fun of(document: JSONObject) = RecordedSession(
            release = if (document.isNull("release")) null else document.getLong("release"),
            startedAt = document.getLong("started_at"),
        )
    }
}

/**
 * What each release does on this phone (contract, section 11): counts per release and UTC day, held in memory,
 * kept in `monitoring.json` and sent as one report per finished day after a manifest ask. The `count…` calls only
 * add in memory; `keep`, `beginSession`, `recordRunningRelease` and `sendFinishedDays` read and write files and the
 * network, and run off the classification's path. With monitoring off nothing is counted and what was kept is deleted.
 */
internal class Monitoring(
    private val enabled: Boolean,
    private val model: String,
    private val store: ModelStore,
    private val delivery: Delivery,
    private val systemVersion: String,
    private val processExits: ProcessExitHistory,
    private val clock: () -> Long,
) {
    private val days = HashMap<ReleaseDay, DayCounts>()
    private val releasesWithSession = HashSet<Long>()
    private val keeping = Any()
    private val sessionLock = Any()
    private val sending = Mutex()
    private val lastKeptAt = AtomicLong(clock())
    private var keptDaysLoaded = false
    private var sessionStartedAt: Long? = null

    @Volatile
    private var runningRelease: Long? = null

    fun countAnswer(release: Long, answer: Answer, preprocessingNanos: Long, modelNanos: Long) =
        count(release) { it.addAnswer(answer.label, answer.confidence, preprocessingNanos, modelNanos) }

    fun countFailure(release: Long) = count(release) { it.failed++ }

    fun countNoStablePicture(release: Long) = count(release) { it.noStablePicture++ }

    fun countBlackScreen(release: Long) = count(release) { it.blackScreen++ }

    /** A release took over in this classifier: one session for it, once per classifier, on the day it was loaded or taken. */
    fun releaseInUse(release: Long) {
        if (!enabled) return
        runningRelease = release
        synchronized(days) {
            if (releasesWithSession.add(release)) countsOn(release, clock()).sessions++
        }
    }

    /** True once a minute has passed since the counts were last kept, for the one classification that starts the write. */
    fun claimKeepIfDue(): Boolean {
        if (!enabled) return false
        val last = lastKeptAt.get()
        val now = clock()
        return now - last >= KEEP_INTERVAL_MILLIS && lastKeptAt.compareAndSet(last, now)
    }

    /**
     * The counts written to `monitoring.json`, together with what an earlier process kept there. With monitoring off
     * it deletes what was kept instead; it runs before every check, the one at creation included.
     */
    fun keep() {
        if (!enabled) return deleteKeptFiles()
        synchronized(keeping) {
            loadKeptDays()
            lastKeptAt.set(clock())
            val snapshot = synchronized(days) { days.mapValues { (_, counts) -> counts.copy() } }
            store.writeDocument(KEPT_FILE, keptDaysJson(snapshot))
        }
    }

    /**
     * At a classifier's creation: the crashes since the session recorded before it are counted against the release
     * that session ran, on the day of each crash, and this session is recorded in its place. The new session starts
     * at the moment the exits were read up to, so the next reading counts none of them again.
     */
    fun beginSession() {
        if (!enabled) return
        synchronized(sessionLock) {
            val now = clock()
            store.readDocument(SESSION_FILE, RecordedSession::of)?.let { countCrashes(it, now) }
            sessionStartedAt = now
            writeSession(now)
        }
    }

    /** After a switch: the session names the release this process now runs, so a crash counts against it. */
    fun recordRunningRelease() {
        if (!enabled) return
        synchronized(sessionLock) { sessionStartedAt?.let(::writeSession) }
    }

    /**
     * After a manifest ask the edge answered: every finished day held, oldest first, at most 14. A day older than a
     * week is dropped unsent; a report the edge took or refused for good is dropped; any other answer keeps it and
     * ends the sending until the next check. A sending still waiting for the edge is left to finish, never doubled.
     */
    suspend fun sendFinishedDays() {
        if (!enabled || !sending.tryLock()) return
        try {
            synchronized(keeping) { loadKeptDays() }
            for (key in finishedDays(utcDay(clock())).take(REPORTS_PER_CHECK)) {
                if (!send(key)) break
            }
            keep()
        } finally {
            sending.unlock()
        }
    }

    private inline fun count(release: Long, add: (DayCounts) -> Unit) {
        if (!enabled) return
        synchronized(days) { add(countsOn(release, clock())) }
    }

    private fun countsOn(release: Long, epochMillis: Long): DayCounts =
        days.getOrPut(ReleaseDay(release, utcDay(epochMillis))) { DayCounts() }

    /** Merges what an earlier process kept into the counts in memory, once; called holding `keeping`. */
    private fun loadKeptDays() {
        if (keptDaysLoaded) return
        val kept = store.readDocument(KEPT_FILE, ::keptDaysOf).orEmpty()
        synchronized(days) {
            for ((key, counts) in kept) days.getOrPut(key) { DayCounts() }.add(counts)
        }
        keptDaysLoaded = true
    }

    private fun countCrashes(session: RecordedSession, readUntil: Long) {
        val release = session.release ?: return
        val crashTimes = processExits.exits()
            .filter { it.crashed && it.timestampMillis > session.startedAt && it.timestampMillis <= readUntil }
            .map { it.timestampMillis }
        synchronized(days) {
            for (time in crashTimes) countsOn(release, time).crashes++
        }
    }

    private fun writeSession(startedAt: Long) {
        val document = JSONObject().put("release", runningRelease ?: JSONObject.NULL).put("started_at", startedAt)
        store.writeDocument(SESSION_FILE, document)
    }

    /** The finished days still held, oldest first, once those older than a week are dropped. */
    private fun finishedDays(today: Long): List<ReleaseDay> = synchronized(days) {
        days.keys.removeAll { today - it.day > DAYS_HELD }
        days.keys.filter { it.day < today }.sortedWith(compareBy({ it.day }, { it.release }))
    }

    /** Whether the report is done with, taken or refused for good; false when it stays for the next check. */
    private suspend fun send(key: ReleaseDay): Boolean {
        val counts = synchronized(days) { days[key]?.copy() } ?: return true
        val status = try {
            delivery.sendReport(model, key.release, counts.report(key.day, systemVersion).toString())
            null
        } catch (error: EdgeError.Edge) {
            error.status
        }
        val done = status == null || status in FINAL_REFUSALS
        if (done) synchronized(days) { days.remove(key) }
        return done
    }

    private fun deleteKeptFiles() {
        store.deleteDocument(KEPT_FILE)
        store.deleteDocument(SESSION_FILE)
    }
}
