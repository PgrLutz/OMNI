package dev.omalauncher.backend

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** [s] as one single-quoted shell word, safe against embedded quotes and spaces. */
fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

/**
 * Host-side binaries the chroot backend needs, resolved from [ChrootConfig].
 *
 * Nothing here is hardcoded to Termux: the paths come from a configured
 * prefix, and each tool can be overridden. Today the default prefix happens
 * to be Termux's (`$PREFIX/usr`), because that is where the user's chroot
 * support tools live; the end goal is a backend that runs with any prefix,
 * or none (rootfs and tools reachable some other way).
 */
data class HostTools(
    val bash: String,
    val mount: String,
    val umount: String,
    val chroot: String,
    val cat: String,
    val grep: String,
    val pkill: String,
    val ps: String,
) {

    /** Every host tool path the backend requires, for escalated presence probes. */
    fun paths(): List<String> = listOf(mount, umount, chroot, cat, grep, pkill, ps, bash)

    /** The first missing tool: (name, path), or null when every tool is there. */
    fun missing(): Pair<String, String>? =
        listOf(
            "bash" to bash,
            "mount" to mount,
            "umount" to umount,
            "chroot" to chroot,
            "cat" to cat,
            "grep" to grep,
            "ps" to ps,
        ).firstOrNull { (_, path) -> !File(path).isFile }

    companion object {
        /** All tools under one prefix, the standard Termux layout. */
        fun forPrefix(prefix: String): HostTools =
            HostTools(
                bash = "$prefix/bin/bash",
                mount = "$prefix/bin/mount",
                umount = "$prefix/bin/umount",
                chroot = "$prefix/bin/chroot",
                cat = "$prefix/bin/cat",
                grep = "$prefix/bin/grep",
                pkill = "$prefix/bin/pkill",
                ps = "$prefix/bin/ps",
            )
    }
}

/**
 * How host commands are granted root. KernelSU's `su` today; anything else
 * (a helper daemon, an adb root session, a different manager) slots in
 * behind this without the backend or widgets knowing.
 */
interface Escalator {
    /** Wraps one command line into the argv that runs it as root. */
    fun asRoot(commandLine: String): List<String>

    /** A [ProcessBuilder] that runs [commandLine] as root, stderr separate from stdout. */
    fun spawn(commandLine: String): ProcessBuilder

    /**
     * Runs [commandLine] as root to completion without a local process — the
     * Termux transport uses RUN_COMMAND (whose uid is allowlisted and stable,
     * unlike this app's own uid under an ephemeral root). The default runs
     * the local [spawn] process, as before.
     */
    suspend fun runToCompletion(commandLine: String, timeoutSeconds: Long): Triple<Int, String, String> =
        withContext(Dispatchers.IO) { drainBlocking(this@Escalator, commandLine, timeoutSeconds) }
}

/** Escalates through `su -c <command line>`, KernelSU's interface. */
class SuEscalator(
    private val suPath: String,
) : Escalator {
    override fun asRoot(commandLine: String): List<String> = listOf(suPath, "-c", commandLine)

    override fun spawn(commandLine: String): ProcessBuilder =
        ProcessBuilder(asRoot(commandLine)).apply {
            redirectErrorStream(false)
            environment()["TERM"] = "xterm-256color"
        }
}
