package dev.cannoli.scorza.server

import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KitchenFsTest {

    private lateinit var root: File
    private lateinit var internalVol: File
    private lateinit var sdVol: File
    private var server: KitchenHttpServer? = null
    private val port = 17192

    @Before fun setUp() {
        root = File.createTempFile("cannoli", "").also { it.delete(); it.mkdirs() }
        internalVol = File(root, "volumes/internal").also { it.mkdirs() }
        sdVol = File(root, "volumes/sd").also { it.mkdirs() }
        File(internalVol, "Download").mkdirs()
        File(internalVol, "Download/readme.txt").writeText("HELLO")
        File(sdVol, "card.txt").writeText("CARD")
        startServer()
    }

    @After fun tearDown() {
        server?.stopServer()
        root.deleteRecursively()
    }

    private fun startServer() {
        val assets = ApplicationProvider.getApplicationContext<android.content.Context>().assets
        val cannoliRoot = File(root, "Cannoli").also { it.mkdirs() }
        val s = KitchenHttpServer(
            cannoliRoot = cannoliRoot,
            assets = assets,
            port = port,
            pin = "TESTPIN",
            volumesProvider = {
                listOf(
                    KitchenVolume("internal", "Internal Storage", internalVol),
                    KitchenVolume("sdcard", "SD Card", sdVol),
                )
            },
        )
        s.startServer()
        waitUntilReady()
        server = s
    }

    private fun waitUntilReady() {
        repeat(50) {
            try {
                URL("http://127.0.0.1:$port/api/auth").openConnection()
                    .also { (it as HttpURLConnection).connect(); it.disconnect() }
                return
            } catch (_: Exception) { Thread.sleep(40) }
        }
    }

    private val httpClient = OkHttpClient()

    private fun request(
        method: String,
        path: String,
        body: ByteArray? = null,
        contentType: String? = null,
    ): Pair<Int, String> {
        val token = Base64.getEncoder().encodeToString("nonna:TESTPIN".toByteArray())
        return if (method == "PATCH") {
            val reqBody = if (body != null) {
                body.toRequestBody((contentType ?: "application/octet-stream").toMediaType())
            } else {
                ByteArray(0).toRequestBody(null)
            }
            val req = Request.Builder()
                .url("http://127.0.0.1:$port$path")
                .method(method, reqBody)
                .header("Authorization", "Basic $token")
                .build()
            val resp = httpClient.newCall(req).execute()
            val text = resp.body?.string() ?: ""
            val code = resp.code
            resp.close()
            code to text
        } else {
            val conn = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.setRequestProperty("Authorization", "Basic $token")
            if (contentType != null) conn.setRequestProperty("Content-Type", contentType)
            if (body != null) {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.readBytes()?.decodeToString() ?: ""
            conn.disconnect()
            code to text
        }
    }

    private fun multipartBody(filename: String, content: ByteArray): Pair<ByteArray, String> {
        val boundary = "----cannoli"
        val head = ("--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"file\"; filename=\"$filename\"\r\n" +
            "Content-Type: application/octet-stream\r\n\r\n").toByteArray()
        val tail = "\r\n--$boundary--\r\n".toByteArray()
        return head + content + tail to "multipart/form-data; boundary=$boundary"
    }

    @Test fun isSecureAcceptsFileUnderGivenRoot() {
        assertTrue(server!!.isSecure(File(sdVol, "card.txt"), listOf(sdVol)))
    }

    @Test fun isSecureRejectsFileOutsideGivenRoots() {
        assertFalse(server!!.isSecure(File(sdVol, "card.txt"), listOf(internalVol)))
    }

    @Test fun isSecureRejectsTraversalOutOfRoot() {
        assertFalse(server!!.isSecure(File(internalVol, "../sd/card.txt"), listOf(internalVol)))
    }

    @Test fun singleArgIsSecureStillGuardsCannoliRoot() {
        assertFalse(server!!.isSecure(File(internalVol, "Download/readme.txt")))
    }

    @Test fun listsVolumes() {
        val (code, body) = request("GET", "/api/fs")
        assertEquals(200, code)
        assertTrue(body.contains("\"internal\""))
        assertTrue(body.contains("SD Card"))
        assertTrue(body.contains("totalBytes"))
    }

    @Test fun unknownVolumeIs404() {
        val (code, _) = request("GET", "/api/fs/usb9")
        assertEquals(404, code)
    }

    @Test fun listsVolumeRoot() {
        val (code, body) = request("GET", "/api/fs/internal")
        assertEquals(200, code)
        assertTrue(body.contains("Download"))
    }

    @Test fun listsSubdirectory() {
        val (code, body) = request("GET", "/api/fs/internal/Download")
        assertEquals(200, code)
        assertTrue(body.contains("readme.txt"))
    }

    @Test fun downloadsFile() {
        val (code, body) = request("GET", "/api/fs/internal/Download/readme.txt")
        assertEquals(200, code)
        assertEquals("HELLO", body)
    }

    @Test fun uploadsFile() {
        val (bytes, contentType) = multipartBody("note.txt", "NOTE".toByteArray())
        val (code, _) = request("POST", "/api/fs/sdcard", body = bytes, contentType = contentType)
        assertEquals(200, code)
        assertEquals("NOTE", File(sdVol, "note.txt").readText())
    }

    @Test fun createsDirectory() {
        val (code, _) = request("PUT", "/api/fs/internal/NewDir")
        assertEquals(201, code)
        assertTrue(File(internalVol, "NewDir").isDirectory)
    }

    @Test fun deletesDirectoryRecursively() {
        File(internalVol, "Trash/sub").mkdirs()
        File(internalVol, "Trash/sub/x.txt").writeText("X")
        val (code, _) = request("DELETE", "/api/fs/internal/Trash")
        assertEquals(200, code)
        assertFalse(File(internalVol, "Trash").exists())
    }

    @Test fun movesAcrossTopLevelDirectories() {
        File(internalVol, "Documents").mkdirs()
        val body = """{"to":"Documents/readme.txt"}""".toByteArray()
        val (code, _) = request("PATCH", "/api/fs/internal/Download/readme.txt", body = body, contentType = "application/json")
        assertEquals(200, code)
        assertTrue(File(internalVol, "Documents/readme.txt").exists())
        assertFalse(File(internalVol, "Download/readme.txt").exists())
    }

    @Test fun traversalOutOfVolumeIsForbidden() {
        val (code, _) = request("GET", "/api/fs/internal/%2e%2e/sd/card.txt")
        assertEquals(403, code)
    }

    @Test fun deleteAtVolumeRootIsRejected() {
        val (code, _) = request("DELETE", "/api/fs/internal")
        assertEquals(400, code)
    }

    @Test fun fsCannotReachCannoliRootImplicitly() {
        File(File(root, "Cannoli"), "secret.txt").writeText("S")
        val (code, _) = request("GET", "/api/fs/internal/%2e%2e/%2e%2e/Cannoli/secret.txt")
        assertEquals(403, code)
    }

    @Test fun moveEscapeViaToFieldIsForbidden() {
        val body = """{"to":"../sd/escaped.txt"}""".toByteArray()
        val (code, _) = request("PATCH", "/api/fs/internal/Download/readme.txt", body = body, contentType = "application/json")
        assertEquals(403, code)
        assertFalse(File(sdVol, "escaped.txt").exists())
        assertTrue(File(internalVol, "Download/readme.txt").exists())
    }

    @Test fun moveWithMissingToIsRejected() {
        val body = """{}""".toByteArray()
        val (code, _) = request("PATCH", "/api/fs/internal/Download/readme.txt", body = body, contentType = "application/json")
        assertEquals(400, code)
    }

    @Test fun symlinkInsideVolumeIsNotServed() {
        val linkPath = File(internalVol, "leak").toPath()
        val target = sdVol.toPath().resolve("card.txt")
        try {
            java.nio.file.Files.createSymbolicLink(linkPath, target)
        } catch (_: UnsupportedOperationException) {
            org.junit.Assume.assumeTrue(false)
        }
        val (code, _) = request("GET", "/api/fs/internal/leak")
        assertEquals(403, code)
    }

    private fun rawGet(path: String): HttpURLConnection =
        (URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection).also { it.requestMethod = "GET" }

    @Test fun uploadsSeveralFilesInOneRequest() {
        val boundary = "----cannoli"
        val body = ("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.txt\"\r\n\r\nAAA\r\n" +
            "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"b.txt\"\r\n\r\nBBB\r\n" +
            "--$boundary--\r\n").toByteArray()
        val (code, text) = request("POST", "/api/fs/internal/Download", body = body, contentType = "multipart/form-data; boundary=$boundary")
        assertEquals(200, code)
        assertTrue(text.contains("a.txt") && text.contains("b.txt"))
        assertEquals("AAA", File(internalVol, "Download/a.txt").readText())
        assertEquals("BBB", File(internalVol, "Download/b.txt").readText())
    }

    @Test fun uploadsAndDownloadsALargeFileIntact() {
        val content = ByteArray(12 * 1024 * 1024) { (it * 31 + 7).toByte() }
        val (bytes, contentType) = multipartBody("big.bin", content)
        val (code, _) = request("POST", "/api/fs/sdcard", body = bytes, contentType = contentType)
        assertEquals(200, code)
        assertTrue(content.contentEquals(File(sdVol, "big.bin").readBytes()))

        val token = Base64.getEncoder().encodeToString("nonna:TESTPIN".toByteArray())
        val conn = rawGet("/api/fs/sdcard/big.bin")
        conn.setRequestProperty("Authorization", "Basic $token")
        assertEquals(200, conn.responseCode)
        assertEquals(content.size.toLong(), conn.getHeaderField("Content-Length").toLong())
        assertTrue(content.contentEquals(conn.inputStream.readBytes()))
        conn.disconnect()
    }

    @Test fun downloadLinkCarriesItsOwnAuthAndSavesAsAFile() {
        val token = java.net.URLEncoder.encode(Base64.getEncoder().encodeToString("nonna:TESTPIN".toByteArray()), "UTF-8")
        val conn = rawGet("/api/fs/internal/Download/readme.txt?auth=$token&download=read%20me%C3%A9.txt")
        assertEquals(200, conn.responseCode)
        assertEquals(
            "attachment; filename=\"read me_.txt\"; filename*=UTF-8''read%20me%C3%A9.txt",
            conn.getHeaderField("Content-Disposition"),
        )
        assertEquals("HELLO", conn.inputStream.readBytes().decodeToString())
        conn.disconnect()
    }

    @Test fun downloadLinkWithAWrongTokenIsRefused() {
        val token = Base64.getEncoder().encodeToString("nonna:WRONG".toByteArray())
        assertEquals(401, rawGet("/api/fs/internal/Download/readme.txt?auth=$token").responseCode)
    }

    @Test fun queryAuthCannotChangeFiles() {
        val token = Base64.getEncoder().encodeToString("nonna:TESTPIN".toByteArray())
        val conn = URL("http://127.0.0.1:$port/api/fs/internal/Download/readme.txt?auth=$token").openConnection() as HttpURLConnection
        conn.requestMethod = "DELETE"
        assertEquals(401, conn.responseCode)
        conn.disconnect()
        assertTrue(File(internalVol, "Download/readme.txt").exists())
    }

    @Test fun renamesAFolderInPlace() {
        File(internalVol, "Old").mkdirs()
        File(internalVol, "Old/inner.txt").writeText("I")
        val body = """{"to":"New"}""".toByteArray()
        val (code, _) = request("PATCH", "/api/fs/internal/Old", body = body, contentType = "application/json")
        assertEquals(200, code)
        assertEquals("I", File(internalVol, "New/inner.txt").readText())
        assertFalse(File(internalVol, "Old").exists())
    }

    @Test fun renameOntoAnotherFileIsAConflict() {
        File(internalVol, "Download/other.txt").writeText("O")
        val body = """{"to":"Download/other.txt"}""".toByteArray()
        val (code, _) = request("PATCH", "/api/fs/internal/Download/readme.txt", body = body, contentType = "application/json")
        assertEquals(409, code)
        assertEquals("HELLO", File(internalVol, "Download/readme.txt").readText())
    }

    @Test fun listsAndDownloadsControllerMappings() {
        val dir = File(root, "Cannoli/Config/Input/Mappings").also { it.mkdirs() }
        File(dir, "8bitdo.ini").writeText("[meta]\ndisplay_name=8BitDo Pro 2\nsource=USER_WIZARD\n")
        File(dir, "plain.ini").writeText("[match]\n")
        File(dir, "notes.txt").writeText("x")

        val (code, body) = request("GET", "/api/mappings")
        assertEquals(200, code)
        assertEquals(
            """{"mappings":[{"file":"8bitdo.ini","name":"8BitDo Pro 2"},{"file":"plain.ini","name":"plain"}]}""",
            body,
        )
        val (fileCode, fileBody) = request("GET", "/api/mappings/8bitdo.ini")
        assertEquals(200, fileCode)
        assertTrue(fileBody.contains("display_name=8BitDo Pro 2"))
        assertEquals(404, request("GET", "/api/mappings/notes.txt").first)
        assertEquals(404, request("GET", "/api/mappings/%2e%2e%2fsecret.ini").first)
    }
}
