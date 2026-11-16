package ai.matca.edge

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The edge's delivery API as the library uses it; a fake stands in for it in every test but the HTTP one. */
internal interface Delivery {
    suspend fun manifest(model: String): SignedManifest

    /** One file of a release, at most `size` bytes: a longer body cannot match the manifest anyway. */
    suspend fun file(model: String, release: Long, platform: String, path: String, size: Long): ByteArray

    suspend fun countInstall(model: String, release: Long)

    suspend fun countFailedCheck(model: String, release: Long)

    /** One finished day's counts for a release, as JSON (contract, section 11). */
    suspend fun sendReport(model: String, release: Long, report: String)
}

private const val CONNECT_TIMEOUT_MILLIS = 10_000
private const val READ_TIMEOUT_MILLIS = 60_000
private const val MANIFEST_LIMIT_BYTES = 1L shl 20

/** The delivery API over `HttpURLConnection`: a bearer key, the library's user agent and bounded reads. */
internal class HttpDelivery(
    baseUrl: String,
    private val deliveryKey: String,
    private val version: String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : Delivery {
    private val baseUrl = baseUrl.trimEnd('/')

    override suspend fun manifest(model: String): SignedManifest {
        val body = get("/v1/models/$model/manifest", MANIFEST_LIMIT_BYTES) { EdgeError.InvalidManifest("the answer is over 1 MiB") }
        return SignedManifest.parse(String(body, Charsets.UTF_8))
    }

    override suspend fun file(model: String, release: Long, platform: String, path: String, size: Long): ByteArray =
        get("/v1/models/$model/releases/$release/files/$platform/$path", size) { EdgeError.FileMismatch(path) }

    override suspend fun countInstall(model: String, release: Long) = post("/v1/models/$model/releases/$release/installs")

    override suspend fun countFailedCheck(model: String, release: Long) = post("/v1/models/$model/releases/$release/failed-checks")

    override suspend fun sendReport(model: String, release: Long, report: String) =
        post("/v1/models/$model/releases/$release/report", report.toByteArray(Charsets.UTF_8))

    private suspend fun get(path: String, limit: Long, tooLong: () -> EdgeError): ByteArray = exchange(path, "GET", null) { connection ->
        connection.inputStream.use { readBounded(it, limit, tooLong) }
    }

    private suspend fun post(path: String, jsonBody: ByteArray = ByteArray(0)) = exchange(path, "POST", jsonBody) {}

    /** One request on the IO dispatcher; a status outside 2xx or a failed connection is an `EdgeError.Edge`. */
    private suspend fun <T> exchange(path: String, method: String, body: ByteArray?, read: (HttpURLConnection) -> T): T =
        withContext(ioDispatcher) {
            try {
                val connection = open(path, method, body)
                try {
                    body?.let { bytes -> connection.outputStream.use { it.write(bytes) } }
                    val status = connection.responseCode
                    if (status !in 200..299) throw EdgeError.Edge(status, path)
                    read(connection)
                } finally {
                    connection.disconnect()
                }
            } catch (error: IOException) {
                throw EdgeError.Edge(0, error.javaClass.simpleName)
            }
        }

    private fun open(path: String, method: String, body: ByteArray?): HttpURLConnection {
        val connection = URL(baseUrl + path).openConnection() as? HttpURLConnection
            ?: throw MalformedURLException("the edge's address is not HTTP")
        connection.requestMethod = method
        connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
        connection.readTimeout = READ_TIMEOUT_MILLIS
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Authorization", "Bearer $deliveryKey")
        connection.setRequestProperty("User-Agent", "matca-edge-android/$version")
        if (body != null) {
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            if (body.isNotEmpty()) connection.setRequestProperty("Content-Type", "application/json")
        }
        return connection
    }

    private fun readBounded(stream: InputStream, limit: Long, tooLong: () -> EdgeError): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) return out.toByteArray()
            out.write(buffer, 0, read)
            if (out.size() > limit) throw tooLong()
        }
    }
}
