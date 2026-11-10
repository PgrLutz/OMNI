package dev.omalauncher.backend

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Runs an escalated host command to completion, draining stdout and stderr
 * CONCURRENTLY so a process that fills a pipe buffer never deadlocks on a
 * read. The process is waited on while the drain threads run, then joined
 * briefly. Returns (exit code, stdout, stderr); a process that would not
 * finish is killed and reports -1.
 */
suspend fun drain(
    esc: Escalator,
    command: String,
    timeoutSeconds: Long,
): Triple<Int, String, String> = esc.runToCompletion(command, timeoutSeconds)

/** Blocking variant of [drain], for non-suspend call sites (process registration). */
internal fun drainBlocking(
    esc: Escalator,
    command: String,
    timeoutSeconds: Long,
): Triple<Int, String, String> {
    val p = esc.spawn(command).start()
    val r = drainProcessBlocking(p, timeoutSeconds, null)
    return Triple(r.exitCode, r.stdout, r.stderr)
}

/**
 * Drains an already-started [Process] to completion, stdout and stderr in
 * parallel (no pipe-buffer deadlock), feeding stdout lines to [onLine] as
 * they come. A process that does not finish within [timeoutSeconds] is
 * killed and reports exit code -1.
 */
internal fun drainProcessBlocking(
    p: Process,
    timeoutSeconds: Long,
    onLine: ((String) -> Unit)?,
): CmdResult {
    val out = StringBuilder()
    val err = StringBuilder()
    val lock = Any()
    val outThread = Thread {
        runCatching {
            p.inputStream.bufferedReader().forEachLine { line ->
                synchronized(lock) {
                    out.appendLine(line)
                    onLine?.invoke(line)
                }
            }
        }
    }.apply { isDaemon = true; start() }
    val errThread = Thread {
        runCatching { p.errorStream.bufferedReader().forEachLine { line -> synchronized(lock) { err.appendLine(line) } } }
    }.apply { isDaemon = true; start() }
    val finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS)
    if (!finished) {
        p.destroyForcibly()
        p.waitFor(5, TimeUnit.SECONDS)
    }
    outThread.join(2_000)
    errThread.join(2_000)
    val code = if (finished) p.exitValue() else runCatching { p.exitValue() }.getOrElse { -1 }
    return CmdResult(out.toString().trimEnd(), err.toString().trimEnd(), code)
}

/** The start time field (22) of a `/proc/<pid>/stat` dump, the classic pid-reuse detector. */
internal fun parseStarttime(statText: String): Long? {
    // "pid (comm) state ..." — the name may hold spaces and parentheses, so read after the last ')'.
    val tail = statText.substringAfterLast(')').trim()
    if (tail.isEmpty()) return null
    val fields = tail.split(' ')
    // Field 22 overall; after comm (field 2), the tail starts at field 3, so index 22 - 3 = 19.
    return fields.getOrNull(19)?.toLongOrNull()
}
