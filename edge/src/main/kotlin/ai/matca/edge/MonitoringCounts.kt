package ai.matca.edge

import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

private const val REPORT_FORMAT = 2
private const val STEP_COUNT = 12
private const val NANOS_PER_MILLI = 1_000_000L
private const val MILLIS_PER_DAY = 86_400_000L

/** The gate the trainer scores against: an answer at or above it is `answered`, below it `below_gate`. */
internal const val CONFIDENCE_GATE = 0.95

// The trainer's gate thresholds: 12 steps between and around them. A test holds them and the speed steps to the
// shared fixtures (steps.json), as the trainer, the control plane and the iOS library are held.
internal val CONFIDENCE_STEPS = doubleArrayOf(0.5, 0.6, 0.7, 0.8, 0.85, 0.9, 0.92, CONFIDENCE_GATE, 0.97, 0.98, 0.99)
internal val SPEED_STEPS_MILLIS = longArrayOf(10, 20, 30, 50, 75, 100, 150, 200, 300, 500, 1000)

/** The UTC day of a moment, counted in days since 1970-01-01. */
internal fun utcDay(epochMillis: Long): Long = Math.floorDiv(epochMillis, MILLIS_PER_DAY)

internal fun utcDayText(day: Long): String = utcDayFormat().format(Date(day * MILLIS_PER_DAY))

private fun utcDayOf(text: String): Long = try {
    utcDay(checkNotNull(utcDayFormat().parse(text)).time)
} catch (_: ParseException) {
    throw JSONException("not a day: $text")
}

private fun utcDayFormat() = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply {
    timeZone = TimeZone.getTimeZone("UTC")
    isLenient = false
}

/** Which release a count is for and on which UTC day. */
internal data class ReleaseDay(val release: Long, val day: Long)

/** One release's counts on one UTC day (contract, section 11), in the form a report carries them. */
internal class DayCounts {
    var answered = 0L
    var belowGate = 0L
    var failed = 0L
    var noStablePicture = 0L
    var blackScreen = 0L
    var sessions = 0L
    var crashes = 0L
    private val confidence = LongArray(STEP_COUNT)
    private val labels = HashMap<String, Long>()
    private val preprocessingSpeed = LongArray(STEP_COUNT)
    private val modelSpeed = LongArray(STEP_COUNT)

    fun addAnswer(label: String, confidence: Double, preprocessingNanos: Long, modelNanos: Long) {
        if (confidence >= CONFIDENCE_GATE) answered++ else belowGate++
        this.confidence[confidenceStep(confidence)]++
        labels[label] = (labels[label] ?: 0L) + 1
        preprocessingSpeed[speedStep(preprocessingNanos)]++
        modelSpeed[speedStep(modelNanos)]++
    }

    fun copy(): DayCounts = DayCounts().also { it.add(this) }

    fun add(other: DayCounts) {
        answered += other.answered
        belowGate += other.belowGate
        failed += other.failed
        noStablePicture += other.noStablePicture
        blackScreen += other.blackScreen
        sessions += other.sessions
        crashes += other.crashes
        for (step in 0 until STEP_COUNT) {
            confidence[step] += other.confidence[step]
            preprocessingSpeed[step] += other.preprocessingSpeed[step]
            modelSpeed[step] += other.modelSpeed[step]
        }
        for ((label, count) in other.labels) labels[label] = (labels[label] ?: 0L) + count
    }

    fun toJson(): JSONObject = JSONObject()
        .put(
            "outcomes",
            JSONObject()
                .put("answered", answered)
                .put("below_gate", belowGate)
                .put("failed", failed)
                .put("no_stable_picture", noStablePicture)
                .put("black_screen", blackScreen),
        )
        .put("confidence", confidence.toJson())
        .put("labels", JSONObject(labels.toMap()))
        .put("speed_ms", JSONObject().put("preprocessing", preprocessingSpeed.toJson()).put("model", modelSpeed.toJson()))
        .put("sessions", sessions)
        .put("crashes", crashes)

    /**
     * A report for this release's day as it leaves the phone: the counts since the last one and what they were measured
     * on, nothing else. `first` is what the control plane counts a phone by, so it is true once per release and day.
     */
    fun report(day: Long, first: Boolean, systemVersion: String): JSONObject = toJson()
        .put("format", REPORT_FORMAT)
        .put("first", first)
        .put("day", utcDayText(day))
        .put("platform", PLATFORM)
        .put("os", systemVersion)
        .put("library", BuildConfig.VERSION)

    companion object {
        /** Counts as `toJson` wrote them; a missing or mistyped field throws `JSONException`. */
        fun fromJson(document: JSONObject): DayCounts = DayCounts().apply {
            val outcomes = document.getJSONObject("outcomes")
            answered = outcomes.getLong("answered")
            belowGate = outcomes.getLong("below_gate")
            failed = outcomes.getLong("failed")
            noStablePicture = outcomes.getLong("no_stable_picture")
            blackScreen = outcomes.getLong("black_screen")
            sessions = document.getLong("sessions")
            crashes = document.getLong("crashes")
            document.getJSONArray("confidence").readInto(confidence)
            val speed = document.getJSONObject("speed_ms")
            speed.getJSONArray("preprocessing").readInto(preprocessingSpeed)
            speed.getJSONArray("model").readInto(modelSpeed)
            val labelCounts = document.getJSONObject("labels")
            for (label in labelCounts.keys()) labels[label] = labelCounts.getLong(label)
        }
    }
}

/**
 * Monitoring's kept file: the counts not yet reported, as `DayCounts.toJson` with their release and day, and the
 * releases and days a report was already sent for, so the next report for one of them is not a first again.
 */
internal class KeptMonitoring(val days: Map<ReleaseDay, DayCounts>, val sent: Set<ReleaseDay>) {
    fun toJson(): JSONObject {
        val dayList = JSONArray()
        for ((key, counts) in days) dayList.put(counts.toJson().putReleaseDay(key))
        val sentList = JSONArray()
        for (key in sent) sentList.put(JSONObject().putReleaseDay(key))
        return JSONObject().put("format", REPORT_FORMAT).put("days", dayList).put("sent", sentList)
    }

    companion object {
        /** A file as `toJson` wrote it; one from a library before 1.2.0 has no `sent`, as it reported no day twice. */
        fun of(document: JSONObject) = KeptMonitoring(
            days = document.getJSONArray("days").objects().associate { releaseDayOf(it) to DayCounts.fromJson(it) },
            sent = document.optJSONArray("sent")?.objects().orEmpty().map(::releaseDayOf).toSet(),
        )
    }
}

private fun JSONObject.putReleaseDay(key: ReleaseDay): JSONObject = put("release", key.release).put("day", utcDayText(key.day))

private fun releaseDayOf(document: JSONObject) = ReleaseDay(document.getLong("release"), utcDayOf(document.getString("day")))

private fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }

private fun confidenceStep(confidence: Double): Int {
    var step = 0
    while (step < CONFIDENCE_STEPS.size && confidence >= CONFIDENCE_STEPS[step]) step++
    return step
}

private fun speedStep(nanos: Long): Int {
    var step = 0
    while (step < SPEED_STEPS_MILLIS.size && nanos >= SPEED_STEPS_MILLIS[step] * NANOS_PER_MILLI) step++
    return step
}

private fun LongArray.toJson(): JSONArray = JSONArray().also { array -> forEach { array.put(it) } }

private fun JSONArray.readInto(steps: LongArray) {
    if (length() != steps.size) throw JSONException("expected ${steps.size} steps, found ${length()}")
    for (step in steps.indices) steps[step] = getLong(step)
}
