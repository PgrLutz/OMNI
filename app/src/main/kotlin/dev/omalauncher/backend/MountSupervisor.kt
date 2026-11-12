package dev.omalauncher.backend

import java.io.File

/**
 * OMNI's single owner of the chroot mount lifecycle, mirroring the mechanics
 * of the user's own `kali-chroot` script (bind /dev, /dev/pts, proc, /sys —
 * presence-guarded, never double-mounted) but owned by the backend.
 *
 * - Mounts are kernel-global (proven: every `su` session shares the caller's
 *   mount namespace on this device), so ONE supervisor mount serves EVERY
 *   independent guest process.
 * - Presence in `/proc/mounts` decides: already mounted = never remount
 *   (whoever mounted it), missing = mount once. This makes app restarts,
 *   concurrent sessions and the user's manual chroot all safe.
 * - There is NO auto-unmount anywhere. Mounts persist until reboot or an
 *   explicit, user-confirmed cleanup. [MountLedger] records our claim
 *   (present + best-effort mountedByUs + boot id) for diagnostics and that
 *   explicit cleanup only.
 */
class MountSupervisor(
    private val config: ChrootConfig,
    private val esc: Escalator,
) {
    data class Report(
        val present: List<String>,
        val mountedByUs: List<String>,
        val log: List<String>,
    )

    /**
     * Ensures the four required mounts exist. Returns Ready + a report on
     * success, or [BackendState.MountFailed] naming what is still missing.
     */
    suspend fun ensureMounted(): Pair<BackendState, Report> {
        val before = MountPlan.parseMounts(readMounts())
        val missing = MountPlan.missingMounts(config.rootfs, before, config.runtimeDirGuest)
        val log = ArrayList<String>()
        if (missing.isNotEmpty()) {
            for (guestPath in missing) {
                val cmd =
                    if (guestPath == config.rootfs + config.runtimeDirGuest) {
                        // The launcher's runtime dir (Wayland socket, bridge
                        // FIFOs) bound into the guest's XDG_RUNTIME_DIR, so
                        // guest processes reach the same socket as before.
                        runtimeDirBindCommand(config)
                    } else {
                        // missing carries the full destination; mountCommand
                        // expects the guest path (it prefixes the rootfs itself).
                        mountCommand(config, guestPath.removePrefix(config.rootfs))
                    }
                val (code, out, err) = drain(esc, cmd, 60)
                log += listOfNotNull("$ $cmd", out.takeIf { it.isNotBlank() }, err.takeIf { it.isNotBlank() }, "exit $code")
            }
        }
        val after = MountPlan.parseMounts(readMounts())
        val stillMissing = MountPlan.missingMounts(config.rootfs, after, config.runtimeDirGuest)
        if (stillMissing.isNotEmpty()) {
            return BackendState.MountFailed(log + "still missing: $stillMissing") to Report(emptyList(), emptyList(), log)
        }
        // App-side access bind: the sandbox cannot stat Termux's private dirs,
        // so the real rootfs is mirrored into the app's own dir for every
        // app-side File access (guest files, .desktop entries, themes).
        val appDest = config.appRootfs
        if (appDest !in after) {
            val cmd = appRootfsBindCommand(config)
            val (code, out, err) = drain(esc, cmd, 60)
            log += listOfNotNull("$ $cmd", out.takeIf { it.isNotBlank() }, err.takeIf { it.isNotBlank() }, "exit $code")
            val afterBind = MountPlan.parseMounts(readMounts())
            if (appDest !in afterBind) {
                return BackendState.MountFailed(log + "still missing: $appDest (app rootfs bind)") to Report(emptyList(), emptyList(), log)
            }
        }
        val needed = MountPlan.neededMounts(config.rootfs, config.runtimeDirGuest)
        val present = needed.filter { it in after }
        val mountedByUs = needed.filter { it in after && it !in before }
        // The launcher's data dirs, bound into the guest at their proot paths
        // (widgets, style, home, themes, backgrounds, omarchy): guest tools
        // must see the launcher's real files, exactly like proot's dataBinds.
        val dataMounts = ArrayList<String>()
        var dataFailed = false
        // SELinux fs into the guest: guest tools (runcon) need it to change
        // domains, which is how GUI processes get the app's context for shm.
        val selinuxDst = config.rootfs + "/sys/fs/selinux"
        if (selinuxDst !in MountPlan.parseMounts(readMounts())) {
            val cmd = "mkdir -p ${shellQuote(selinuxDst)} && ${shellQuote(config.hostTools.mount)} --bind /sys/fs/selinux ${shellQuote(selinuxDst)}"
            val (code, out, err) = drain(esc, cmd, 60)
            log += listOfNotNull("$ $cmd", out.takeIf { it.isNotBlank() }, err.takeIf { it.isNotBlank() }, "exit $code")
            if (selinuxDst !in MountPlan.parseMounts(readMounts())) dataFailed = true
        }
        dataMounts += selinuxDst
        for ((guestPath, source) in config.launcherDataBinds) {
            val dst = config.rootfs + guestPath
            if (dst !in MountPlan.parseMounts(readMounts())) {
                val cmd = launcherDataBindCommand(config, source, dst)
                val (code, out, err) = drain(esc, cmd, 60)
                log += listOfNotNull("$ $cmd", out.takeIf { it.isNotBlank() }, err.takeIf { it.isNotBlank() }, "exit $code")
                if (dst !in MountPlan.parseMounts(readMounts())) dataFailed = true
            }
            dataMounts += dst
        }
        // The bind sources were mkdir'd as root, so the app uid can't write them; the app adds backgrounds
        // and themes into these dirs. Made writable every prepare, so a state left root-owned is fixed too.
        if (config.launcherDataBinds.isNotEmpty()) {
            val sources = config.launcherDataBinds.joinToString(" ") { shellQuote(it.second) }
            val (code, out, err) = drain(esc, "chmod 777 $sources 2>/dev/null; echo ok", 20)
            log += listOfNotNull("$ chmod 777 sources", out.takeIf { it.isNotBlank() }, err.takeIf { it.isNotBlank() }, "exit $code")
        }
        if (dataFailed) {
            return BackendState.MountFailed(log + "launcher data binds missing") to Report(emptyList(), emptyList(), log)
        }
        // Best-effort hosts sync, as the reference entry does; a failure is logged, not fatal.
        if (present.isNotEmpty()) {
            val hosts = "${shellQuote(config.hostTools.cat)} ${shellQuote(config.hostsSource)} > ${shellQuote("${config.rootfs}/etc/hosts")}"
            try {
                drain(esc, hosts, 15)
            } catch (e: Exception) {
                log += "hosts sync failed: ${e.message}"
            }
        }
        return BackendState.Ready to Report(present, mountedByUs, log)
    }

    private suspend fun readMounts(): String = drain(esc, "cat /proc/mounts", 15).second
}

/** The escalated command that mounts [guestPath] (one of /dev, /dev/pts, /proc, /sys) into the rootfs. */
internal fun mountCommand(
    config: ChrootConfig,
    guestPath: String,
): String {
    val dst = shellQuote(config.rootfs + guestPath)
    val mount = shellQuote(config.hostTools.mount)
    return if (guestPath == "/proc") {
        "$mount -t proc proc $dst"
    } else {
        "$mount --bind ${shellQuote(guestPath)} $dst"
    }
}

/**
 * The escalated command that binds the launcher's runtime dir (stateDir/xdg:
 * the Wayland socket and the bridge FIFOs) into the guest's XDG_RUNTIME_DIR,
 * creating the empty destination first (it does not exist in a fresh rootfs).
 */
internal fun runtimeDirBindCommand(config: ChrootConfig): String {
    val source = shellQuote(File(config.stateDir, "xdg").path)
    val dst = shellQuote(config.rootfs + config.runtimeDirGuest)
    return "mkdir -p $source $dst && ${shellQuote(config.hostTools.mount)} --bind $source $dst"
}

/**
 * The escalated command that mirrors the real rootfs into the app's own dir
 * (stateDir/chroot-rootfs), so the Android sandbox can read guest files; the
 * destination is created first.
 */
internal fun appRootfsBindCommand(config: ChrootConfig): String {
    val src = shellQuote(config.rootfs)
    val dst = shellQuote(config.appRootfs)
    return "mkdir -p $dst && ${shellQuote(config.hostTools.mount)} --bind $src $dst"
}

/** Binds one launcher data dir into the guest at its proot path (mirrors Proot.dataBinds). */
internal fun launcherDataBindCommand(config: ChrootConfig, source: String, dst: String): String {
    return "mkdir -p ${shellQuote(source)} ${shellQuote(dst)} && ${shellQuote(config.hostTools.mount)} --bind ${shellQuote(source)} ${shellQuote(dst)}"
}

/**
 * The escalated command that makes the guest home traversable by the launcher's
 * own uid: the guest root user keeps ownership, and the launcher only reads
 * (and writes files the root user owns through its own escalated helpers).
 */
internal fun homeVisibilityCommand(config: ChrootConfig): String {
    val dirs =
        listOf(
            "/root",
            "/root/.local", "/root/.local/share", "/root/.local/share/applications", "/root/.local/bin",
            "/root/.config", "/root/.config/omalauncher", "/root/.config/omarchy", "/root/.config/btop",
            "/root/.local/state", "/root/.local/state/omarchy",
            "/root/.local/state/omarchy/current", "/root/.local/state/omarchy/current/theme",
            "/root/.local/state/omalauncher", "/root/.cache",
        ).joinToString(" ") { shellQuote(config.rootfs + it) }
    return "mkdir -p $dirs && chmod 755 $dirs 2>/dev/null; echo ok"
}
