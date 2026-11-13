package proj.dnd

import org.junit.jupiter.api.Test
import java.io.IOException
import java.nio.file.AccessDeniedException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FileOperationsTest {
    @Test
    fun `retries transient Windows access denial`() {
        var attempts = 0
        val result = retryAccessDenied {
            if (++attempts < 3) throw AccessDeniedException("staging", "destination", null)
            "published"
        }

        assertEquals("published", result)
        assertEquals(3, attempts)
    }

    @Test
    fun `other IO failures propagate without retrying`() {
        var attempts = 0

        assertFailsWith<IOException> {
            retryAccessDenied {
                attempts++
                throw IOException("disk full")
            }
        }

        assertEquals(1, attempts)
    }
}
