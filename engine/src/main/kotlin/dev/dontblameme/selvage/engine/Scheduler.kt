package dev.dontblameme.selvage.engine

import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

fun interface Cancellable {
    fun cancel()
}

/** The session's clock and timers; a test injects one it advances by hand. */
interface Scheduler {
    /** A monotone clock in milliseconds. */
    fun nowMs(): Long

    fun schedule(
        delayMs: Long,
        task: Runnable,
    ): Cancellable

    /** Called once the session that owns this scheduler is left. */
    fun shutdown() {}
}

/** One daemon thread and the monotone system clock. */
class ThreadScheduler(
    name: String = "selvage-session",
) : Scheduler {
    private val executor =
        ScheduledThreadPoolExecutor(1) { runnable -> Thread(runnable, name).apply { isDaemon = true } }.apply {
            removeOnCancelPolicy = true
        }

    override fun nowMs(): Long = System.nanoTime() / 1_000_000

    override fun schedule(
        delayMs: Long,
        task: Runnable,
    ): Cancellable {
        val future = executor.schedule(task, delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
        return Cancellable { future.cancel(false) }
    }

    override fun shutdown() {
        executor.shutdownNow()
    }
}
