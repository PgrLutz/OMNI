package dev.omalauncher.widgets.runtime

import dev.omalauncher.linux.ChrootGuest
import dev.omalauncher.linux.LinuxGuest
import dev.omalauncher.linux.killTree
import dev.omalauncher.linux.shellQuote
import dev.omalauncher.widgets.lang.WidgetError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Long-running guest commands (`source x = stream("playerctl -F ...")`), one
 * process per distinct command however many widgets listen, stopped a few
 * seconds after the last listener goes.
 */
class Streams(
    private val guest: LinuxGuest,
    private val scope: CoroutineScope,
) {
    /** [flow] carries lines, then null when the command exits. */
    private class Entry(
        val flow: SharedFlow<String?>,
        var listeners: Int = 0,
    )

    private val entries = HashMap<String, Entry>()

    suspend fun collect(
        command: String,
        onLine: (String) -> Unit,
    ) {
        val entry =
            synchronized(entries) {
                entries[command] ?: run {
                    if (entries.size >= MAX) throw WidgetError("at most $MAX different streams run at once")
                    Entry(open(command)).also { entries[command] = it }
                }
            }
        synchronized(entries) { entry.listeners++ }
        var ended = false
        try {
            // Returning tells the widget the command exited, so it restarts it after a pause.
            entry.flow
                .takeWhile { line ->
                    ended = line == null
                    !ended
                }.collect { onLine(it!!) }
        } finally {
            synchronized(entries) {
                entry.listeners--
                if (ended) {
                    // The next listener must start a new process, not replay this one's end.
                    if (entries[command] === entry) entries.remove(command)
                } else if (entry.listeners == 0) {
                    // The process lingers a few seconds for a quick return (swiping between pages); keep its entry as long.
                    scope.launch {
                        delay(LINGER)
                        synchronized(entries) { if (entry.listeners == 0 && entries[command] === entry) entries.remove(command) }
                    }
                }
            }
        }
    }

    private fun open(command: String): SharedFlow<String?> =
        callbackFlow<String?> {
            if (guest is ChrootGuest && guest.transportIsTermux) {
                // The Termux transport has no local persistent process: the
                // writer runs detached in the guest through the bridge and the
                // app tails the bound output file, ending when the pid is gone.
                val tag = "stream-" + command.hashCode().toUInt().toString(16)
                val pidFile = guest.streamOutput("$tag.pid")
                val outFile = guest.streamOutput("$tag.out")
                outFile.delete()
                if (!guest.streamBackground(command.trimPrefix("exec bash -c ").trim(), tag)) {
                    close()
                    awaitClose { }
                    return@callbackFlow
                }
                val pid = runCatching { pidFile.readText().trim().toLongOrNull() }.getOrNull()
                launch(Dispatchers.IO) {
                    var off = 0L
                    var lastLen = -1L
                    while (isActive) {
                        val len = outFile.length()
                        if (len > off) {
                            RandomAccessFile(outFile, "r").use { raf ->
                                raf.seek(off)
                                raf.readBytes().toString(Charsets.UTF_8).lineSequence().forEach { line -> if (line.isNotEmpty()) trySend(line) }
                            }
                            off = len
                        }
                        // Writer gone and no new output for a beat: the stream ended.
                        if (pid != null && !File("/proc/$pid").exists() && len == lastLen) {
                            trySend(null)
                            break
                        }
                        lastLen = len
                        delay(200)
                    }
                    close()
                }
                awaitClose {
                    guest.stopStream(tag)
                    outFile.delete()
                }
            } else {
                val process = guest.process("exec bash -c " + shellQuote(command)).start()
                launch(Dispatchers.IO) {
                    try {
                        process.inputStream.bufferedReader().useLines { lines -> lines.forEach { trySend(it) } }
                        trySend(null)
                    } catch (e: IOException) {
                        // Stopping destroys the process mid-read: that's the normal way a stream ends.
                    }
                    close()
                }
                // Killing the process closes its output, which ends the reader.
                awaitClose { killTree(process) }
            }
        }.flowOn(Dispatchers.IO)
            // The sharing coroutine lives in the engine's scope; if it failed, the app would crash.
            .catch { }
            .shareIn(scope, SharingStarted.WhileSubscribed(LINGER, 0), replay = 1)

    companion object {
        const val MAX = 4
        private const val LINGER = 5000L
    }
}
