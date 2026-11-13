package proj.dnd

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentLinkedQueue

/** Each I/O worker takes the next job only after its current job finishes. */
internal suspend fun <T, R> parallelMap(items: List<T>, workers: Int, action: suspend (T) -> R): List<R> = coroutineScope {
    require(workers > 0)
    val pending = ConcurrentLinkedQueue(items.withIndex().toList())
    List(minOf(workers, items.size)) {
        async(Dispatchers.IO) {
            buildList {
                while (true) {
                    val job = pending.poll() ?: break
                    add(job.index to action(job.value))
                }
            }
        }
    }.awaitAll().flatten().sortedBy { it.first }.map { it.second }
}
