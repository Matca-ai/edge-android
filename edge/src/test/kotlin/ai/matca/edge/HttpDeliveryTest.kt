package ai.matca.edge

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/** The edge's API over a real socket: the headers every call carries, the answers and the refusals. */
internal class HttpDeliveryTest {
    private lateinit var server: HttpServer
    private val requests = mutableListOf<HttpExchange>()
    private val requestBodies = mutableListOf<String>()
    private val bodies = mutableMapOf<String, Pair<Int, ByteArray>>()

    @Before
    fun startTheFakeEdge() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests += exchange
            requestBodies += String(exchange.requestBody.readBytes(), Charsets.UTF_8)
            val (status, body) = bodies[exchange.requestMethod + " " + exchange.requestURI.path] ?: (404 to ByteArray(0))
            exchange.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
            exchange.responseBody.use { if (body.isNotEmpty()) it.write(body) }
        }
        server.start()
    }

    @After
    fun stopTheFakeEdge() = server.stop(0)

    private fun delivery() = HttpDelivery("http://127.0.0.1:${server.address.port}/", "mdk_id_secret", "0.1.0")

    @Test
    fun theManifestCallCarriesTheKeyAndTheUserAgent() = runBlocking {
        val answer = """{"release":3,"manifest":"{\"a\":1}","signature":"c2ln"}"""
        bodies["GET /v1/models/cap-detection/manifest"] = 200 to answer.toByteArray()
        val signed = delivery().manifest("cap-detection")
        assertEquals(SignedManifest("""{"a":1}""", "c2ln"), signed)
        assertEquals("Bearer mdk_id_secret", requests.single().requestHeaders.getFirst("Authorization"))
        assertEquals("matca-edge-android/0.1.0", requests.single().requestHeaders.getFirst("User-Agent"))
    }

    @Test
    fun aFileIsReadUpToItsSize() = runBlocking<Unit> {
        bodies["GET /v1/models/cap-detection/releases/3/files/android/model.tflite"] = 200 to "hello".toByteArray()
        assertEquals("hello", String(delivery().file("cap-detection", 3, "android", "model.tflite", 5)))
        assertThrows(EdgeError.FileMismatch::class.java) {
            runBlocking { delivery().file("cap-detection", 3, "android", "model.tflite", 4) }
        }
    }

    @Test
    fun aManifestAnswerOverOneMebibyteIsRefused() {
        val answer = """{"manifest":"m","signature":"s"}"""
        bodies["GET /v1/models/cap-detection/manifest"] = 200 to (answer + " ".repeat(1 shl 20)).toByteArray()
        assertThrows(EdgeError.InvalidManifest::class.java) { runBlocking { delivery().manifest("cap-detection") } }
    }

    @Test
    fun aRedirectIsNotFollowed() {
        server.createContext("/moved") { exchange ->
            exchange.responseHeaders.add("Location", "http://127.0.0.1:${server.address.port}/elsewhere")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        val error = assertThrows(EdgeError.Edge::class.java) {
            runBlocking { HttpDelivery("http://127.0.0.1:${server.address.port}/moved", "mdk_id_secret", "0.1.0").manifest("cap-detection") }
        }
        assertEquals(302, error.status)
        assertEquals(emptyList<String>(), requests.map { it.requestURI.path })
    }

    @Test
    fun theCountsArePostedWithoutABody() = runBlocking {
        bodies["POST /v1/models/cap-detection/releases/3/installs"] = 204 to ByteArray(0)
        bodies["POST /v1/models/cap-detection/releases/3/failed-checks"] = 204 to ByteArray(0)
        delivery().countInstall("cap-detection", 3)
        delivery().countFailedCheck("cap-detection", 3)
        assertEquals(listOf("POST", "POST"), requests.map { it.requestMethod })
    }

    @Test
    fun aReportIsPostedAsJsonWithTheKeyAndTheUserAgent() = runBlocking {
        bodies["POST /v1/models/cap-detection/releases/3/report"] = 204 to ByteArray(0)
        val report = """{"format":1,"day":"2026-11-03"}"""
        delivery().sendReport("cap-detection", 3, report)
        val request = requests.single()
        assertEquals(report, requestBodies.single())
        assertEquals("application/json", request.requestHeaders.getFirst("Content-Type"))
        assertEquals("Bearer mdk_id_secret", request.requestHeaders.getFirst("Authorization"))
        assertEquals("matca-edge-android/0.1.0", request.requestHeaders.getFirst("User-Agent"))
    }

    @Test
    fun aRefusedReportCarriesTheStatus() {
        bodies["POST /v1/models/cap-detection/releases/3/report"] = 413 to ByteArray(0)
        val error = assertThrows(EdgeError.Edge::class.java) { runBlocking { delivery().sendReport("cap-detection", 3, "{}") } }
        assertEquals(413, error.status)
    }

    @Test
    fun aRefusalCarriesTheStatus() {
        bodies["GET /v1/models/cap-detection/manifest"] = 401 to "no such key".toByteArray()
        val error = assertThrows(EdgeError.Edge::class.java) { runBlocking { delivery().manifest("cap-detection") } }
        assertEquals(401, error.status)
    }

    @Test
    fun anAddressThatIsNoHttpUrlIsADeliveryError() {
        for (address in listOf("edge.example.eu", "ftp://edge.example.eu")) {
            val error = assertThrows(EdgeError.Edge::class.java) {
                runBlocking { HttpDelivery(address, "mdk_id_secret", "0.1.0").manifest("cap-detection") }
            }
            assertEquals(0, error.status)
        }
    }

    @Test
    fun anUnreachableEdgeIsADeliveryError() {
        server.stop(0)
        val error = assertThrows(EdgeError.Edge::class.java) { runBlocking { delivery().manifest("cap-detection") } }
        assertEquals(0, error.status)
    }
}
