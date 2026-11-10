package dev.omalauncher.backend

import java.io.File

/**
 * Builds the escalated argv that runs one guest process, preserving the
 * original OMNI execution model: every consumer gets its own process,
 * pipes, cwd and environment.
 *
 * KernelSU execs the su command line, leaving the guest command in the
 * process started by ProcessBuilder. ProcessRegistry resolves that guest
 * PID from the unique OMNI_SESSION environment token, then captures
 * /proc/<pid>/stat starttime for PID-reuse protection. Android's
 * java.lang.Process API does not expose pid() to this source set.
 */
class ProcessFactory(
    private val config: ChrootConfig,
    private val esc: Escalator,
) {
    /** Environment pairs passed to env -i; token is inherited by descendants. */
    fun guestEnv(token: String? = null): List<String> =
        buildList {
            add("HOME=${config.guestHome}")
            add("USER=root")
            add("LOGNAME=root")
            add("SHELL=/bin/bash")
            add("TERM=xterm-256color")
            add("LANG=C.UTF-8")
            add("PATH=${config.guestPath}")
            add("XDG_RUNTIME_DIR=${config.runtimeDirGuest}")
            add("WAYLAND_DISPLAY=wayland-0")
            addAll(config.env.map { (k, v) -> "$k=$v" })
            token?.let { add("OMNI_SESSION=$it") }
        }

    /** One guest command line with an explicit working directory, defaulting to guest home. */
    fun guestLine(
        inner: String,
        cwd: String? = null,
    ): String {
        val cd = cwd?.let { "cd ${shellQuote(it)} && " } ?: "cd ${shellQuote(config.guestHome)} && "
        return "${cd}exec $inner"
    }

    /** Full escalated argv for the requested guest line. */
    fun argv(
        line: String,
        token: String? = null,
    ): List<String> {
        val env = guestEnv(token).joinToString(" ")
        val suCommand =
            "exec chroot ${shellQuote(config.rootfs)} /usr/bin/env -i $env " +
                shellQuote("/bin/bash") + " -c " + shellQuote(line)
        return esc.asRoot(suCommand)
    }

    /** One bounded or streamed guest script. */
    fun spawnBash(
        script: String,
        cwd: String? = null,
        token: String? = null,
    ): ProcessBuilder = ProcessBuilder(argv(guestLine("bash --noprofile --norc -c ${shellQuote(script)}", cwd), token))

    /**
     * The escalated command STRING for [line] (without a local process): the
     * Termux transport runs exactly this through RUN_COMMAND.
     */
    fun commandString(
        line: String,
        token: String? = null,
    ): String {
        val env = guestEnv(token).joinToString(" ")
        return "exec chroot ${shellQuote(config.rootfs)} /usr/bin/env -i $env " +
            shellQuote("/bin/bash") + " -c " + shellQuote(line)
    }

    /** A persistent protocol shell driven through stdin. */
    fun spawnShell(
        cwd: String? = null,
        token: String? = null,
    ): ProcessBuilder = ProcessBuilder(argv(guestLine("exec bash --noprofile --norc", cwd), token))

    /** Raw guest command (agents/voice), with independent stdin/stdout/stderr pipes. */
    fun spawn(
        guestCommand: String,
        cwd: String? = null,
        token: String? = null,
    ): ProcessBuilder = ProcessBuilder(argv(guestLine(guestCommand, cwd), token))
}

/** Host file corresponding to a guest path, or null when the path escapes the rootfs. */
fun guestFileFor(config: ChrootConfig, guestPath: String): File? {
    if (!guestPath.startsWith("/")) return null
    // The app-visible rootfs: the sandbox cannot stat Termux's private dirs.
    val root = File(config.appRootfs).canonicalFile
    val file = File(root, guestPath.trimStart('/')).canonicalFile
    return file.takeIf { it.path.startsWith(root.path) }
}
