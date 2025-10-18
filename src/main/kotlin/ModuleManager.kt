package proj.dnd

import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import proj.dnd.ModuleManager.downloadAll
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * Manages discovery, caching, downloading, and unpacking of remote modules for Foundry-like systems.
 *
 * This object automates retrieval of remote `module.json` manifests and their corresponding archives.
 * It supports multiple remote providers (Dropbox, GitHub, Gist) through URL normalization, local JSON
 * caching, concurrent downloading, and controlled unpacking.
 *
 * ### Responsibilities
 * - Normalizes URLs from GitHub, Dropbox, and Gist to direct download endpoints.
 * - Downloads and caches `module.json` definitions to avoid redundant network calls.
 * - Downloads corresponding ZIP archives and unpacks them into the `downloads/` directory.
 * - Handles concurrent downloads and unpacks using Kotlin coroutines and semaphores.
 * - Automatically retries failed HTTP connections through OkHttp configuration.
 *
 * ### Thread safety
 * All operations are coroutine-safe. Downloading and unpacking are executed concurrently but
 * limited by semaphores to prevent I/O saturation.
 *
 * @author spechurkin
 * @version 1.0
 **/
object ModuleManager {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val gson = GsonBuilder().serializeNulls().create()
    private val cacheDir = File("cache").apply { mkdirs() }
    private val downloadDir = File(".")

    /**
     * Loads and parses a list of URLs from a plain text file.
     *
     * Each non-empty, non-comment line is treated as a valid URL.
     * Comment lines (starting with `#`) are ignored.
     *
     * @param filePath Path to the text file containing one URL per line.
     * @return A list of normalized, non-empty URLs. If the file does not exist, returns an empty list.
     */
    fun loadUrlsFromFile(filePath: String): List<String> {
        val file = File(filePath)
        if (!file.exists()) {
            println("⚠️ File not found: ${file.absolutePath}")
            return emptyList()
        }

        return file.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
    }

    /**
     * Normalizes known remote URLs to direct download links.
     *
     * Handles the following cases:
     * - Converts `dropbox.com` sharing links to `dl.dropboxusercontent.com`.
     * - Converts `github.com/.../blob/...` URLs to `raw.githubusercontent.com`.
     * - Converts `gist.github.com` to `gist.githubusercontent.com/raw`.
     *
     * @param rawUrl The original user-provided URL.
     * @return A normalized, direct-download-ready URL.
     */
    private fun normalizeUrl(rawUrl: String): String {
        var url = rawUrl.trim()

        // === Dropbox ===
        if (url.contains("dropbox.com")) {
            url = url
                .replace(Regex("https?://(www\\.)?dropbox\\.com"), "https://dl.dropboxusercontent.com")
                .replace(Regex("[?&](dl|raw)=\\d"), "")
        }

        // === GitHub ===
        when {
            // repo/blob → raw.githubusercontent
            url.contains("github.com") && url.contains("/blob/") -> {
                url = url.replace(
                    Regex("https?://github\\.com/([^/]+)/([^/]+)/blob/([^/]+)/(.*)"),
                    "https://raw.githubusercontent.com/$1/$2/$3/$4"
                )
            }

            // gist.github.com → gist.githubusercontent.com/raw
            url.contains("gist.github.com") -> {
                url = url.replace(
                    Regex("https?://gist\\.github\\.com/([^/]+)/([a-f0-9]+)"),
                    "https://gist.githubusercontent.com/$1/$2/raw"
                )
            }
        }

        return url
    }

    /**
     * Produces a `File` instance for caching the downloaded `module.json` associated with [url].
     *
     * Cache filename is derived from the URL hash to avoid filesystem-unfriendly characters.
     * Cached files are stored under the `cache/` directory.
     *
     * This method is private because cache layout and lifecycle are internal implementation details.
     *
     * @param url The remote URL of the module manifest.
     * @return A [File] pointing to the cached JSON location for the given URL.
     */
    private fun cacheFile(url: String): File =
        File(cacheDir, url.hashCode().toString() + ".json")

    /**
     * Downloads a file from a remote URL and saves it locally.
     *
     * @param url Remote file URL.
     * @param output Target file location on disk.
     * @throws IllegalStateException if the download fails.
     */
    private fun downloadFile(url: String, output: File) {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Downloading error: ${response.code}")
            response.body?.byteStream()?.use { input ->
                FileOutputStream(output).use { outputStream ->
                    input.copyTo(outputStream)
                }
            }
        }
    }

    /**
     * Fetches and parses a remote `module.json` file into [ModuleData].
     *
     * - Uses a local cache when available to reduce redundant HTTP requests.
     * - Supports automatic URL normalization for Dropbox, GitHub, and Gist.
     *
     * @param url Remote URL to the module manifest.
     * @return Parsed [ModuleData] instance, or `null` if the request failed or the data was invalid.
     */
    suspend fun fetchModule(url: String): ModuleData? = withContext(Dispatchers.IO) {
        val cache = cacheFile(url)
        File(cacheDir, "invalid").apply { mkdirs() }

        val text = if (cache.exists()) cache.readText() else {
            val request = Request.Builder()
                .url(url)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 5.1) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/41.0.2224.3 Safari/537.36"
                )
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                println("Error downloading module.json: ${response.code} for $url")
                return@withContext null
            }

            val contentType = response.header("Content-Type") ?: ""
            val body = response.body?.string()?.trim().orEmpty()

            if (!contentType.contains("json", ignoreCase = true) && !body.startsWith("{")) {
                println("⚠️ $url did not return module.json (Content-Type=$contentType)")
                return@withContext null
            }

            cache.writeText(body)
            body
        }

        try {
            gson.fromJson(text, ModuleData::class.java)
        } catch (e: JsonSyntaxException) {
            println("⚠️ Error parsing $url: ${e.message}.")
            null
        }
    }

    /**
     * Downloads all modules provided in the given list of URLs.
     *
     * - Each URL is normalized and fetched concurrently.
     * - Archives are downloaded into current directory.
     * - Successfully processed caches are cleared after unpacking.
     * - Failed modules are logged for later review.
     *
     * @param urls List of remote `module.json` URLs.
     */
    suspend fun downloadAll(urls: List<String>) = coroutineScope {
        val failedModules = mutableListOf<String>()

        urls.map { url ->
            async {
                val module = fetchModule(normalizeUrl(url)) ?: return@async
                val id = module.id ?: module.name ?: run {
                    println("Pass: no `id` and `name` for $url")
                    return@async
                }

                val archiveUrl = normalizeUrl(module.download ?: run {
                    println("Pass: no `download` for $id")
                    return@async
                })

                val archiveFile = File(downloadDir, "$id.zip")
                val outputDir = File(downloadDir, id)
                val cacheFile = cacheFile(url)

                if (outputDir.exists() && outputDir.isDirectory && outputDir.list()?.isNotEmpty() == true) {
                    println("⏩ Pass: $id has already been unpacked")
                    cacheFile.delete()
                    return@async
                }

                try {
                    if (!archiveFile.exists()) {
                        println("⬇️ Downloading archive: $archiveUrl → ${archiveFile.name}")
                        downloadFile(normalizeUrl(archiveUrl), archiveFile)
                    } else {
                        println("✅ Archive already downloaded: ${archiveFile.name}")
                    }

                    cacheFile.delete()
                    println("✔️ Completed: $id (cache cleared)")
                } catch (e: Exception) {
                    println("❌ Error during processing $id: ${e.message}")
                    failedModules += id
                }
            }
        }.awaitAll()

        cleanup()

        if (failedModules.isNotEmpty()) {
            println("⚠️ Unable to process modules: ${failedModules.joinToString(", ")}")
        } else {
            println("🎉 All modules have been successfully processed.")
        }
    }

    /**
     * Extracts a ZIP archive into a target directory.
     *
     * @param zipFile The archive file to extract.
     * @param destDir The destination directory.
     */
    private fun unzip(zipFile: File, destDir: File) {
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(destDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    try {
                        FileOutputStream(outFile).use { fos ->
                            zis.copyTo(fos)
                        }
                    } catch (e: Exception) {
                        println("⚠️ Error writing ${outFile.path}: ${e.message}")
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    /**
     * Unpacks all `.zip` archives in the directory.
     *
     * - Each archive is extracted to its own folder.
     * - Extraction is performed concurrently but limited by a [Semaphore].
     * - Successfully unpacked archives are deleted afterward.
     *
     * @see downloadAll
     */
    suspend fun unpackAll() = coroutineScope {
        val archives: List<File> = downloadDir
            .listFiles { f -> f.extension == "zip" }
            ?.toList()
            .orEmpty()

        if (archives.isEmpty()) {
            println("🎉 No archives to unpack")
            return@coroutineScope
        }

        println("📦 Starting to unpack ${archives.size} archives...")

        val unzipSemaphore = Semaphore(5)
        val results: List<File?> = archives.mapIndexed { idx, archive ->
            async(Dispatchers.IO) {
                unzipSemaphore.withPermit {
                    val id = archive.nameWithoutExtension
                    val tempDir = File(downloadDir, "${id}_tmp")
                    val finalDir = File(downloadDir, id)

                    runCatching {
                        if (tempDir.exists()) tempDir.deleteRecursively()
                        unzip(archive, tempDir)

                        val fileCount = tempDir.walk().count { it.isFile }
                        if (fileCount == 0) error("Archive is empty or has not been unpacked.")

                        if (finalDir.exists()) finalDir.deleteRecursively()
                        tempDir.renameTo(finalDir)

                        println("✔️ [${idx + 1}/${archives.size}] $id unpacked ($fileCount files)")
                        archive // <- Явно возвращаем File
                    }.onFailure {
                        println("❌ Error during unpacking ${archive.name}: ${it.message}")
                    }.getOrNull()
                }
            }
        }.awaitAll()

        val successfullyUnpacked = results.filterNotNull()

        if (successfullyUnpacked.isNotEmpty()) {
            println("🧹 Deleting ${successfullyUnpacked.size} successfully unpacked archives...")
            successfullyUnpacked.forEach { zip: File ->
                try {
                    if (zip.exists()) {
                        val deleted = zip.delete()
                        if (!deleted) println("⚠️ Unable to delete ${zip.name}")
                    }
                } catch (e: Exception) {
                    println("⚠️ Error deleting ${zip.name}: ${e.message}")
                }
            }
        }

        println("🎯 Unpacking complete. Total processed: ${archives.size}, successfully: ${successfullyUnpacked.size}")
    }

    /**
     * Cleans up temporary cache data after successful downloads.
     *
     * Removes the entire ***cache** directory to ensure fresh state for the next run.
     */
    private fun cleanup() {
        if (cacheDir.exists()) cacheDir.deleteRecursively()
    }
}