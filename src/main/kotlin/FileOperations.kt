package proj.dnd

import java.nio.file.AccessDeniedException

/** Windows scanners can briefly hold files after extraction has closed them. */
internal fun <T> retryAccessDenied(action: () -> T): T {
    for (attempt in 0..5) {
        try {
            return action()
        } catch (e: AccessDeniedException) {
            if (attempt == 5) throw e
            Thread.sleep(100L shl attempt)
        }
    }
    error("Unreachable")
}
