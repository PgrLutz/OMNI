package dev.omalauncher.chroot

import dev.omalauncher.backend.CmdResult
import dev.omalauncher.backend.ProcessFactory
import dev.omalauncher.termux.TermuxBackend
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

/**
 * The persistent guest session over the Termux transport.
 *
 * RUN_COMMAND is one-shot, so a long-lived guest shell is started in the
 * BACKGROUND: inside the chroot it executes one command per line, appending
 * output plus an `__OMARCHY_EXIT__<code>` marker to `/run/user/0/bc.out`
 * (the app's own xdg dir bound there). Commands arrive in a regular file
 * `bc.cmd` that the APP owns (app_data_file — writable by the app, readable
 * by root), so no FIFO open-blocking and no SELinux write wall: the app can
 * only READ the bridge's output file, which the earlier FIFO design could not
 * guarantee for writes.
 */
class SessionBridge(
    private val filesDir: File,
    private val factory: ProcessFactory,
    private val context: android.content.Context?,
) {
    private val dir = File(filesDir, "xdg")
    private val cmdFile = File(dir, "bc.cmd")
    private val outFile = File(dir, "bc.out")
    private val errFile = File(dir, "bc.err")
    private val quitLine = "__OMARCHY_QUIT__"
    private val endLine = "__OMARCHY_END__"

    /** One command at a time: the bridge is a single shared command/output stream. */
    private val sendMutex = kotlinx.coroutines.sync.Mutex()

    /** The guest-side bridge loop: one COMMAND BLOCK per END-marker from bc.cmd, output to bc.out, stderr to bc.err. */
    internal fun script(): String =
        """
        set +e
        D=/run/user/0
        : > "${'$'}D/bc.out"; chmod 666 "${'$'}D/bc.out"
        : > "${'$'}D/bc.err"; chmod 666 "${'$'}D/bc.err"
        while true; do
          if [ -s "${'$'}D/bc.cmd" ]; then
            first=${'$'}(sed -n '1p' "${'$'}D/bc.cmd")
            if [ "${'$'}first" = "$quitLine" ]; then sed -i '1d' "${'$'}D/bc.cmd"; break; fi
            # Read the whole block up to the END marker: newlines must survive
            # (comments, functions, heredocs break when flattened to one line).
            block=""
            while true; do
              line=${'$'}(sed -n '1p' "${'$'}D/bc.cmd")
              sed -i '1d' "${'$'}D/bc.cmd"
              if [ "${'$'}line" = "__OMARCHY_END__" ]; then break; fi
              [ -z "${'$'}line" ] && continue
              block="${'$'}block${'$'}line"${'$'}'\n'
            done
            printf '%s\n' "${'$'} ${'$'}first" >> "${'$'}D/bc.out"
            ( eval "${'$'}block" ) >> "${'$'}D/bc.out" 2>>"${'$'}D/bc.err"
            printf '__OMARCHY_EXIT__%s\n' "${'$'}?" >> "${'$'}D/bc.out"
          fi
          sleep 0.2
        done
        """.trimIndent()

    /** Starts the guest shell in the background; bc.cmd is created app-side so the app can write it. */
    fun start(timeoutSeconds: Int = 10): Boolean {
        val ctx = context ?: return false
        // Clear stale state: any older bridge process and its FIFO/files.
        // The pkill pattern must not appear in this command's own cmdline
        // (the rm path holds "bc.cmd") and must not contain regex metachar
        // (the bridge's "set +e" has a literal plus that pkill eats as a
        // quantifier), so match the unique "while true; do" with the bracket
        // trick instead.
        val clean = factory.commandString("pkill -f '[w]hile true; do' 2>/dev/null; rm -f /run/user/0/bc.cmd /run/user/0/bc.out /run/user/0/bc.err; true")
        runCatching {
            runBlocking { TermuxBackend.run(ctx, "su -c ${dev.omalauncher.backend.shellQuote(clean)}", timeoutSeconds = 10) }
        }
        runCatching { outFile.delete(); cmdFile.delete() }
        runCatching { cmdFile.writeText("") } // app-owned: untrusted_app can write its own file
        val escalated = factory.commandString("bash --noprofile --norc -c " + dev.omalauncher.backend.shellQuote(script()))
        // Through Termux's own su (allowlisted, ksu domain) -> chroot -> bridge.
        TermuxBackend.launchBackground(ctx, "su -c ${dev.omalauncher.backend.shellQuote(escalated)}")
        // Wait until the bridge has created its output file.
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            if (outFile.exists() && outFile.canRead()) return true
            Thread.sleep(200)
        }
        return outFile.exists()
    }

    /**
     * Appends one guest command to bc.cmd and polls bc.out for the exit
     * marker. Output is the bridge's merged stdout+stderr with the `$ line`
     * echo and the marker stripped; capped at 4 MiB.
     */
    fun send(command: String, timeoutSeconds: Int = 30): CmdResult =
        kotlinx.coroutines.runBlocking { sendMutex.withLock { sendLocked(command, timeoutSeconds) } }

    private fun sendLocked(command: String, timeoutSeconds: Int): CmdResult {
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
        // The bridge reads a whole block up to the END marker, so multi-line
        // scripts keep their newlines (comments, functions, heredocs).
        val cmdBytes = (command + "\n" + endLine + "\n").toByteArray(StandardCharsets.UTF_8)
        // Append to the app-owned command file; the bridge consumes line by line.
        runCatching {
            RandomAccessFile(cmdFile, "rw").use { raf ->
                raf.seek(cmdFile.length())
                raf.write(cmdBytes)
            }
        }.getOrElse { return CmdResult("", "cannot write command file: $it", -1) }
        var readFrom = runCatching { outFile.length() }.getOrElse { 0L }
        var errFrom = runCatching { errFile.length() }.getOrElse { 0L }
        val out = StringBuilder()
        val cap = 4 * 1024 * 1024
        while (System.currentTimeMillis() < deadline) {
            val len = runCatching { outFile.length() }.getOrElse { 0L }
            if (len > readFrom) {
                val text =
                    runCatching {
                        RandomAccessFile(outFile, "r").use { raf ->
                            raf.seek(readFrom)
                            val b = ByteArray((len - readFrom).coerceAtMost(1_000_000).toInt())
                            raf.readFully(b)
                            String(b, StandardCharsets.UTF_8)
                        }
                    }.getOrElse { null }
                if (text != null) {
                    readFrom = len
                    for (line in text.lineSequence()) {
                        val marker = "__OMARCHY_EXIT__"
                        if (line.startsWith(marker)) {
                            val code = line.removePrefix(marker).trim().toIntOrNull() ?: -1
                            val err =
                                runCatching {
                                    if (errFile.length() > errFrom) {
                                        RandomAccessFile(errFile, "r").use { raf ->
                                            raf.seek(errFrom)
                                            val b = ByteArray((errFile.length() - errFrom).coerceAtMost(8 * 1024).toInt())
                                            raf.readFully(b)
                                            String(b, StandardCharsets.UTF_8).trimEnd()
                                        }
                                    } else ""
                                }.getOrElse { "" }
                            return CmdResult(out.toString().trimEnd(), err, code)
                        }
                        if (line == "\$ $command" || line.startsWith("\$ ")) continue
                        if (out.length < cap) out.appendLine(line)
                    }
                }
            }
            Thread.sleep(100)
        }
        return CmdResult(out.toString().trimEnd(), "", -1)
    }

    /** Writes [content] to [guestPath] as root through the bridge: the app's
     * own uid cannot write the rootfs (SELinux), so guest file provisioning
     * must go through the persistent session. Base64 avoids quoting issues.
     */
    fun writeFile(guestPath: String, content: ByteArray, executable: Boolean = false): Boolean {
        val b64 = android.util.Base64.encodeToString(content, android.util.Base64.NO_WRAP)
        val mode = if (executable) "755" else "644"
        val quoted = dev.omalauncher.backend.shellQuote(guestPath)
        val cmd = "printf '%s' '$b64' | base64 -d > $quoted && chmod $mode $quoted"
        return send(cmd, timeoutSeconds = 20).exitCode == 0
    }

    /** Tells the guest shell to exit; the background RUN_COMMAND then finishes. */
    fun close() {
        runCatching {
            RandomAccessFile(cmdFile, "rw").use { raf ->
                raf.seek(cmdFile.length())
                raf.write((quitLine + "\n").toByteArray(StandardCharsets.UTF_8))
            }
        }
    }
}
