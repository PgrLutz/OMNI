package dev.omalauncher.termux

import android.content.Context
import dev.omalauncher.backend.Escalator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Escalates through Termux's RUN_COMMAND instead of this app's own `su`.
 *
 * Under an ephemeral root (a KernelSU LKM loaded by an exploit), the kernel
 * snapshots the uid→package table at module load: apps installed afterwards —
 * like this one — stay invisible to the allowlist and their `su` is hidden.
 * Termux's own uid is allowlisted and stable, so every escalated command goes
 * through it; that is the original design (OMNI → Termux → su).
 *
 * RUN_COMMAND is one-shot: it returns stdout/stderr/exit code when the
 * command exits, so only run-to-completion calls (probes, mounts, one-shot
 * guest commands) work here. Persistent processes (shell pools, agents,
 * voice) need the Termux session bridge — the next migration group.
 */
class TermuxEscalator(
    private val context: Context,
) : Escalator {
    override fun asRoot(commandLine: String): List<String> =
        throw UnsupportedOperationException("Termux transport has no local process argv; persistent guest processes need the Termux session bridge")

    override fun spawn(commandLine: String): ProcessBuilder =
        throw UnsupportedOperationException("Termux transport has no local process; persistent guest processes need the Termux session bridge")

    override suspend fun runToCompletion(commandLine: String, timeoutSeconds: Long): Triple<Int, String, String> =
        withContext(Dispatchers.IO) {
            // Escalate through Termux's OWN su: Termux's uid is allowlisted, so
            // its su reaches the ksu domain (which can read Termux's data),
            // unlike this app's direct su under the ephemeral-root snapshot.
            val escalated = "su -c ${dev.omalauncher.backend.shellQuote(commandLine)}"
            val r = TermuxBackend.run(context, escalated, timeoutSeconds = timeoutSeconds.coerceIn(1, 120).toInt())
            Triple(r.exitCode, r.stdout, r.stderr)
        }
}
