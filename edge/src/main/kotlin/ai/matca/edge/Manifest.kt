package ai.matca.edge

import ai.matca.edge.preprocessing.Illumination
import ai.matca.edge.preprocessing.PreprocessingSteps
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal const val MANIFEST_FORMAT = 1L
internal const val PLATFORM = "android"
internal const val REFERENCE_PLATFORM = "reference"

/** The manifest's exact text, which is what the signature covers, as the edge answers it and a bundle holds it. */
internal data class SignedManifest(val text: String, val signature: String) {
    companion object {
        fun parse(json: String): SignedManifest = readingJson {
            val document = JSONObject(json)
            SignedManifest(text = document.getString("manifest"), signature = document.getString("signature"))
        }
    }
}

/** A path as a release may name one: segments of plain characters, nowhere near the directory above. */
internal val SAFE_PATH = Regex("""[A-Za-z0-9_-][A-Za-z0-9_.-]*(/[A-Za-z0-9_-][A-Za-z0-9_.-]*)*""")

internal data class ManifestFile(val path: String, val size: Long, val sha256: String) {
    init {
        if (!SAFE_PATH.matches(path)) throw EdgeError.InvalidManifest("files.path")
    }
}

internal data class ReferencePicture(val path: String, val size: Long, val sha256: String, val expected: String, val minimumConfidence: Double) {
    init {
        if (!SAFE_PATH.matches(path)) throw EdgeError.InvalidManifest("reference.path")
    }
}

internal data class SigningCertificate(val expires: String, val signature: String)

/** The one part of a manifest read before its signature verified: who says they signed it. */
internal data class SigningBlock(val key: String, val certificate: SigningCertificate?) {
    companion object {
        fun of(text: String): SigningBlock = readingJson {
            val signing = JSONObject(text).getJSONObject("signing")
            SigningBlock(
                key = signing.getString("key"),
                certificate = signing.optJSONObject("certificate")?.let {
                    SigningCertificate(expires = it.getString("expires"), signature = it.getString("signature"))
                },
            )
        }
    }
}

/** A release as the control plane describes it, read only after its signature verified. Other fields are ignored. */
internal data class Manifest(
    val format: Long,
    val model: String,
    val release: Long,
    val sequence: Long,
    val rolloutShare: Long,
    val classes: List<String>,
    val inputWidth: Int,
    val inputHeight: Int,
    val preprocessing: PreprocessingSteps,
    val requires: List<String>,
    val files: List<ManifestFile>,
    val reference: List<ReferencePicture>,
) {
    companion object {
        fun parse(text: String): Manifest = readingJson {
            val document = JSONObject(text)
            val input = document.getJSONObject("input")
            Manifest(
                format = document.getLong("format"),
                model = document.getString("model"),
                release = document.getLong("release"),
                sequence = document.getLong("sequence"),
                rolloutShare = document.getLong("rollout_share"),
                classes = document.getJSONArray("classes").strings(),
                inputWidth = input.getInt("width"),
                inputHeight = input.getInt("height"),
                preprocessing = preprocessingOf(document.getJSONObject("preprocessing")),
                requires = document.getJSONArray("requires").strings(),
                // A platform's file list may be absent: the release is then for the other platform.
                files = document.getJSONObject("files").optJSONArray(PLATFORM)?.objects(::fileOf).orEmpty(),
                reference = document.getJSONArray("reference").objects(::referenceOf),
            )
        }

        private fun preprocessingOf(document: JSONObject) = PreprocessingSteps(
            fixOrientation = document.getBoolean("fix_orientation"),
            centreSquareCrop = document.getBoolean("centre_square_crop"),
            cropFactor = if (document.isNull("crop_factor")) null else document.getDouble("crop_factor"),
            circleMask = document.getBoolean("circle_mask"),
            illumination = document.optJSONObject("illumination")?.let {
                Illumination(
                    sigmaPercent = it.getDouble("sigma_percent"),
                    percentileLow = it.getDouble("percentile_low"),
                    percentileHigh = it.getDouble("percentile_high"),
                    smoothing = it.getBoolean("smoothing"),
                )
            },
        )

        private fun fileOf(document: JSONObject) =
            ManifestFile(path = document.getString("path"), size = document.getLong("size"), sha256 = document.getString("sha256"))

        private fun referenceOf(document: JSONObject) = ReferencePicture(
            path = document.getString("path"),
            size = document.getLong("size"),
            sha256 = document.getString("sha256"),
            expected = document.getString("expected"),
            minimumConfidence = document.getDouble("minimum_confidence"),
        )
    }
}

private inline fun <T> readingJson(read: () -> T): T = try {
    read()
} catch (error: JSONException) {
    throw EdgeError.InvalidManifest(error.message ?: "not the expected JSON")
}

private fun JSONArray.strings(): List<String> = List(length()) { getString(it) }

private fun <T> JSONArray.objects(read: (JSONObject) -> T): List<T> = List(length()) { read(getJSONObject(it)) }
