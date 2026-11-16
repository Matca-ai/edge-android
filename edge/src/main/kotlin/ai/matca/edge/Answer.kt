package ai.matca.edge

import ai.matca.edge.preprocessing.RgbImage
import ai.matca.edge.preprocessing.exifOrientation
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

public data class Score(val label: String, val confidence: Double)

/** What the model said about a picture: the top label, its confidence, every score sorted, and the release that answered. */
public data class Answer(val label: String, val confidence: Double, val all: List<Score>, val release: Long)

/**
 * A picture as the library takes it, with the moment it was taken in epoch milliseconds, which a `Watch` paces by.
 * An app makes one with `Bitmap.toFrame` or `Frame.fromJpeg`.
 */
public class Frame internal constructor(
    internal val image: RgbImage,
    internal val orientation: Int = 1,
    public val time: Long = System.currentTimeMillis(),
) {
    public companion object {
        internal fun decoded(jpeg: ByteArray, decoder: JpegDecoder, time: Long = System.currentTimeMillis()): Frame =
            Frame(decoder.decode(jpeg), exifOrientation(jpeg), time)
    }
}

/**
 * What the app configures: where the edge is, the delivery key for one model and channel, the root key
 * that signs or certifies every manifest, and the optional labels, bundled release and network rules.
 * `monitoring` counts how each release does and sends those counts once a day; the app that leaves it on
 * needs its own legal basis for it, and false counts and sends nothing and deletes what was kept.
 */
public data class EdgeConfiguration(
    val baseUrl: String,
    val deliveryKey: String,
    val rootPublicKey: String,
    val expectedLabels: List<String> = emptyList(),
    val bundledRelease: File? = null,
    val wifiOnly: Boolean = false,
    val checkInterval: Duration = 6.hours,
    val monitoring: Boolean = true,
)
