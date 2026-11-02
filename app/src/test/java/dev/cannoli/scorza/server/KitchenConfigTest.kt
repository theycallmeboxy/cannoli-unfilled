package dev.cannoli.scorza.server

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/** Kitchen reads and replaces folders.json and emulators.json through /api/config. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KitchenConfigTest {

    private var server: KitchenHttpServer? = null
    private var root: File? = null
    private val port = 17197

    @After fun tearDown() {
        server?.stopServer()
        root?.deleteRecursively()
    }

    private var saved = "{}"

    private fun start() {
        val dir = File.createTempFile("cannoli", "").also { it.delete(); it.mkdirs() }.also { root = it }
        val assets = ApplicationProvider.getApplicationContext<Context>().assets
        val file = KitchenConfigFile(
            read = { saved },
            problems = { listOf("one problem") },
            save = { text ->
                if (text.startsWith("{")) {
                    saved = text
                    KitchenConfigFile.SaveResult(true, emptyList())
                } else {
                    KitchenConfigFile.SaveResult(false, listOf("not valid JSON"))
                }
            },
        )
        val s = KitchenHttpServer(dir, assets, port = port, pin = PIN, configFiles = mapOf("folders.json" to file))
        s.startServer()
        repeat(50) {
            try {
                URL("http://127.0.0.1:$port/api/auth").openConnection()
                    .also { c -> (c as HttpURLConnection).connect(); c.disconnect() }
                return@repeat
            } catch (_: Exception) { Thread.sleep(40) }
        }
        server = s
    }

    private fun call(method: String, path: String, body: String? = null): Pair<Int, String> {
        val conn = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString("nonna:$PIN".toByteArray()))
        if (body != null) {
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.readBytes()?.decodeToString() ?: ""
        conn.disconnect()
        return code to text
    }

    @Test fun `get returns the text and its problems`() {
        start()
        val (code, body) = call("GET", "/api/config/folders.json")
        assertEquals(200, code)
        val json = JSONObject(body)
        assertEquals("{}", json.getString("text"))
        assertEquals("one problem", json.getJSONArray("problems").getString(0))
    }

    @Test fun `put saves valid text and refuses the rest with 422`() {
        start()
        val (ok, okBody) = call("PUT", "/api/config/folders.json", """{"folders":[]}""")
        assertEquals(200, ok)
        assertTrue(JSONObject(okBody).getBoolean("saved"))
        assertEquals("""{"folders":[]}""", saved)

        val (bad, badBody) = call("PUT", "/api/config/folders.json", "nope")
        assertEquals(422, bad)
        assertFalse(JSONObject(badBody).getBoolean("saved"))
        assertEquals("""{"folders":[]}""", saved)
    }

    @Test fun `only listed files are reachable`() {
        start()
        assertEquals(404, call("GET", "/api/config/settings.json").first)
        assertEquals(404, call("GET", "/api/config/../cannoli.db").first)
        val (code, body) = call("GET", "/api/config")
        assertEquals(200, code)
        assertEquals("folders.json", JSONObject(body).getJSONArray("files").getString(0))
    }

    private companion object {
        const val PIN = "1234"
    }
}
