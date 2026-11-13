package proj.dnd

import kotlinx.coroutines.coroutineScope
import java.io.File
import kotlin.system.exitProcess
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
 * java -jar .\batchModulesDownload.jar modules.txt
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
 * @param args Optional links file and `--dir` / `-d` extraction directory.
 */
suspend fun main(args: Array<String>) = coroutineScope {
    var urlsFile = "modules.txt"
    var linksProvided = false
    var outputDir = File(".")
    var options = true
    var index = 0
    fun usageError(message: String): Nothing {
        System.err.println("$message\nUsage: java -jar batchModulesDownload.jar [modules.txt] [--dir|-d DIRECTORY]")
        exitProcess(2)
    }
    while (index < args.size) {
        val arg = args[index++]
        when {
            options && arg == "--" -> options = false
            options && (arg == "--dir" || arg == "-d") -> {
                if (index == args.size || args[index].isEmpty() || args[index].startsWith("-")) {
                    usageError("Missing directory for $arg")
                }
                outputDir = File(args[index++])
            }
            options && arg.startsWith("--dir=") -> {
                val path = arg.substringAfter('=')
                if (path.isEmpty()) usageError("Missing directory for --dir")
                outputDir = File(path)
            }
            options && arg.startsWith("-") -> usageError("Unknown option: $arg")
            linksProvided -> usageError("Unexpected argument: $arg")
            else -> {
                urlsFile = arg
                linksProvided = true
            }
        }
    }

    val urls = loadUrlsFromFile(urlsFile)
    if (urls.isEmpty()) {
        println("❌ Empty links file! Add them to $urlsFile")
        return@coroutineScope
    }

    println("🔗 ${urls.size} links downloaded from $urlsFile")

    if (!outputDir.isDirectory && !outputDir.mkdirs()) {
        usageError("Cannot create extraction directory: ${outputDir.absolutePath}")
    }
    downloadAll(urls, outputDir)
    unpackAll(outputDir)
}
