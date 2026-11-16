package ai.matca.edge

import ai.matca.edge.preprocessing.PreprocessingSteps
import java.io.File
import java.security.MessageDigest
import java.util.logging.Logger
import kotlinx.coroutines.CancellationException

internal val log: Logger = Logger.getLogger("ai.matca.edge")

/** Where a release's files come from and who hears about the outcome: the edge, or a bundled directory that counts nothing. */
internal interface ReleaseSource {
    suspend fun read(release: Long, platform: String, path: String, size: Long): ByteArray

    suspend fun installed(release: Long)

    suspend fun failedCheck(release: Long)
}

internal class DeliverySource(
    private val delivery: Delivery,
    private val model: String,
    private val mayDownload: () -> Boolean,
) : ReleaseSource {
    override suspend fun read(release: Long, platform: String, path: String, size: Long): ByteArray {
        if (!mayDownload()) throw EdgeError.Edge(0, "waiting for Wi-Fi")
        return delivery.file(model, release, platform, path, size)
    }

    override suspend fun installed(release: Long) = delivery.countInstall(model, release)

    override suspend fun failedCheck(release: Long) = delivery.countFailedCheck(model, release)
}

/** A release the app ships: `manifest.json` as the edge answers it, then `<path>` and `reference/<path>`. */
internal class BundledSource(private val directory: File) : ReleaseSource {
    fun signedManifest(): SignedManifest = SignedManifest.parse(File(directory, "manifest.json").readText(Charsets.UTF_8))

    override suspend fun read(release: Long, platform: String, path: String, size: Long): ByteArray {
        val file = File(directory, if (platform == REFERENCE_PLATFORM) "$REFERENCE_PLATFORM/$path" else path)
        if (!file.isFile) throw EdgeError.FileMismatch(path)
        return file.readBytes()
    }

    override suspend fun installed(release: Long) = Unit

    override suspend fun failedCheck(release: Long) = Unit
}

/** A release with its model loaded on this device. */
internal class LoadedRelease(
    val manifestText: String,
    val manifest: Manifest,
    val directory: File,
    private val runner: ModelRunner,
) : AutoCloseable {
    fun answer(frame: Frame, steps: PreprocessingSteps): Answer = scored(prepared(frame, steps))

    fun prepared(frame: Frame, steps: PreprocessingSteps): FloatArray =
        steps.prepare(frame.image, frame.orientation, manifest.inputWidth, manifest.inputHeight).toFloatArray()

    fun scored(pixels: FloatArray): Answer {
        val scores = try {
            runner.run(pixels)
        } catch (error: Exception) {
            throw EdgeError.PredictionFailed(error.javaClass.simpleName)
        }
        if (scores.size != manifest.classes.size) {
            throw EdgeError.PredictionFailed("${scores.size} values for ${manifest.classes.size} classes")
        }
        if (scores.any { it.isNaN() }) throw EdgeError.PredictionFailed("a score is not a number")
        val all = manifest.classes.indices
            .map { Score(manifest.classes[it], scores[it].toDouble()) }
            .sortedByDescending { it.confidence }
        return Answer(all[0].label, all[0].confidence, all, manifest.release)
    }

    override fun close() = runner.close()
}

/** Steps 2 to 7 of taking a release: verify, read, decide the rollout, download, load, try on this phone. */
internal class Updater(
    private val model: String,
    private val configuration: EdgeConfiguration,
    private val store: ModelStore,
    private val loader: ModelLoader,
    private val decoder: JpegDecoder,
    private val clock: () -> Long,
) {
    /**
     * The release loaded and checked, ready to switch to. Null for a quiet stop, its reason logged; a release
     * that is refused or fails a step throws the reason.
     */
    suspend fun prepare(signed: SignedManifest, source: ReleaseSource, runningRelease: Long?): LoadedRelease? {
        val manifest = verified(signed)
        requireThisModel(manifest)
        val lastSequence = store.lastSequence()
        // With nothing loaded, the release last accepted may come again: it may be the one that no longer loads.
        val seen = manifest.sequence < lastSequence || (manifest.sequence == lastSequence && runningRelease != null)
        if (seen) return quiet("sequence ${manifest.sequence} is not above $lastSequence")
        if (manifest.release == runningRelease) {
            store.rememberSequence(manifest.sequence)
            return quiet("release ${manifest.release} already runs here, sequence ${manifest.sequence} is remembered")
        }
        requireSupported(manifest)
        if (manifest.files.isEmpty()) return quiet("release ${manifest.release} has no files for this platform")
        if (store.rolloutNumber() >= manifest.rolloutShare) {
            store.rememberSequence(manifest.sequence)
            return quiet("release ${manifest.release} is rolled out to ${manifest.rolloutShare}%, not to this device")
        }
        return install(signed, manifest, source)
    }

    /**
     * Loads a release already on this device, as a restart does; null when it no longer loads. It is not
     * verified again, so a root key rotation or an expired certificate does not drop a working model.
     */
    fun restore(installed: InstalledRelease): LoadedRelease? = try {
        load(installed.manifestText, Manifest.parse(installed.manifestText), installed.directory)
    } catch (error: EdgeError) {
        quiet(describe(error))
    }

    private suspend fun install(signed: SignedManifest, manifest: Manifest, source: ReleaseSource): LoadedRelease {
        val directory = store.newReleaseDirectory(manifest.release, manifest.sequence)
        try {
            downloadFiles(manifest, source, directory)
            val pictures = downloadReferences(manifest, source)
            val loaded = load(signed.text, manifest, directory)
            try {
                tryOnThisPhone(loaded, pictures)
            } catch (error: Throwable) {
                loaded.close()
                throw error
            }
            return loaded
        } catch (error: Throwable) {
            store.discard(directory)
            if (error is EdgeError.ReferenceCheckFailed) countQuietly { source.failedCheck(manifest.release) }
            throw error
        }
    }

    private fun verified(signed: SignedManifest): Manifest {
        val signing = SigningBlock.of(signed.text)
        if (!Signing.verifies(signing.key, signed.text, signed.signature)) {
            throw EdgeError.Untrusted("the signature does not verify")
        }
        Signing.trust(signing, configuration.rootPublicKey, clock())
        return Manifest.parse(signed.text)
    }

    private fun requireThisModel(manifest: Manifest) {
        if (manifest.format != MANIFEST_FORMAT) throw EdgeError.Refused("manifest format ${manifest.format} is not $MANIFEST_FORMAT")
        if (manifest.model != model) throw EdgeError.Refused("the manifest is for ${manifest.model}, not $model")
    }

    private fun requireSupported(manifest: Manifest) {
        val missing = manifest.requires.filterNot { it in PreprocessingSteps.FEATURES }
        if (missing.isNotEmpty()) throw EdgeError.Refused("release ${manifest.release} needs a newer library: $missing")
        val unknown = configuration.expectedLabels.filterNot { it in manifest.classes }
        if (unknown.isNotEmpty()) throw EdgeError.Refused("release ${manifest.release} lacks the labels the app expects: $unknown")
    }

    private suspend fun downloadFiles(manifest: Manifest, source: ReleaseSource, directory: File) {
        for (file in manifest.files) {
            val bytes = source.read(manifest.release, PLATFORM, file.path, file.size)
            if (!matches(bytes, file.size, file.sha256)) throw EdgeError.FileMismatch(file.path)
            store.writeFile(directory, file.path, bytes)
        }
    }

    private suspend fun downloadReferences(manifest: Manifest, source: ReleaseSource): List<Pair<ReferencePicture, ByteArray>> =
        manifest.reference.map { picture ->
            val bytes = source.read(manifest.release, REFERENCE_PLATFORM, picture.path, picture.size)
            if (!matches(bytes, picture.size, picture.sha256)) throw EdgeError.FileMismatch(picture.path)
            picture to bytes
        }

    private fun load(manifestText: String, manifest: Manifest, directory: File): LoadedRelease {
        val modelFile = manifest.files.firstOrNull { it.path.endsWith(".tflite") }
            ?: throw EdgeError.ModelLoadFailed("the release lists no .tflite")
        val runner = try {
            loader.load(File(directory, modelFile.path))
        } catch (error: Throwable) {
            // A native library that does not load on this phone is an Error, not an Exception.
            throw EdgeError.ModelLoadFailed(error.javaClass.simpleName)
        }
        return LoadedRelease(manifestText, manifest, directory, runner)
    }

    /** Any failure to answer counts as a failed check: an engine that throws on this phone is what the check is for. */
    private fun tryOnThisPhone(loaded: LoadedRelease, pictures: List<Pair<ReferencePicture, ByteArray>>) {
        for ((picture, bytes) in pictures) {
            val answer = try {
                loaded.answer(Frame.decoded(bytes, decoder), loaded.manifest.preprocessing)
            } catch (error: Exception) {
                log.info("reference picture ${picture.path}: ${describe(error)}")
                throw EdgeError.ReferenceCheckFailed(picture.path)
            }
            if (answer.label != picture.expected || answer.confidence < picture.minimumConfidence) {
                throw EdgeError.ReferenceCheckFailed(picture.path)
            }
        }
    }

    private fun matches(bytes: ByteArray, size: Long, sha256: String): Boolean =
        bytes.size.toLong() == size && sha256Hex(bytes).equals(sha256, ignoreCase = true)

    private fun quiet(reason: String): Nothing? {
        log.info("release not taken: $reason")
        return null
    }
}

/** A count that cannot be sent never changes the outcome of the step it reports. */
internal suspend fun countQuietly(count: suspend () -> Unit) {
    try {
        count()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        log.info("a count was not sent: ${describe(error)}")
    }
}

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
