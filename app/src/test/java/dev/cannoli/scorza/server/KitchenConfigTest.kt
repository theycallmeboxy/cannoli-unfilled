package dev.cannoli.scorza.server

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.config.library.LibraryConfig
import dev.cannoli.scorza.config.library.LibraryFile
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
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/** Kitchen reads and replaces the config files it is given through /api/config, and serves the templates read-only. */
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

    private fun fakeFile() = KitchenConfigFile(
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

    private fun asset(name: String): String? = try {
        ApplicationProvider.getApplicationContext<Context>().assets.open(name).bufferedReader().use { it.readText() }
    } catch (_: IOException) {
        null
    }

    private fun start(
        apps: List<Pair<String, String>> = emptyList(),
        cores: Map<String, Set<String>> = emptyMap(),
        prepare: (File) -> Unit = {},
        files: (File) -> Map<String, KitchenConfigFile> = { mapOf("directory.json" to fakeFile()) },
    ) {
        val dir = File.createTempFile("cannoli", "").also { it.delete(); it.mkdirs() }.also { root = it }
        prepare(dir)
        val assets = ApplicationProvider.getApplicationContext<Context>().assets
        val s = KitchenHttpServer(
            dir, assets, port = port, pin = PIN, configFiles = files(dir),
            installedAppsProvider = { apps },
            installedCoresProvider = { cores },
        )
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
        val (code, body) = call("GET", "/api/config/directory.json")
        assertEquals(200, code)
        val json = JSONObject(body)
        assertEquals("{}", json.getString("text"))
        assertEquals("one problem", json.getJSONArray("problems").getString(0))
    }

    @Test fun `put saves valid text and refuses the rest with 422`() {
        start()
        val (ok, okBody) = call("PUT", "/api/config/directory.json", """{"items":[]}""")
        assertEquals(200, ok)
        assertTrue(JSONObject(okBody).getBoolean("saved"))
        assertEquals("""{"items":[]}""", saved)

        val (bad, badBody) = call("PUT", "/api/config/directory.json", "nope")
        assertEquals(422, bad)
        assertFalse(JSONObject(badBody).getBoolean("saved"))
        assertEquals("""{"items":[]}""", saved)
    }

    @Test fun `only listed files are reachable`() {
        start()
        assertEquals(404, call("GET", "/api/config/settings.json").first)
        assertEquals(404, call("GET", "/api/config/../cannoli.db").first)
        val (code, body) = call("GET", "/api/config")
        assertEquals(200, code)
        assertEquals("directory.json", JSONObject(body).getJSONArray("files").getString(0))
    }

    private var savedCount = 0

    private fun textOf(body: String): String = JSONObject(body).getString("text")

    private fun startWithLibrary(): LibraryConfig {
        var config: LibraryConfig? = null
        start { dir ->
            LibraryConfig({ File(dir, "Config") }, ::asset).also { it.load(); config = it }
                .let { libraryConfigFiles(it) { savedCount++ } }
        }
        return config!!
    }

    @Test fun `the library files and templates are all listed`() {
        startWithLibrary()
        val files = JSONObject(call("GET", "/api/config").second).getJSONArray("files")
        val names = (0 until files.length()).map { files.getString(it) }.toSet()
        assertEquals(LibraryFile.entries.map { it.fileName }.toSet() + LibraryConfig.TEMPLATES, names)
    }

    @Test fun `a saved library file is written, reloaded and reported`() {
        val config = startWithLibrary()
        val text = """{ "pizzaboy": { "package": "it.dbtecno.pizzaboygba" } }"""
        val (code, body) = call("PUT", "/api/config/emulators.json", text)
        assertEquals(200, code)
        assertTrue(JSONObject(body).getBoolean("saved"))
        assertEquals(1, savedCount)
        assertEquals(setOf("pizzaboy"), config.library.emulators.keys)
        assertEquals(text, textOf(call("GET", "/api/config/emulators.json").second))

        val (bad, _) = call("PUT", "/api/config/emulators.json", "{ nope")
        assertEquals(422, bad)
        assertEquals(1, savedCount)
        assertEquals(setOf("pizzaboy"), config.library.emulators.keys)
    }

    @Test fun `a saved file reports problems with the other files`() {
        startWithLibrary()
        val (code, body) = call("PUT", "/api/config/systems.json", """{ "GBA": { "emulators": ["nowhere"] } }""")
        assertEquals(200, code)
        val problems = JSONObject(body).getJSONArray("problems")
        assertTrue(problems.toString(), problems.length() > 0)
        val get = JSONObject(call("GET", "/api/config/systems.json").second)
        assertEquals(problems.toString(), get.getJSONArray("problems").toString())
    }

    @Test fun `templates are served but cannot be saved`() {
        startWithLibrary()
        val (code, body) = call("GET", "/api/config/systems.template.json")
        assertEquals(200, code)
        assertEquals(asset("templates/systems.template.json"), textOf(body))
        val (put, putBody) = call("PUT", "/api/config/systems.template.json", "{}")
        assertEquals(405, put)
        assertTrue(putBody, putBody.contains("read-only"))
        assertEquals(0, savedCount)
    }

    @Test fun `hints list installed apps, reported cores and ROM folders`() {
        start(
            apps = listOf("RetroArch" to "com.retroarch.aarch64", "Kodi" to "org.xbmc.kodi"),
            cores = mapOf("com.retroarch.aarch64" to setOf("mgba_libretro", "gambatte_libretro")),
            prepare = { dir ->
                File(dir, "Roms/snes").mkdirs()
                File(dir, "Roms/GBA").mkdirs()
                File(dir, "Roms/.hidden").mkdirs()
                File(dir, "Roms/readme.txt").writeText("not a folder")
            },
        )
        val (code, body) = call("GET", "/api/config/hints")
        assertEquals(200, code)
        val json = JSONObject(body)
        val apps = json.getJSONArray("apps")
        assertEquals(2, apps.length())
        assertEquals("com.retroarch.aarch64", apps.getJSONObject(0).getString("package"))
        assertEquals("RetroArch", apps.getJSONObject(0).getString("label"))
        val cores = json.getJSONObject("cores").getJSONArray("com.retroarch.aarch64")
        assertEquals(listOf("gambatte_libretro", "mgba_libretro"), (0 until cores.length()).map { cores.getString(it) })
        val folders = json.getJSONArray("romFolders")
        assertEquals(listOf("GBA", "snes"), (0 until folders.length()).map { folders.getString(it) })
        // No ROM database in this server: no games, rather than an error.
        assertEquals(0, json.getJSONArray("games").length())
        assertFalse(json.getBoolean("gamesTruncated"))
    }

    @Test fun `hints are read-only and survive a failing source`() {
        start()
        assertEquals(405, call("PUT", "/api/config/hints", "{}").first)
        val (code, body) = call("GET", "/api/config/hints")
        assertEquals(200, code)
        assertEquals(0, JSONObject(body).getJSONArray("romFolders").length())
    }

    private companion object {
        const val PIN = "1234"
    }
}
