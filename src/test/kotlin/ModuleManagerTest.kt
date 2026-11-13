package proj.dnd

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModuleManagerTest {
    @TempDir
    lateinit var directory: Path

    private lateinit var server: MockWebServer
    private val routes = ConcurrentHashMap<String, MockResponse>()
    private val hits = ConcurrentHashMap<String, AtomicInteger>()
    private var beforeResponse: (String) -> Unit = {}
    private val baseUrl: String get() = server.url("/").toString().removeSuffix("/")
    private val payload = byteArrayOf(0, -1, 13, 10)

    @BeforeEach
    fun startServer() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                hits.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet()
                beforeResponse(path)
                return routes[path] ?: MockResponse().setResponseCode(404)
            }
        }
        server.start()
        respond("/module.zip", archive(), "application/zip")
        respond("/module.json", manifest("sample"))
    }

    @AfterEach
    fun stopServer() {
        if (::server.isInitialized) server.shutdown()
    }

    @Test
    fun `loads trimmed URLs in order and ignores blanks and comments`() {
        file("links.txt").writeText(
            "  # comment\r\n\r\n https://example.com/module.json \r\n\t# another comment\n" +
                "https://example.com/second\nhttps://example.com/module.json\n"
        )

        val urls = result(run("load", "links.txt"))

        assertEquals(
            listOf("https://example.com/module.json", "https://example.com/second", "https://example.com/module.json"),
            urls.map { it.asString }
        )
    }

    @Test
    fun `missing URL file returns empty list`() {
        val output = run("load", "missing.txt")

        assertEquals(0, result(output).size())
        assertTrue(output.contains("File not found:"), output)
    }

    @Test
    fun `fetches manifest once then uses disk cache`() {
        val url = "$baseUrl/module.json"

        val modules = result(run("fetch", url, url))

        assertEquals(2, modules.size())
        assertEquals("sample", modules[0].asJsonObject["id"].asString)
        assertEquals(modules[0], modules[1])
        assertEquals(1, requests("/module.json"))
        assertEquals(manifest("sample"), cache(url).readText())
    }

    @Test
    fun `uses existing manifest cache without an HTTP request`() {
        val url = "$baseUrl/cached.json"
        cache(url).apply { parentFile.mkdirs() }.writeText(manifest("cached"))

        val modules = result(run("fetch", url))

        assertEquals("cached", modules[0].asJsonObject["id"].asString)
        assertEquals(0, requests("/cached.json"))
    }

    @Test
    fun `accepts JSON body with text content type and whitespace`() {
        respond("/text", " \n${manifest("plain")}\n ", "text/plain")

        val modules = result(run("fetch", "$baseUrl/text"))

        assertEquals("plain", modules[0].asJsonObject["id"].asString)
    }

    @Test
    fun `follows HTTP redirects for manifests`() {
        routes["/redirect"] = MockResponse().setResponseCode(302).addHeader("Location", "$baseUrl/module.json")

        val modules = result(run("fetch", "$baseUrl/redirect"))

        assertEquals("sample", modules[0].asJsonObject["id"].asString)
        assertEquals(1, requests("/module.json"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["html", "invalid", "null", "missing"])
    fun `invalid or failed manifests return null`(kind: String) {
        when (kind) {
            "html" -> respond("/bad", "<html>Not JSON</html>", "text/html")
            "invalid" -> respond("/bad", "{broken")
            "null" -> respond("/bad", "null")
            "missing" -> respond("/bad", "Missing", "text/plain", 404)
        }

        val modules = result(run("fetch", "$baseUrl/bad"))

        assertTrue(modules[0].isJsonNull)
        assertEquals(1, requests("/bad"))
        if (kind == "html" || kind == "missing") {
            assertFalse(cache("$baseUrl/bad").exists())
        }
    }

    @Test
    fun `CLI downloads and unpacks modules using id and fallback name`() {
        respond("/fallback.json", """{"name":"fallback","title":"Fallback","download":"$baseUrl/module.zip"}""")
        file("modules.txt").writeText("$baseUrl/module.json\n$baseUrl/fallback.json\n")

        val output = run("cli")

        for (id in listOf("sample", "fallback")) {
            assertTrue(file("$id/nested/module.json").isFile, output)
            assertEquals("""{"id":"inside"}""", file("$id/nested/module.json").readText())
            assertContentEquals(payload, file("$id/payload.bin").readBytes())
            assertFalse(file("$id.zip").exists())
            assertFalse(file("${id}_tmp").exists())
        }
        assertEquals(2, requests("/module.zip"))
        assertFalse(file("cache").exists())
        assertTrue(output.contains("Total processed: 2, successfully: 2"), output)

        val repeated = run("cli")

        assertEquals(2, requests("/module.zip"))
        assertTrue(repeated.contains("sample has already been unpacked"), repeated)
        assertTrue(repeated.contains("No archives to unpack"), repeated)
    }

    @ParameterizedTest
    @ValueSource(strings = ["--dir", "-d", "--dir=", "absolute"])
    fun `CLI extracts into chosen directory and skips it on repeat`(option: String) {
        file("links.txt").writeText("$baseUrl/module.json\n")
        file("modules.txt").writeText("$baseUrl/module.json\n")
        file("sample/original.txt").apply { parentFile.mkdirs() }.writeText("keep")
        val destination = "nested/extracted modules"
        val args = when (option) {
            "-d" -> arrayOf("-d", destination, "links.txt")
            "--dir=" -> arrayOf("--dir=$destination")
            "absolute" -> arrayOf("links.txt", "--dir", file(destination).absolutePath)
            else -> arrayOf("links.txt", "--dir", destination)
        }

        val output = run("cli", *args)

        assertContentEquals(payload, file("$destination/sample/payload.bin").readBytes())
        assertEquals("keep", file("sample/original.txt").readText())
        assertFalse(file("sample/payload.bin").exists())
        assertFalse(file("sample.zip").exists())
        assertEquals(listOf("sample"), file(destination).list()!!.toList(), output)
        assertEquals(1, requests("/module.zip"))

        val repeated = run("cli", *args)

        assertTrue(repeated.contains("sample has already been unpacked"), repeated)
        assertEquals(1, requests("/module.zip"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["--dir", "-d", "--dir=", "--unknown"])
    fun `CLI rejects invalid directory options before processing`(option: String) {
        file("modules.txt").writeText("$baseUrl/module.json\n")

        val output = run("cli", option, expectedStatus = 2)

        assertTrue(output.contains("Usage:"), output)
        assertEquals(0, requests("/module.json"))
        assertFalse(file("sample").exists())
    }

    @Test
    fun `CLI reuses working directory archive when extracting elsewhere`() {
        file("modules.txt").writeText("$baseUrl/module.json\n")
        file("sample.zip").writeBytes(archive())

        run("cli", "-d", "output")

        assertContentEquals(payload, file("output/sample/payload.bin").readBytes())
        assertFalse(file("sample.zip").exists())
        assertEquals(0, requests("/module.zip"))
    }

    @Test
    fun `CLI rejects a file as extraction directory before downloading`() {
        file("modules.txt").writeText("$baseUrl/module.json\n")
        file("output").writeText("keep")

        val output = run("cli", "-d", "output", expectedStatus = 2)

        assertTrue(output.contains("Cannot create extraction directory"), output)
        assertEquals("keep", file("output").readText())
        assertEquals(0, requests("/module.json"))
    }

    @Test
    fun `download stage reuses existing ZIP and leaves unpacking to separate stage`() {
        file("sample.zip").writeBytes(archive())

        val output = run("download", "$baseUrl/module.json")

        assertContentEquals(archive(), file("sample.zip").readBytes())
        assertFalse(file("sample").exists())
        assertFalse(file("cache").exists())
        assertEquals(0, requests("/module.zip"))
        assertTrue(output.contains("Archive already downloaded: sample.zip"), output)
    }

    @Test
    fun `skips incomplete manifests and continues after archive HTTP failure`() {
        respond("/no-id.json", """{"title":"No ID","download":"$baseUrl/module.zip"}""")
        respond("/no-download.json", """{"id":"no-download","title":"No download"}""")
        respond("/failed.json", manifest("failed", "/missing.zip"))
        respond("/missing.zip", "Missing", "text/plain", 404)

        val output = run(
            "download", "$baseUrl/no-id.json", "$baseUrl/no-download.json",
            "$baseUrl/failed.json", "$baseUrl/module.json"
        )

        assertTrue(file("sample.zip").isFile, output)
        assertFalse(file("failed.zip").exists())
        assertFalse(file("no-download.zip").exists())
        assertFalse(file("cache").exists())
        assertEquals(1, requests("/module.zip"))
        assertTrue(output.contains("Pass: no `id` and `name`"), output)
        assertTrue(output.contains("Pass: no `download` for no-download"), output)
        assertTrue(output.contains("Unable to process modules: failed"), output)
    }

    @Test
    fun `unpacks ZIPs in isolated staging directories and replaces destination after success`() {
        file("orphan.zip").writeBytes(archive())
        file("orphan/old.txt").apply { parentFile.mkdirs() }.writeText("old")
        file("orphan_tmp/stale.txt").apply { parentFile.mkdirs() }.writeText("stale")

        run("unpack")

        assertContentEquals(payload, file("orphan/payload.bin").readBytes())
        assertFalse(file("orphan/old.txt").exists())
        assertFalse(file("orphan/stale.txt").exists())
        assertEquals("stale", file("orphan_tmp/stale.txt").readText())
        assertFalse(file("orphan.zip").exists())
    }

    @Test
    fun `retains broken and directory-only ZIPs while unpacking valid ones`() {
        file("valid.zip").writeBytes(archive())
        file("broken.zip").writeText("not a ZIP")
        file("empty.zip").writeBytes(zipOf("empty/" to byteArrayOf()))

        val output = run("unpack")

        assertTrue(file("valid/payload.bin").isFile)
        assertFalse(file("valid.zip").exists())
        assertEquals("not a ZIP", file("broken.zip").readText())
        assertTrue(file("empty.zip").isFile)
        assertFalse(file("broken").exists())
        assertFalse(file("empty").exists())
        assertTrue(output.contains("Total processed: 3, successfully: 1"), output)
    }

    @Test
    fun `archive downloads overlap with at most five workers and deduplicate URLs`() {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val overlapping = CountDownLatch(2)
        beforeResponse = { path ->
            if (path.endsWith(".zip")) {
                val current = active.incrementAndGet()
                peak.accumulateAndGet(current, ::maxOf)
                overlapping.countDown()
                try {
                    overlapping.await(2, TimeUnit.SECONDS)
                    Thread.sleep(200)
                } finally {
                    active.decrementAndGet()
                }
            }
        }
        val urls = (1..9).map { index ->
            respond("/module$index.json", manifest("module$index", "/module$index.zip"))
            respond("/module$index.zip", archive(), "application/zip")
            "$baseUrl/module$index.json"
        }

        val output = run("download", *(urls + urls).toTypedArray())

        assertTrue(peak.get() > 1, "Archive downloads were serial: peak=${peak.get()}")
        assertTrue(peak.get() <= 5, "Download worker limit exceeded: peak=${peak.get()}")
        for (index in 1..9) {
            assertEquals(1, requests("/module$index.json"))
            assertEquals(1, requests("/module$index.zip"), output)
            assertContentEquals(archive(), file("module$index.zip").readBytes())
        }
    }

    @Test
    fun `different manifests for one id download only one archive`() {
        respond("/alias.json", manifest("sample"))

        run("download", "$baseUrl/module.json", "$baseUrl/alias.json")

        assertEquals(1, requests("/module.zip"))
        assertContentEquals(archive(), file("sample.zip").readBytes())
    }

    @Test
    fun `interrupted download leaves no ZIP or partial file and can be retried`() {
        routes["/module.zip"] = MockResponse().setBody(Buffer().write(archive()))
            .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)

        val failed = run("download", "$baseUrl/module.json")

        assertTrue(failed.contains("Unable to process modules: sample"), failed)
        assertFalse(file("sample.zip").exists())
        assertFalse(file("sample.zip.part").exists())
        respond("/module.zip", archive(), "application/zip")

        run("download", "$baseUrl/module.json")

        assertContentEquals(archive(), file("sample.zip").readBytes())
    }

    @Test
    fun `invalid URL does not cancel valid downloads`() {
        run("download", "not-a-url", "$baseUrl/module.json")

        assertTrue(file("sample.zip").isFile)
        assertEquals(1, requests("/module.zip"))
    }

    @Test
    fun `unsafe ZIP retains archive and previous destination without publishing partial files`() {
        file("unsafe.zip").writeBytes(zipOf("safe.txt" to payload, "../escape.txt" to payload))
        file("unsafe/original.txt").apply { parentFile.mkdirs() }.writeText("keep")

        val output = run("unpack")

        assertTrue(output.contains("successfully: 0"), output)
        assertEquals("keep", file("unsafe/original.txt").readText())
        assertFalse(file("unsafe/safe.txt").exists())
        assertFalse(file("escape.txt").exists())
        assertTrue(file("unsafe.zip").isFile)
        assertTrue(directory.toFile().listFiles()!!.none { it.name.startsWith(".unsafe.unpack-") })
    }

    @Test
    fun `staging directory does not collide with another module id`() {
        file("module.zip").writeBytes(archive())
        file("module_tmp.zip").writeBytes(archive())

        run("unpack")

        assertContentEquals(payload, file("module/payload.bin").readBytes())
        assertContentEquals(payload, file("module_tmp/payload.bin").readBytes())
    }

    @Test
    fun `unpacks more archives than the worker limit without losing files`() {
        for (index in 1..9) file("module$index.zip").writeBytes(archive())

        val output = run("unpack")

        for (index in 1..9) {
            assertContentEquals(payload, file("module$index/payload.bin").readBytes())
            assertFalse(file("module$index.zip").exists())
            assertFalse(file("module${index}_tmp").exists())
        }
        assertTrue(output.contains("Total processed: 9, successfully: 9"), output)
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing.txt", "empty.txt", "comments.txt"])
    fun `CLI stops before unpacking when input has no URLs`(input: String) {
        if (input != "missing.txt") {
            file(input).writeText(if (input == "comments.txt") " # comment\n\n\t\n" else "")
        }
        file("orphan.zip").writeBytes(archive())

        val output = run("cli", input)

        assertTrue(output.contains("Empty links file! Add them to $input"), output)
        assertTrue(file("orphan.zip").isFile)
        assertFalse(file("orphan").exists())
        assertEquals(0, requests("/module.json"))
    }

    @Test
    fun `unpacking with no ZIP files leaves unrelated files alone`() {
        file("notes.txt").writeText("keep")

        val output = run("unpack")

        assertTrue(output.contains("No archives to unpack"), output)
        assertEquals("keep", file("notes.txt").readText())
    }

    private fun file(name: String): File = directory.resolve(name).toFile()

    private fun cache(url: String): File = file("cache/${url.hashCode()}.json")

    private fun requests(path: String): Int = hits[path]?.get() ?: 0

    private fun manifest(id: String, download: String = "/module.zip"): String =
        """{"id":"$id","title":"Sample module","download":"$baseUrl$download"}"""

    private fun respond(path: String, body: String, type: String = "application/json", status: Int = 200) =
        respond(path, body.toByteArray(Charsets.UTF_8), type, status)

    private fun respond(path: String, body: ByteArray, type: String, status: Int = 200) {
        routes[path] = MockResponse().setResponseCode(status).addHeader("Content-Type", type)
            .setBody(Buffer().write(body))
    }

    private fun archive(): ByteArray = zipOf(
        "nested/module.json" to """{"id":"inside"}""".toByteArray(),
        "payload.bin" to payload
    )

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().use { bytes ->
        ZipOutputStream(bytes).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name).apply { time = 0 })
                zip.write(content)
                zip.closeEntry()
            }
        }
        bytes.toByteArray()
    }

    private fun result(output: String): JsonArray =
        JsonParser.parseString(output.lineSequence().last { it.startsWith("RESULT=") }.removePrefix("RESULT=")).asJsonArray

    private fun run(vararg args: String, expectedStatus: Int = 0): String {
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"))
            .split(File.pathSeparator).joinToString(File.pathSeparator) { File(it).absolutePath }
        val log = file("process.log")
        val process = ProcessBuilder(
            listOf(java, "-Dfile.encoding=UTF-8", "-cp", classpath, ModuleManagerProbe::class.java.name) + args
        ).directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log).start()

        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Test process timed out: ${log.readText()}")
            val output = log.readText()
            assertEquals(expectedStatus, process.exitValue(), output)
            return output
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
            }
        }
    }
}
