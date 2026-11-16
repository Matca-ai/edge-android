package ai.matca.edge

import java.io.File
import java.util.Properties
import kotlin.random.Random
import org.json.JSONException
import org.json.JSONObject

private const val STATE_FILE = "state.properties"
private const val STATE_TEMPORARY = "state.properties.tmp"
private const val STATE_UNREADABLE = "state.properties.unreadable"
private const val LAST_SEQUENCE = "lastSequence"
private const val ROLLOUT_NUMBER = "rolloutNumber"
private const val CURRENT_DIRECTORY = "current"
private const val MANIFEST_FILE = "manifest.txt"
private const val RELEASE_DIRECTORY_PREFIX = "release-"
private const val ROLLOUT_NUMBERS = 100
private const val UNREADABLE_SUFFIX = ".unreadable"

/** A release as it sits on this device: its directory with the manifest text it was taken from. */
internal data class InstalledRelease(val directory: File, val manifestText: String)

/**
 * The library's own directory: one directory per release and a state file with the last accepted
 * sequence, the rollout number drawn once and the pointer to the current release. The pointer moves by
 * renaming a whole new state file over the old one, so a crash leaves either release, never neither.
 */
internal class ModelStore(private val root: File) {
    init {
        root.mkdirs()
    }

    fun lastSequence(): Long = loadOrCreateState().getProperty(LAST_SEQUENCE)?.toLongOrNull() ?: 0L

    fun rolloutNumber(): Int = loadOrCreateState().getProperty(ROLLOUT_NUMBER).toInt()

    fun rememberSequence(sequence: Long) {
        write(loadOrCreateState().apply { setProperty(LAST_SEQUENCE, sequence.toString()) })
    }

    fun current(): InstalledRelease? {
        val name = loadOrCreateState().getProperty(CURRENT_DIRECTORY) ?: return null
        val directory = File(root, name)
        val manifest = File(directory, MANIFEST_FILE)
        return if (manifest.isFile) InstalledRelease(directory, manifest.readText(Charsets.UTF_8)) else null
    }

    /** A fresh directory for a release; named after the release and its sequence, so a move back collides with nothing. */
    fun newReleaseDirectory(release: Long, sequence: Long): File {
        val directory = File(root, "$RELEASE_DIRECTORY_PREFIX$release-$sequence")
        directory.deleteRecursively()
        directory.mkdirs()
        return directory
    }

    /** A file under a release directory; a path that could leave it is refused before anything is written. */
    fun writeFile(directory: File, relativePath: String, bytes: ByteArray) {
        require(SAFE_PATH.matches(relativePath)) { "unsafe path $relativePath" }
        val file = File(directory, relativePath)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }

    /** The pointer onto the new directory and the sequence remembered, in one atomic state write. */
    fun switchTo(directory: File, manifestText: String, sequence: Long) {
        File(directory, MANIFEST_FILE).writeText(manifestText, Charsets.UTF_8)
        write(
            loadOrCreateState().apply {
                setProperty(CURRENT_DIRECTORY, directory.name)
                setProperty(LAST_SEQUENCE, sequence.toString())
            },
        )
    }

    fun discard(directory: File) {
        if (directory.parentFile == root) directory.deleteRecursively()
    }

    /** Also what a process killed mid-download or a release that no longer loads left behind. */
    fun discardAllExcept(kept: File) {
        releaseDirectories().filter { it != kept }.forEach { it.deleteRecursively() }
    }

    /** A JSON document kept beside the releases, read by `read`; one that cannot be read is moved aside as `<name>.unreadable`. */
    fun <T> readDocument(name: String, read: (JSONObject) -> T): T? {
        val file = File(root, name)
        if (!file.isFile) return null
        return try {
            read(JSONObject(file.readText(Charsets.UTF_8)))
        } catch (_: JSONException) {
            file.renameTo(File(root, name + UNREADABLE_SUFFIX))
            null
        }
    }

    /** Written under a temporary name and renamed over the old one, so a killed process leaves either document whole. */
    fun writeDocument(name: String, document: JSONObject) {
        val temporary = File(root, "$name.tmp")
        temporary.writeText(document.toString(), Charsets.UTF_8)
        check(temporary.renameTo(File(root, name))) { "$name could not be replaced" }
    }

    /** The document and the copy of it that was set aside as unreadable. */
    fun deleteDocument(name: String) {
        File(root, name).delete()
        File(root, name + UNREADABLE_SUFFIX).delete()
    }

    /** The state file, drawing the rollout number when there is none yet; an unreadable one is set aside and rebuilt. */
    private fun loadOrCreateState(): Properties {
        val file = File(root, STATE_FILE)
        if (!file.isFile) return write(freshState())
        val properties = runCatching { Properties().apply { file.inputStream().use { load(it) } } }.getOrNull()
        if (properties?.getProperty(ROLLOUT_NUMBER)?.toIntOrNull() != null) return properties
        file.renameTo(File(root, STATE_UNREADABLE))
        return write(rebuiltState())
    }

    private fun freshState() = Properties().apply { setProperty(ROLLOUT_NUMBER, Random.nextInt(ROLLOUT_NUMBERS).toString()) }

    /** The newest release kept on this device gives back the pointer and a floor for the sequence. */
    private fun rebuiltState(): Properties {
        val installed = releaseDirectories().mapNotNull { directory ->
            runCatching { directory to Manifest.parse(File(directory, MANIFEST_FILE).readText(Charsets.UTF_8)).sequence }.getOrNull()
        }
        val state = freshState()
        installed.maxByOrNull { it.second }?.let { (directory, sequence) ->
            state.setProperty(CURRENT_DIRECTORY, directory.name)
            state.setProperty(LAST_SEQUENCE, sequence.toString())
        }
        return state
    }

    private fun releaseDirectories(): List<File> =
        root.listFiles { file -> file.isDirectory && file.name.startsWith(RELEASE_DIRECTORY_PREFIX) }.orEmpty().toList()

    private fun write(properties: Properties): Properties {
        val temporary = File(root, STATE_TEMPORARY)
        temporary.outputStream().use { properties.store(it, null) }
        check(temporary.renameTo(File(root, STATE_FILE))) { "the state file could not be replaced" }
        return properties
    }
}
