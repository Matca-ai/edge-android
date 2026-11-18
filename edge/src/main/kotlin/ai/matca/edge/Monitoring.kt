package ai.matca.edge

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject

private const val KEPT_FILE = "monitoring.json"
private const val SESSION_FILE = "session.json"
private const val KEEP_INTERVAL_MILLIS = 60_000L
private const val FIRST_SENDING_AFTER_MILLIS = 60_000L
private const val SENDING_INTERVAL_MILLIS = 15 * 60_000L
private const val REPORTS_PER_SENDING = 14
private const val DAYS_HELD = 7

// Refusals the edge would give the same report again, so the report is dropped.
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

/** How a report ended: the edge took it, refused it in a way it would refuse again, or it stays for the next sending. */
private enum class ReportOutcome { TAKEN, REFUSED_FOR_GOOD, KEPT }

/**
 * What each release does on this phone (contract, section 11): counts per release and UTC day, held in memory,
 * kept in `monitoring.json` and sent as reports of what was counted since the last one, after a manifest ask and
 * while the classifier answers. The `count…` and `claim…` calls only add and compare in memory; `keep`,
 * `beginSession`, `recordRunningRelease` and `sendHeldDays` read and write files and the network, and run off the
 * classification's path. With monitoring off nothing is counted and what was kept is deleted.
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
    // The counts no report has carried yet; `reportedDays` and `reportOnItsWay` are guarded by this map's lock too.
    private val days = HashMap<ReleaseDay, DayCounts>()
    private val reportedDays = HashSet<ReleaseDay>()
    private var reportOnItsWay: Pair<ReleaseDay, DayCounts>? = null
    private val releasesWithSession = HashSet<Long>()
    private val keeping = Any()
    private val sessionLock = Any()
    private val sending = Mutex()
    private val lastKeptAt = AtomicLong(clock())
    private val nextSendingAt = AtomicLong(clock() + FIRST_SENDING_AFTER_MILLIS)
    private var keptLoaded = false
    private var sessionStartedAt: Long? = null

    @Volatile
    private var runningRelease: Long? = null

    @Volatile
    private var sendingClaimedWhileAnswering = false

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
     * True for the one classification that starts a sending: the first a minute after the classifier was created,
     * so a release shows within minutes, and from then on the first a quarter of an hour after the last sending.
     */
    fun claimSendingIfDue(): Boolean {
        if (!enabled) return false
        val due = nextSendingAt.get()
        val now = clock()
        if (now < due || !nextSendingAt.compareAndSet(due, now + SENDING_INTERVAL_MILLIS)) return false
        sendingClaimedWhileAnswering = true
        return true
    }

    /**
     * The counts written to `monitoring.json`, together with what an earlier process kept there. With monitoring off
     * it deletes what was kept instead; it runs before every check, the one at creation included.
     */
    fun keep() {
        if (!enabled) return deleteKeptFiles()
        synchronized(keeping) {
            loadKept()
            lastKeptAt.set(clock())
            store.writeDocument(KEPT_FILE, heldNow().toJson())
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
     * After a manifest ask the edge answered and while the classifier answers: one report for every release and day
     * with counts no report has carried, today's too, oldest first, at most 14. A day older than a week is dropped
     * unsent. A report the edge neither took nor refused for good ends the sending until the next one. A sending still
     * waiting for the edge is left to finish, never doubled.
     */
    suspend fun sendHeldDays() {
        if (!enabled || !sending.tryLock()) return
        try {
            // The first sending while answering stays a minute after creation, whatever the check at creation sent.
            if (sendingClaimedWhileAnswering) nextSendingAt.set(clock() + SENDING_INTERVAL_MILLIS)
            synchronized(keeping) { loadKept() }
            for (key in heldDays(utcDay(clock())).take(REPORTS_PER_SENDING)) {
                if (send(key) == ReportOutcome.KEPT) break
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

    private fun countsOn(release: Long, epochMillis: Long): DayCounts = countsOf(ReleaseDay(release, utcDay(epochMillis)))

    private fun countsOf(key: ReleaseDay): DayCounts = days.getOrPut(key) { DayCounts() }

    /** Merges what an earlier process kept into what memory holds, once; called holding `keeping`. */
    private fun loadKept() {
        if (keptLoaded) return
        store.readDocument(KEPT_FILE, KeptMonitoring::of)?.let { kept ->
            synchronized(days) {
                for ((key, counts) in kept.days) countsOf(key).add(counts)
                reportedDays += kept.sent
            }
        }
        keptLoaded = true
    }

    /**
     * What a restart must find: the counts of a report still on its way are among them, so a process killed before
     * the edge answers loses none (it may send them twice instead).
     */
    private fun heldNow(): KeptMonitoring = synchronized(days) {
        val held = days.mapValuesTo(HashMap()) { (_, counts) -> counts.copy() }
        reportOnItsWay?.let { (key, counts) -> held.getOrPut(key) { DayCounts() }.add(counts) }
        KeptMonitoring(held, reportedDays.toSet())
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

    /** The releases and days with counts to report, oldest first, once all that is older than a week is dropped. */
    private fun heldDays(today: Long): List<ReleaseDay> = synchronized(days) {
        days.keys.removeAll { today - it.day > DAYS_HELD }
        reportedDays.removeAll { today - it.day > DAYS_HELD }
        days.keys.sortedWith(compareBy({ it.day }, { it.release }))
    }

    /**
     * One report of what was counted for a release and day since its last report. The counts leave `days` before the
     * report does, so what is counted while the edge answers goes with the next report; they come back when the
     * report stays, whatever stopped it.
     */
    private suspend fun send(key: ReleaseDay): ReportOutcome {
        val (counts, first) = synchronized(days) {
            val counts = days.remove(key) ?: return ReportOutcome.TAKEN
            reportOnItsWay = key to counts
            counts to (key !in reportedDays)
        }
        var outcome = ReportOutcome.KEPT
        try {
            delivery.sendReport(model, key.release, counts.report(key.day, first, systemVersion).toString())
            outcome = ReportOutcome.TAKEN
        } catch (error: EdgeError.Edge) {
            if (error.status in FINAL_REFUSALS) outcome = ReportOutcome.REFUSED_FOR_GOOD
        } finally {
            synchronized(days) {
                reportOnItsWay = null
                when (outcome) {
                    ReportOutcome.TAKEN -> reportedDays += key
                    ReportOutcome.REFUSED_FOR_GOOD -> Unit
                    ReportOutcome.KEPT -> countsOf(key).add(counts)
                }
            }
        }
        return outcome
    }

    private fun deleteKeptFiles() {
        store.deleteDocument(KEPT_FILE)
        store.deleteDocument(SESSION_FILE)
    }
}
