package dev.omalauncher.backend

import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * The backend abstraction: everything the launcher runs Linux commands in.
 *
 * Deliberately free of implementation details: a backend may be a Kali
 * chroot entered through a privilege escalator, a native Termux environment,
 * or anything else added later. Widgets, the runtime and the UI talk only to
 * this interface, never to a concrete backend.
 */
interface Backend {

    /** Where the backend is: [BackendState.Ready], or why it isn't. */
    val state: StateFlow<BackendState>

    /** The guest home directory, as the guest sees it ("/root" for a chroot). */
    val guestHome: String

    /**
     * Runs [script] once, outside the pooled shells, to completion. Streams
     * output lines to [onLine] as they come. A command that cannot start
     * throws. Use for installs, package work and other one-shot jobs.
     */
    suspend fun run(
        script: String,
        timeoutSeconds: Int = 3600,
        onLine: (String) -> Unit = {},
    ): CmdResult

    /**
     * Runs [command] through a pooled persistent shell, so many short
     * commands (widget data) share one process. One at a time per shell,
     * each under a [timeoutSeconds] timeout. With [retry], a command whose
     * shell died runs again, which is only safe for commands without side
     * effects.
     */
    suspend fun cmd(
        command: String,
        timeoutSeconds: Int = 10,
        retry: Boolean = true,
    ): CmdResult

    /**
     * Runs [command] long-lived, feeding its output lines to [onLine] until
     * the command exits. Used for `stream(...)` widget sources.
     */
    suspend fun stream(
        command: String,
        onLine: (String) -> Unit,
    )

    /**
     * Starts [script] as a long-lived process with stdin/stdout/stderr
     * pipes the caller reads and writes. Used by the voice session and
     * agents, which need a live conversation, not a bounded command.
     * The caller owns the process and must destroy it.
     */
    fun spawn(script: String): Process

    /** The host file a guest path maps to, or null when it's outside the guest. */
    fun guestFile(guestPath: String): File?

    /** Shuts the backend down: kills pooled and spawned processes. Mounts are left; see the implementation. */
    fun close()
}

/** What a bounded or pooled command printed, and how it exited. */
data class CmdResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
) {
    /** 124: killed by `timeout`; 137: SIGKILLed (timeout's kill-after, or cancellation). */
    val timedOut: Boolean get() = exitCode == 124 || exitCode == 137

    /** Output hit the capture cap (head -c closed the pipe, SIGPIPE 141): complete but truncated. NOT a timeout. */
    val capped: Boolean get() = exitCode == 141
}

/** Where the backend stands. */
sealed interface BackendState {
    /** Checking readiness; the state before [Backend] is usable. */
    data object Checking : BackendState

    /** Ready: commands can go. */
    data object Ready : BackendState

    /** Why the backend is not usable, shown in settings and by widget placeholders. */
    sealed interface Failed : BackendState {
        val message: String
    }

    /** The root filesystem is not there; [path] is where it was expected. */
    data class RootfsMissing(val path: String, val detail: String = "") : Failed {
        override val message = "rootfs not found at $path" + if (detail.isBlank()) "" else " ($detail)"
    }

    /** A host binary the backend needs is missing; nothing is assumed about Termux. */
    data class HostToolMissing(val tool: String, val path: String) : Failed {
        override val message = "host tool $tool not found at $path"
    }

    /** No privilege escalation works (KernelSU su expected, but configurable). */
    data class PrivilegeUnavailable(val detail: String) : Failed {
        override val message = "no privilege escalation: $detail"
    }

    /** Entering the guest (mounts) failed; [log] holds the entry script's output. */
    data class MountFailed(val log: List<String>) : Failed {
        override val message = "could not enter the guest"
    }
}
