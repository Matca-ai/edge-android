package ai.matca.edge

import ai.matca.edge.preprocessing.RgbImage
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import javax.imageio.ImageIO
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.json.JSONObject
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/** JPEG and PNG fixtures decoded with `javax.imageio`, which ignores EXIF as `BitmapFactory` does. */
internal object ImageIoDecoder : JpegDecoder {
    override fun decode(jpeg: ByteArray): RgbImage {
        val image = ImageIO.read(jpeg.inputStream()) ?: throw EdgeError.UndecodablePicture()
        val argb = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        return RgbImage.fromArgb(image.width, image.height, argb)
    }
}

internal fun fixture(name: String): ByteArray =
    checkNotNull(ImageIoDecoder.javaClass.getResourceAsStream("/fixtures/$name")) { "no fixture $name" }.use { it.readBytes() }

internal fun fixtureJson(name: String) = JSONObject(String(fixture(name), Charsets.UTF_8))

/** A P-256 key pair for a test, with its public half as the libraries carry it. */
internal class TestKey {
    private val pair: KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    val publicKeyBase64: String = Base64.getEncoder().encodeToString(pair.public.encoded)

    fun sign(text: String): String = Signature.getInstance("SHA256withECDSA").run {
        initSign(pair.private)
        update(text.toByteArray(Charsets.UTF_8))
        Base64.getEncoder().encodeToString(sign())
    }
}

/** A manifest with the fields a test varies; the libraries read nothing else of what the control plane writes. */
internal fun manifestText(
    signingKey: String,
    sequence: Long = 7,
    release: Long = 3,
    rolloutShare: Int = 100,
    format: Int = 1,
    model: String = "cap-detection",
    classes: List<String> = listOf("With Cap", "Without Cap"),
    files: List<ManifestFile> = listOf(ManifestFile("model.tflite", 5, sha256Hex("hello".toByteArray()))),
    reference: List<ReferencePicture> = emptyList(),
    requires: List<String> = listOf("fix_orientation", "centre_square_crop"),
    certificate: String = "null",
): String {
    val fileList = files.joinToString(",") { """{"path":"${it.path}","sha256":"${it.sha256}","size":${it.size}}""" }
    val referenceList = reference.joinToString(",") {
        """{"expected":"${it.expected}","minimum_confidence":${it.minimumConfidence},""" +
            """"path":"${it.path}","sha256":"${it.sha256}","size":${it.size}}"""
    }
    return """{"classes":[${classes.joinToString(",") { "\"$it\"" }}],"files":{"android":[$fileList],"ios":[]},""" +
        """"format":$format,"input":{"height":224,"width":224},"model":"$model",""" +
        """"preprocessing":{"centre_square_crop":true,"circle_mask":false,"crop_factor":null,"fix_orientation":true,""" +
        """"illumination":null},"reference":[$referenceList],"release":$release,""" +
        """"requires":[${requires.joinToString(",") { "\"$it\"" }}],"rollout_share":$rolloutShare,"sequence":$sequence,""" +
        """"signing":{"certificate":$certificate,"key":"$signingKey"}}"""
}

internal fun TestKey.signed(text: String): SignedManifest = SignedManifest(text, sign(text))

/** The edge in memory: one manifest, the files by `platform/path`, the two counts, and the reports with the status it answers them. */
internal class FakeDelivery(var manifest: SignedManifest? = null, val files: MutableMap<String, ByteArray> = mutableMapOf()) : Delivery {
    var installs = 0
    var failedChecks = 0
    var fileRequests = 0
    val requestedReleases = mutableSetOf<Long>()
    val reports = java.util.Collections.synchronizedList(mutableListOf<SentReport>())
    var reportStatus = 204

    override suspend fun manifest(model: String): SignedManifest = manifest ?: throw EdgeError.Edge(404, "nothing on the channel")

    override suspend fun file(model: String, release: Long, platform: String, path: String, size: Long): ByteArray {
        fileRequests++
        requestedReleases += release
        return files["$platform/$path"] ?: throw EdgeError.Edge(404, path)
    }

    override suspend fun countInstall(model: String, release: Long) {
        installs++
    }

    override suspend fun countFailedCheck(model: String, release: Long) {
        failedChecks++
    }

    override suspend fun sendReport(model: String, release: Long, report: String) {
        reports += SentReport(release, JSONObject(report), reportStatus)
        if (reportStatus !in 200..299) throw EdgeError.Edge(reportStatus, "report")
    }
}

/** A JSON value as nested sorted maps, lists and longs, so two documents compare by content and print readably. */
internal fun comparable(value: Any?): Any? = when (value) {
    is JSONObject -> value.keys().asSequence().sorted().associateWith { comparable(value.get(it)) }
    is org.json.JSONArray -> List(value.length()) { comparable(value.get(it)) }
    is Int, is Long -> (value as Number).toLong()
    else -> value
}

/** A report as the edge received it, with the status it answered. */
internal data class SentReport(val release: Long, val report: JSONObject, val status: Int) {
    val day: String get() = report.getString("day")
}

/** A model that answers the same scores for every picture and remembers whether it was closed. */
internal class FakeModelLoader(private val scores: FloatArray = floatArrayOf(0.9f, 0.1f)) : ModelLoader {
    val loaded = mutableListOf<File>()
    val closed = mutableListOf<File>()

    override fun load(modelFile: File): ModelRunner {
        require(modelFile.isFile) { "no model file at $modelFile" }
        loaded += modelFile
        return object : ModelRunner {
            override fun run(input: FloatArray): FloatArray = scores.copyOf()

            override fun close() {
                closed += modelFile
            }
        }
    }
}

internal fun solidFrame(grey: Int, width: Int = 64, height: Int = 48, time: Long = 0): Frame =
    Frame(RgbImage(width, height, ByteArray(width * height * RgbImage.CHANNELS) { grey.toByte() }), time = time)

/** What the library logged while `block` ran. */
internal inline fun loggedDuring(block: () -> Unit): List<String> {
    val messages = java.util.Collections.synchronizedList(mutableListOf<String>())
    val listener = object : Handler() {
        override fun publish(record: LogRecord) {
            messages += record.message
        }

        override fun flush() = Unit

        override fun close() = Unit
    }
    val libraryLog = Logger.getLogger("ai.matca.edge")
    libraryLog.addHandler(listener)
    try {
        block()
    } finally {
        libraryLog.removeHandler(listener)
    }
    return messages
}

/** The system version the tests' reports carry, as `sdks/test-fixtures/report.json` has it. */
internal const val SYSTEM_VERSION = "13"

/** A classifier over the fake edge and the fake model, and the manifests a test offers it. */
internal abstract class ClassifierTestBase {
    @get:Rule
    val folder = TemporaryFolder()

    protected val root = TestKey()
    protected val delivery = FakeDelivery()
    protected val loader = FakeModelLoader()
    protected val modelBytes = "hello".toByteArray()
    protected val referenceJpeg = fixture("cap-upright.jpg")
    protected val reference = ReferencePicture("ref.jpg", referenceJpeg.size.toLong(), sha256Hex(referenceJpeg), "With Cap", 0.5)
    protected lateinit var storeRoot: File
    protected lateinit var store: ModelStore

    protected fun configuration(
        expectedLabels: List<String> = emptyList(),
        bundledRelease: File? = null,
        wifiOnly: Boolean = false,
        monitoring: Boolean = true,
    ) = EdgeConfiguration(
        "http://edge.test", "mdk_a_b", root.publicKeyBase64, expectedLabels, bundledRelease, wifiOnly, monitoring = monitoring,
    )

    @Suppress("LongParameterList")
    protected fun classifier(
        configuration: EdgeConfiguration = configuration(),
        edge: Delivery = delivery,
        engine: ModelLoader = loader,
        decoder: JpegDecoder = ImageIoDecoder,
        networkPolicy: NetworkPolicy = NetworkPolicy.ALWAYS,
        clock: () -> Long = System::currentTimeMillis,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        workDispatcher: CoroutineDispatcher = Dispatchers.Default,
        processExits: ProcessExitHistory = ProcessExitHistory.NONE,
        monotonicNanos: () -> Long = System::nanoTime,
        retryDelays: List<Duration> = ASKS_WITHOUT_MODEL,
    ): Classifier {
        storeRoot = folder.newFolder()
        store = ModelStore(storeRoot)
        delivery.files["android/model.tflite"] = modelBytes
        delivery.files["reference/ref.jpg"] = referenceJpeg
        return Classifier(
            "cap-detection", configuration, store, edge, engine, decoder, networkPolicy,
            clock = clock, scope = scope, workDispatcher = workDispatcher, systemVersion = SYSTEM_VERSION,
            processExits = processExits, monotonicNanos = monotonicNanos, retryDelays = retryDelays,
        )
    }

    /** The classifier a restart makes over the same store. */
    protected fun restarted(engine: ModelLoader = loader, edge: Delivery = FakeDelivery(), configuration: EdgeConfiguration = configuration()) =
        Classifier("cap-detection", configuration, ModelStore(storeRoot), edge, engine, ImageIoDecoder)

    protected fun offer(sequence: Long, release: Long = 3, rolloutShare: Int = 100, files: List<ManifestFile>? = null) {
        delivery.manifest = root.signed(
            manifestText(
                root.publicKeyBase64,
                sequence = sequence,
                release = release,
                rolloutShare = rolloutShare,
                reference = listOf(reference),
                files = files ?: listOf(ManifestFile("model.tflite", modelBytes.size.toLong(), sha256Hex(modelBytes))),
            ),
        )
    }

    protected fun releaseDirectories(): List<String> = storeRoot.list()!!.filter { it.startsWith("release-") }.sorted()

    /** A bundled release as an app ships it: `manifest.json`, the files at their paths, `reference/<path>`. */
    protected fun bundle(release: Long = 2, sequence: Long = 5): File {
        val bundled = folder.newFolder()
        val signed = root.signed(
            manifestText(
                root.publicKeyBase64,
                sequence = sequence,
                release = release,
                reference = listOf(reference),
                files = listOf(ManifestFile("model.tflite", 5, sha256Hex(modelBytes))),
            ),
        )
        val answer = JSONObject().put("manifest", signed.text).put("signature", signed.signature)
        File(bundled, "manifest.json").writeText(answer.toString())
        File(bundled, "model.tflite").writeBytes(modelBytes)
        File(bundled, "reference").mkdirs()
        File(bundled, "reference/ref.jpg").writeBytes(referenceJpeg)
        return bundled
    }
}
