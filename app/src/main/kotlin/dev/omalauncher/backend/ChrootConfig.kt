package dev.omalauncher.backend

import java.util.concurrent.ConcurrentHashMap

/**
 * Everything the chroot backend needs to know about its environment.
 *
 * The current defaults point at the user's real setup (a Kali rootfs that
 * today lives under Termux's proot-distro directory, entered through
 * KernelSU's `su`), but every value is configurable: the backend must be
 * able to run from any prefix — or none — once the rootfs and host tools are
 * reachable some other way. Termux is a temporary host dependency, never an
 * assumption baked into the code.
 */
data class ChrootConfig(
    /** The Kali rootfs directory (the directory `chroot` is called on). */
    val rootfs: String,
    /** Host binaries, resolved from a prefix or overridden individually. */
    val hostTools: HostTools,
    /** Privilege escalation candidates, tried in order. */
    val suCandidates: List<String>,
    /** Where OMNI keeps its runtime state (mount ledger, process registry); filesDir in the app. */
    val stateDir: String,
    /** How many persistent shells the pool keeps. */
    val shellCount: Int = 3,
    /** Default timeout for pooled widget commands, seconds. */
    val shellTimeoutSeconds: Int = 10,
    /** `/etc/hosts` source synced into the rootfs on entry, as the user's own chroot entry does. */
    val hostsSource: String = "/system/etc/hosts",

    /**
     * The guest XDG_RUNTIME_DIR. The launcher binds its own runtime dir
     * (stateDir/xdg: the Wayland socket, the bridge FIFOs) here, so guest
     * processes reach the same socket and FIFOs the legacy proot guest sees.
     */
    val runtimeDirGuest: String = "/run/user/0",
    /** The guest home directory, as the guest sees it. */
    val guestHome: String = "/root",
    /** Guest PATH inside the rootfs, a standard Kali layout. */
    val guestPath: String = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
    /**
     * Extra environment pairs passed to every guest process. The backend adds
     * `OMNI_SESSION=<token>` itself per spawn; consumers may add more here.
     */
    val extraEnv: Map<String, String> = emptyMap(),
) {
    /**
     * Mutable extra environment, initialised from [extraEnv]; consumers push
     * per-run environment (GPU, agents) here and every subsequent guest
     * process picks it up. Thread-safe; read by [ProcessFactory.guestEnv].
     */
    val env: MutableMap<String, String> = ConcurrentHashMap(extraEnv)

    /**
     * The app-visible rootfs: the real Kali rootfs bound (by root, at prepare)
     * into the launcher's own directory. The Android sandbox cannot stat
     * Termux's private dirs, so every app-side File access to the guest goes
     * through this bind; the guest-side chroot and mounts keep using [rootfs].
     */
    val appRootfs: String get() = "$stateDir/chroot-rootfs"

    /**
     * The launcher's data dirs bound into the guest at their proot paths, so
     * guest tools (owl-check/owl-send, widgets, themes) see the launcher's
     * real files: guestPath to host-source (relative to the state dir).
     */
    val launcherDataBinds: List<Pair<String, String>> get() =
        listOf(
            "/root/.local/share/omarchy" to "$stateDir/omarchy",
            "/root/.config/omalauncher/widgets" to "$stateDir/widgets",
            "/root/.config/omalauncher/style" to "$stateDir/style",
            "/root/.config/omalauncher/home" to "$stateDir/home",
            "/root/.config/omarchy/themes" to "$stateDir/themes",
            "/root/.config/omarchy/backgrounds" to "$stateDir/backgrounds",
        )

    /** Overrides from any source (prefs, a config file): keys matching field names win. */
    fun withOverrides(map: Map<String, String>): ChrootConfig {
        var out = this
        map["rootfs"]?.let { out = out.copy(rootfs = it) }
        map["su"]?.let { out = out.copy(suCandidates = listOf(it)) }
        map["stateDir"]?.let { out = out.copy(stateDir = it) }
        map["hostsSource"]?.let { out = out.copy(hostsSource = it) }
        // Per-tool overrides, e.g. "tool.mount=/custom/mount".
        val tools = out.hostTools
        out = out.copy(
            hostTools =
                tools.copy(
                    bash = map["tool.bash"] ?: tools.bash,
                    mount = map["tool.mount"] ?: tools.mount,
                    umount = map["tool.umount"] ?: tools.umount,
                    chroot = map["tool.chroot"] ?: tools.chroot,
                    cat = map["tool.cat"] ?: tools.cat,
                    grep = map["tool.grep"] ?: tools.grep,
                    pkill = map["tool.pkill"] ?: tools.pkill,
                    ps = map["tool.ps"] ?: tools.ps,
                ),
        )
        return out
    }

    companion object {
        /**
         * Defaults for the user's current setup: Termux's prefix. This is the
         * temporary default — the goal is a backend whose paths all come from
         * configuration, so the Termux dependency can be removed later.
         */
        fun termuxDefaults(
            stateDir: String,
            prefix: String = "/data/data/com.termux/files/usr",
        ): ChrootConfig =
            ChrootConfig(
                rootfs = "$prefix/var/lib/proot-distro/containers/kali-rolling/rootfs",
                hostTools = HostTools.forPrefix(prefix),
                suCandidates = listOf("$prefix/bin/su", "/system/bin/su", "$prefix/bin/sudo"),
                stateDir = stateDir,
            )
    }
}
