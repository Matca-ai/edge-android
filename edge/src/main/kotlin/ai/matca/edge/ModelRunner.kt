package ai.matca.edge

import ai.matca.edge.preprocessing.RgbImage
import java.io.File

/** The seam over the platform's engine: pixels in, one score per class out. */
internal interface ModelRunner : AutoCloseable {
    /** Channels-last float32 pixels of the model's input size; the answer is one value per class. */
    fun run(input: FloatArray): FloatArray
}

/** Loads a release's model file; LiteRT on the phone, a fake in the tests. */
internal fun interface ModelLoader {
    fun load(modelFile: File): ModelRunner
}

/** Decodes JPEG bytes to pixels: `BitmapFactory` on the phone, `javax.imageio` in the tests. */
internal fun interface JpegDecoder {
    fun decode(jpeg: ByteArray): RgbImage
}

/** Whether a download may start now; the Wi-Fi-only policy lives in the platform adapter. */
internal fun interface NetworkPolicy {
    fun downloadsAllowed(): Boolean

    companion object {
        val ALWAYS = NetworkPolicy { true }
    }
}

/** How one of the app's earlier processes ended: whether it crashed, and when, in epoch milliseconds. */
internal data class ProcessExit(val crashed: Boolean, val timestampMillis: Long)

/** The system's record of how the app's processes ended: `ApplicationExitInfo` on the phone, a list in the tests. */
internal fun interface ProcessExitHistory {
    fun exits(): List<ProcessExit>

    companion object {
        val NONE = ProcessExitHistory { emptyList() }
    }
}
