package dev.hermeskotlin.core.voice

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Lets threads work inside something that [close] will free, without a lock held while they do. [use] counts a
 * thread in and out; [close] turns new ones away, waits for those inside to leave, and only then frees.
 *
 * Its lock is held just long enough to count. So work inside may block on another thread, and that thread may
 * read [closed] or come in itself: neither ever waits on someone who is waiting on it.
 */
internal class CallGate {
    private val lock = ReentrantLock()
    private val empty = lock.newCondition()
    private var inside = 0

    @Volatile
    var closed = false
        private set

    /** Whether the calling thread holds the gate's lock; never true inside [use] or [close]'s block. */
    val lockHeld: Boolean get() = lock.isHeldByCurrentThread

    fun enter(): Boolean = lock.withLock {
        if (closed) return false
        inside++
        true
    }

    fun leave() = lock.withLock {
        if (--inside == 0) empty.signalAll()
    }

    /** Runs [block] unless the gate is closed, which gives [ifClosed] instead. */
    inline fun <T> use(ifClosed: () -> T, block: () -> T): T {
        if (!enter()) return ifClosed()
        try {
            return block()
        } finally {
            leave()
        }
    }

    /**
     * Closes the gate and runs [release] once nobody is inside. Only the first call does; the others return
     * false at once. Not to be called from inside [use].
     */
    fun close(release: () -> Unit): Boolean {
        lock.withLock {
            if (closed) return false
            closed = true
            while (inside > 0) empty.awaitUninterruptibly()
        }
        release()
        return true
    }
}
