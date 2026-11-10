package dev.omalauncher.chroot

import dev.omalauncher.backend.CmdResult
import dev.omalauncher.backend.ProcessFactory
import dev.omalauncher.termux.TermuxBackend
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.FileReader
import java.io.IOException

/**
 * The persistent guest session over the Termux transport.
 *
 * RUN_COMMAND is one-shot, so a long-lived guest shell is started in the
 * BACKGROUND: inside the chroot it creates two FIFOs in /run/user/0 (the
 * app's own xdg dir bound there), holds all four ends, and executes one
 * command per line. The app talks to it through the bind at
 * `<filesDir>/xdg` — its own directory, so no SELinux wall — sending a
 * command line and reading output until the `__OMARCHY_EXIT__<code>` marker.
 */
class SessionBridge(
    private val filesDir: File,
    private val factory: ProcessFactory,
    private val context: android.content.Context,
) {
    private val dir = File(filesDir, "xdg")
    private val cmdFifo = File(dir, "bc.cmd")
    private val outFifo = File(dir, "bc.out")
    private val quitLine = "__OMARCHY_QUIT__"

    private var writer: FileOutputStream? = null
    private var reader: BufferedReader? = null

    /** The guest-side bridge loop: one command per line, output until the exit marker. */
    internal fun script(): String =
        """
        set +e
        D=/run/user/0
        mkdir -p "${'$'}D" && chmod 777 "${'$'}D"
        mkfifo "${'$'}D/bc.cmd" 2>/dev/null; mkfifo "${'$'}D/bc.out" 2>/dev/null
        chmod 666 "${'$'}D/bc.cmd" "${'$'}D/bc.out"
        exec 3>"${'$'}D/bc.out"
        exec 4<"${'$'}D/bc.cmd"
        while IFS= read -r line <&4; do
          if [ "${'$'}line" = "$quitLine" ]; then break; fi
          printf '%s\n' "${'$'} ${'$'}line" >&3
          ( eval "${'$'}line" ) >&3 2>&3
          code=${'$'}?
          printf '__OMARCHY_EXIT__%s\n' "${'$'}code" >&3
        done
        exec 3>&- 4<&-
        """.trimIndent()

    /** Starts the guest shell in the background and opens both FIFO ends. */
    fun start(timeoutSeconds: Int = 10): Boolean {
        val escalated = factory.commandString("bash --noprofile --norc -c " + dev.omalauncher.backend.shellQuote(script()))
        // Through Termux's own su (allowlisted, ksu domain) -> chroot -> bridge.
        TermuxBackend.launchBackground(context, "su -c ${dev.omalauncher.backend.shellQuote(escalated)}")
        return connect(timeoutSeconds)
    }

    /**
     * Opens both FIFO ends. The bridge holds the other ends, so these opens
     * complete once the session is up; retry until the deadline.
     */
    private fun connect(timeoutSeconds: Int): Boolean {
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            try {
                val w = FileOutputStream(cmdFifo)
                val r = BufferedReader(FileReader(outFifo))
                writer = w
                reader = r
                return true
            } catch (e: IOException) {
                Thread.sleep(200)
            }
        }
        return false
    }

    /**
     * Sends one guest command and reads until the exit marker. Output is the
     * bridge's merged stdout+stderr; the `$ line` echo and the marker are
     * stripped. Caps output at 4 MiB so a noisy command cannot wedge us.
     */
    fun send(command: String, timeoutSeconds: Int = 30): CmdResult {
        val w = writer ?: throw IllegalStateException("session bridge not connected")
        val r = reader ?: throw IllegalStateException("session bridge not connected")
        w.write(command.replace('\n', ' ').toByteArray())
        w.write('\n'.toByte())
        w.flush()
        val out = StringBuilder()
        var code = -1
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
        val cap = 4 * 1024 * 1024
        while (System.currentTimeMillis() < deadline) {
            val line = r.readLine() ?: break
            val marker = "__OMARCHY_EXIT__"
            if (line.startsWith(marker)) {
                code = line.removePrefix(marker).trim().toIntOrNull() ?: -1
                return CmdResult(out.toString().trimEnd(), "", code)
            }
            if (line == "\$ $command" || line.startsWith("\$ ")) continue
            if (out.length < cap) out.appendLine(line)
        }
        return CmdResult(out.toString().trimEnd(), "", if (code >= 0) code else -1)
    }

    /** Tells the guest shell to exit; the background RUN_COMMAND then finishes. */
    fun close() {
        runCatching { writer?.write(quitLine.toByteArray()); writer?.flush() }
        runCatching { writer?.close(); reader?.close() }
        writer = null
        reader = null
    }
}
