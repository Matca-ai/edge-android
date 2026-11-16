package ai.matca.edge.sample

import android.net.Uri
import android.os.Build
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

private const val TIMEOUT_MILLIS = 30_000
private val PAIRING_CODE = Regex("^[A-Za-z0-9_-]{20,64}$")
// What the control plane takes for a phone's name, system and app version.
private val NOT_A_DEVICE_WORD = Regex("[^A-Za-z0-9 .,()_+-]")

/** What the edge's install page opens: matcaedge://pair?edge=https://…&code=…&platform=android. */
data class PairingLink(val edgeUrl: String, val code: String) {
    companion object {
        fun from(uri: Uri?): PairingLink? {
            if (uri == null || uri.scheme != "matcaedge" || uri.host != "pair") return null
            val edge = uri.getQueryParameter("edge")?.trimEnd('/') ?: return null
            val code = uri.getQueryParameter("code") ?: return null
            if (!edge.startsWith("https://") || !PAIRING_CODE.matches(code)) return null
            return PairingLink(edge, code)
        }
    }
}

/** What the pairing gives the app: the four fields it would otherwise ask a person to type. */
data class Paired(val edgeUrl: String, val key: String, val model: String, val rootPublicKey: String)

/** A picture of a try this phone has not answered yet. */
data class OpenPicture(val tryId: Int, val position: Int)

/**
 * The test phone's side of trying pictures (docs/on-device-models-plan.md, section 5): the pairing, the tries it
 * answers and the answers it sends. Plain HTTPS to the edge with the paired key; only answers and timings leave the
 * phone, never a picture.
 */
class TestPhone(private val edgeUrl: String, private val key: String, private val model: String) {
    fun openPictures(): List<OpenPicture> {
        val tries = org.json.JSONArray(request("$edgeUrl/v1/models/$model/tries", key, "GET", null).decodeToString())
        return (0 until tries.length()).flatMap { index ->
            val attempt = tries.getJSONObject(index)
            val pictures = attempt.getJSONArray("pictures")
            (0 until pictures.length())
                .map { pictures.getJSONObject(it) }
                .filter { !it.getBoolean("answered") }
                .map { OpenPicture(attempt.getInt("id"), it.getInt("position")) }
        }
    }

    fun picture(open: OpenPicture): ByteArray =
        request("$edgeUrl/v1/models/$model/tries/${open.tryId}/pictures/${open.position}", key, "GET", null)

    /** One answer: a try's picture, or one taken here under its name; the scores by label, the time it took. */
    fun sendAnswer(open: OpenPicture?, picture: String, release: Long, scores: Map<String, Double>, milliseconds: Double) {
        val body = JSONObject()
            .put("picture", picture.take(80))
            .put("release", release)
            .put("scores", JSONObject(scores))
            .put("milliseconds", milliseconds.coerceIn(0.0, 60_000.0))
        if (open != null) body.put("try_id", open.tryId).put("position", open.position)
        request("$edgeUrl/v1/models/$model/answers", key, "POST", body.toString())
    }

    companion object {
        /** The code spent once for this phone's key on the test channel. */
        fun pair(link: PairingLink, appVersion: String): Paired {
            val body = JSONObject()
                .put("platform", "android")
                .put("device", deviceWords("${Build.MANUFACTURER} ${Build.MODEL}"))
                .put("system", deviceWords("Android ${Build.VERSION.RELEASE}"))
                .put("app_version", deviceWords(appVersion))
            val paired = JSONObject(request("${link.edgeUrl}/v1/pairings/${link.code}", null, "POST", body.toString()).decodeToString())
            return Paired(link.edgeUrl, paired.getString("key"), paired.getString("model"), paired.getString("root_public_key"))
        }

        fun deviceWords(text: String): String = text.replace(NOT_A_DEVICE_WORD, " ").trim().take(60).ifEmpty { "Android" }

        private fun request(address: String, key: String?, method: String, body: String?): ByteArray {
            val connection = URL(address).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = TIMEOUT_MILLIS
                connection.readTimeout = TIMEOUT_MILLIS
                key?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(body.encodeToByteArray()) }
                }
                val status = connection.responseCode
                if (status !in 200..299) throw IOException(refusal(status))
                return connection.inputStream.use { it.readBytes() }
            } finally {
                connection.disconnect()
            }
        }

        private fun refusal(status: Int): String = when (status) {
            401 -> "the edge does not know this phone's key: pair it again"
            404 -> "the edge does not have that any more: a pairing code lasts an hour and pairs once"
            429 -> "the edge asks to wait a moment"
            else -> "the edge answered $status"
        }
    }
}
