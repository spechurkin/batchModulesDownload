package proj.dnd

import kotlinx.coroutines.coroutineScope
import proj.dnd.ModuleManager.downloadAll
import proj.dnd.ModuleManager.loadUrlsFromFile
import proj.dnd.ModuleManager.unpackAll

/**
 * Entry point for batch module processing.
 *
 * Reads a list of module manifest URLs (typically GitHub or Dropbox links) from a text file
 * and triggers the full download and unpack pipeline via [ModuleManager].
 *
 * The file path can be passed as a command-line argument:
 * ```
 * $ kotlin MainKt modules.txt
 * ```
 * If no argument is provided, it defaults to `modules.txt` in the working directory.
 *
 * Each line in the file should contain one valid URL.
 * Lines starting with `#` are treated as comments and ignored.
 *
 * Example file:
 * ```
 * # github link example
 * https://github.com/example/example-module/blob/master/module.json
 * https://github.com/example/example-module/releases/download/LATEST/module.json
 *
 * # dropbox link example
 * https://www.dropbox.com/scl/fi/whatever/module.json?rlkey=somekey&dl=1
 * ```
 *
 * @param args Optional CLI argument specifying the path to the links file.
 */
suspend fun main(args: Array<String>) = coroutineScope {
    val urlsFile = if (args.isNotEmpty()) args[0] else "modules.txt"

    val urls = loadUrlsFromFile(urlsFile)
    if (urls.isEmpty()) {
        println("❌ Empty links file! Add them to $urlsFile")
        return@coroutineScope
    }

    println("🔗 ${urls.size} links downloaded from $urlsFile")

    downloadAll(urls)
    unpackAll()
}