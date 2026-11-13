package proj.dnd

import com.google.gson.GsonBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.ZipFile

/** Downloads modules in the current directory and extracts them using bounded I/O workers. */
object ModuleManager {
    private const val DOWNLOAD_WORKERS = 5
    private const val UNPACK_WORKERS = 5
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.MINUTES)
        .retryOnConnectionFailure(true)
        .build()
    private val gson = GsonBuilder().serializeNulls().create()
    private val cacheDir = File("cache")
    private val downloadDir = File(".")

    fun loadUrlsFromFile(filePath: String): List<String> {
        val file = File(filePath)
        if (!file.exists()) {
            println("⚠️ File not found: ${file.absolutePath}")
            return emptyList()
        }
        return file.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    }

    private fun normalizeUrl(rawUrl: String): String {
        var url = rawUrl.trim()
        if (url.contains("dropbox.com")) {
            url = url.replace(Regex("https?://(www\\.)?dropbox\\.com"), "https://dl.dropboxusercontent.com")
                .replace(Regex("[?&](dl|raw)=\\d"), "")
        }
        return when {
            url.contains("github.com") && url.contains("/blob/") -> url.replace(
                Regex("https?://github\\.com/([^/]+)/([^/]+)/blob/([^/]+)/(.*)"),
                "https://raw.githubusercontent.com/$1/$2/$3/$4"
            )

            url.contains("gist.github.com") -> url.replace(
                Regex("https?://gist\\.github\\.com/([^/]+)/([a-f0-9]+)"),
                "https://gist.githubusercontent.com/$1/$2/raw"
            )

            else -> url
        }
    }

    private fun cacheFile(url: String): File = File(cacheDir, "${url.hashCode()}.json")

    private fun move(source: File, destination: File) {
        retryAccessDenied {
            try {
                Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    private fun downloadFile(url: String, output: File) {
        val partial = File("${output.path}.part")
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                check(response.isSuccessful) { "Downloading error: ${response.code}" }
                val body = checkNotNull(response.body) { "Empty HTTP response" }
                body.byteStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
            }
            move(partial, output)
        } finally {
            partial.delete()
        }
    }

    suspend fun fetchModule(url: String): ModuleData? = withContext(Dispatchers.IO) {
        val normalized = normalizeUrl(url)
        val cache = cacheFile(normalized)
        try {
            val text = if (cache.isFile) cache.readText() else {
                val request = Request.Builder().url(normalized).header("User-Agent", "batchModulesDownload/0.9").build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        println("Error downloading module.json: ${response.code} for $normalized")
                        return@withContext null
                    }
                    val body = response.body.string().trim()
                    val contentType = response.header("Content-Type").orEmpty()
                    if (!contentType.contains("json", ignoreCase = true) && !body.startsWith("{")) {
                        println("⚠️ $normalized did not return module.json (Content-Type=$contentType)")
                        return@withContext null
                    }
                    body
                }
            }
            val module = gson.fromJson(text, ModuleData::class.java) ?: return@withContext null
            if (!cache.exists()) {
                Files.createDirectories(cacheDir.toPath())
                val partial = File.createTempFile("manifest-", ".part", cacheDir)
                try {
                    partial.writeText(text)
                    move(partial, cache)
                } finally {
                    partial.delete()
                }
            }
            module
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("⚠️ Error fetching $normalized: ${e.message}")
            null
        }
    }

    private fun validId(id: String): Boolean = id.isNotBlank() && id != "." && id != ".." &&
            id.none { it in "\\/:*?\"<>|" || it.isISOControl() } && !id.endsWith('.') && !id.endsWith(' ')

    suspend fun downloadAll(urls: List<String>, outputDirectory: File = File(".")) {
        val claimedIds = ConcurrentHashMap.newKeySet<String>()
        val failures = parallelMap(urls.map(::normalizeUrl).distinct(), DOWNLOAD_WORKERS) { url ->
            var failedId = url
            try {
                val module = fetchModule(url) ?: return@parallelMap url
                val id = module.id ?: module.name ?: run {
                    println("Pass: no `id` and `name` for $url")
                    return@parallelMap null
                }
                failedId = id
                check(validId(id)) { "Invalid module id: $id" }
                val archiveUrl = module.download ?: run {
                    println("Pass: no `download` for $id")
                    return@parallelMap null
                }
                // Different manifests may refer to the same output directory.
                if (!claimedIds.add(id.lowercase())) return@parallelMap null
                val outputDir = File(outputDirectory, id)
                if (outputDir.isDirectory && outputDir.list()?.isNotEmpty() == true) {
                    println("⏩ Pass: $id has already been unpacked")
                } else {
                    val archive = File(downloadDir, "$id.zip")
                    if (archive.isFile) {
                        println("✅ Archive already downloaded: ${archive.name}")
                    } else {
                        println("⬇️ Downloading archive: $archiveUrl → ${archive.name}")
                        downloadFile(normalizeUrl(archiveUrl), archive)
                    }
                    println("✔️ Completed: $id (cache cleared)")
                }
                cacheFile(url).delete()
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                println("❌ Error during processing $failedId: ${e.message}")
                failedId
            }
        }.filterNotNull()
        if (cacheDir.exists()) check(cacheDir.deleteRecursively()) { "Cannot clean cache directory" }
        if (failures.isEmpty()) println("🎉 All modules have been successfully processed.")
        else println("⚠️ Unable to process modules: ${failures.joinToString(", ")}")
    }

    private fun unzip(archive: File, destination: File) {
        val root = destination.canonicalFile.toPath()
        ZipFile(archive).use { zip ->
            for (entry in zip.entries()) {
                val output = File(destination, entry.name.replace('\\', '/')).canonicalFile
                check(output.toPath().startsWith(root) && output.toPath() != root) {
                    "ZIP entry escapes destination: ${entry.name}"
                }
                if (entry.isDirectory) {
                    check(output.isDirectory || output.mkdirs()) { "Cannot create $output" }
                } else {
                    check(output.parentFile.isDirectory || output.parentFile.mkdirs()) { "Cannot create ${output.parent}" }
                    val crc = CRC32()
                    var size = 0L
                    zip.getInputStream(entry).use { input ->
                        output.outputStream().use { target ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                target.write(buffer, 0, count)
                                crc.update(buffer, 0, count)
                                size += count
                            }
                        }
                    }
                    check(size == entry.size && crc.value == entry.crc) { "Corrupt ZIP entry: ${entry.name}" }
                }
            }
        }
    }

    private fun replaceDirectory(temporary: File, destination: File) {
        val backup = File("${temporary.path}.backup")
        if (destination.exists()) move(destination, backup)
        try {
            move(temporary, destination)
        } catch (e: Exception) {
            if (backup.exists()) move(backup, destination)
            throw e
        }
        if (backup.exists()) check(backup.deleteRecursively()) { "Cannot remove backup: $backup" }
    }

    suspend fun unpackAll(outputDirectory: File = File(".")) {
        val archives = downloadDir.listFiles { file -> file.isFile && file.extension.equals("zip", ignoreCase = true) }
            ?.sortedBy { it.name }.orEmpty()
        if (archives.isEmpty()) {
            println("🎉 No archives to unpack")
            return
        }
        withContext(Dispatchers.IO) {
            Files.createDirectories(outputDirectory.toPath())
        }
        println("📦 Starting to unpack ${archives.size} archives...")
        val successful = parallelMap(archives.withIndex().toList(), UNPACK_WORKERS) { (index, archive) ->
            // A unique staging directory cannot collide with another module's id.
            val temporary =
                Files.createTempDirectory(outputDirectory.toPath(), ".${archive.nameWithoutExtension}.unpack-").toFile()
            try {
                unzip(archive, temporary)
                val count = temporary.walk().count { it.isFile }
                check(count > 0) { "Archive is empty or has not been unpacked." }
                replaceDirectory(temporary, File(outputDirectory, archive.nameWithoutExtension))
                check(archive.delete()) { "Cannot delete ${archive.name}" }
                println("✔️ [${index + 1}/${archives.size}] ${archive.nameWithoutExtension} unpacked ($count files)")
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                println("❌ Error during unpacking ${archive.name}: ${e.message}")
                false
            } finally {
                if (temporary.exists()) temporary.deleteRecursively()
            }
        }.count { it }
        println("🎯 Unpacking complete. Total processed: ${archives.size}, successfully: $successful")
    }
}
