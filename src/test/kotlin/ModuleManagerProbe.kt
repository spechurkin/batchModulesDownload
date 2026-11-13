package proj.dnd

import com.google.gson.GsonBuilder
import kotlinx.coroutines.runBlocking

// A separate JVM gives the singleton its own working directory for each test.
object ModuleManagerProbe {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val result: Any? = when (args[0]) {
            "load" -> ModuleManager.loadUrlsFromFile(args[1])
            "fetch" -> args.drop(1).map { ModuleManager.fetchModule(it) }
            "download" -> ModuleManager.downloadAll(args.drop(1)).let { null }
            "unpack" -> ModuleManager.unpackAll().let { null }
            "cli" -> proj.dnd.main(args.drop(1).toTypedArray()).let { null }
            else -> error("Unknown test operation: ${args[0]}")
        }
        println("RESULT=" + GsonBuilder().serializeNulls().create().toJson(result))
    }
}
