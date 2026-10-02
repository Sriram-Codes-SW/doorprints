package app.doorprints.drive

import kotlinx.coroutines.CompletableDeferred

/**
 * Where the browser's answer arrives (S4b-BL-117). [AndroidAuthBrowser] waits on [begin]; `MainActivity` hands the
 * redirect VIEW intent to [deliver]. One sign-in at a time; an answer nobody waits for is dropped (any app can send the
 * intent: the sign-in checks `state`, so a stranger's link opens nothing).
 */
object DriveRedirects {
    private val lock = Any()
    private var waiting: CompletableDeferred<String?>? = null
    private var since = 0L

    fun begin(nowMs: Long = System.currentTimeMillis()): CompletableDeferred<String?> {
        val d = CompletableDeferred<String?>()
        synchronized(lock) {
            waiting?.complete(null)
            waiting = d
            since = nowMs
        }
        return d
    }

    /** True when a sign-in was waiting for it. */
    fun deliver(uri: String?): Boolean = synchronized(lock) {
        val d = waiting ?: return false
        waiting = null
        d.complete(uri)
    }

    /** The app came back to the front with no answer: the person closed the browser. A just-started wait is left alone. */
    fun resumed(nowMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            val d = waiting ?: return
            if (nowMs - since < RESUME_GRACE_MS) return
            waiting = null
            d.complete(null)
        }
    }

    fun end(d: CompletableDeferred<String?>) {
        synchronized(lock) { if (waiting === d) waiting = null }
    }

    /** The browser can take a while (a Google password, a 2-step check). */
    const val WAIT_MS = 10 * 60_000L
    private const val RESUME_GRACE_MS = 1_500L
}
