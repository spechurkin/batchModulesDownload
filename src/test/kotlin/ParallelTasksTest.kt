package proj.dnd

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParallelTasksTest {
    @Test
    fun `blocking IO jobs overlap within the worker limit and results retain input order`() = runBlocking {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val ready = CountDownLatch(5)
        val results = parallelMap((1..19).toList(), 5) { value ->
            peak.accumulateAndGet(active.incrementAndGet(), ::maxOf)
            ready.countDown()
            try {
                assertTrue(ready.await(3, TimeUnit.SECONDS), "Workers did not overlap")
                Thread.sleep((20 - value).toLong())
                value * 2
            } finally {
                active.decrementAndGet()
            }
        }

        assertEquals(5, peak.get())
        assertEquals(0, active.get())
        assertEquals((1..19).map { it * 2 }, results)
    }
}
