package dev.dontblameme.selvage.engine

/**
 * Where a caught error goes. The engine has no platform logger, so the caller supplies one and is
 * handed the throwable itself rather than its `toString()`. The sink is called on the thread that
 * caught the error — the transport's, the scheduler's or a listener's — so it must neither block
 * nor throw.
 */
fun interface ErrorSink {
    fun onError(
        what: String,
        error: Throwable,
    )
}

/**
 * The sink for a caller that supplies none. One bounded line per report on stderr: the sites it
 * serves run per frame and per tick, so a caller that wants the frames — an IDE, through its own
 * logger — supplies its own sink rather than paying for a stack trace here.
 */
object StderrErrorSink : ErrorSink {
    override fun onError(
        what: String,
        error: Throwable,
    ) {
        System.err.println("$what: $error")
    }
}
