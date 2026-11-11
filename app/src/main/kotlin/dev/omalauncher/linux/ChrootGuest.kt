package dev.omalauncher.linux

import dev.omalauncher.backend.guestFileFor
import dev.omalauncher.chroot.ChrootBackend
import dev.omalauncher.chroot.chrootGuestFs
import dev.omalauncher.upgrade.Managed
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * The ACTIVE guest: the Kali chroot backend. Paths live inside the Kali
 * rootfs (home, applications, /tmp, the FIFO dir), exec goes through the
 * backend's marker-bounded one-shots and raw process builders, and the GPU
 * environment is pushed into every subsequent guest process. The Kali rootfs
 * itself is never deleted by the launcher.
 */
class ChrootGuest(
    private val backend: ChrootBackend,
    private val launcherFiles: File,
) : LinuxGuest {
    override val filesDir: File get() = launcherFiles
    override val rootfs: File get() = backend.rootfs
    override val home: File get() = File(rootfs, "root")
    override val applications: File get() = File(home, ".local/share/applications")
    override val launcherConfig: File get() = File(home, ".config/omalauncher")
    override val tmpDir: File get() = File(rootfs, "tmp")
    // The host side of the guest's XDG_RUNTIME_DIR: the same filesDir/xdg the
    // legacy guest used; the backend binds it over the guest path at prepare.
    override val runtimeDir: File get() = File(backend.runtimeConfig.stateDir, "xdg")
    override val guestRuntimeDir: String get() = backend.runtimeConfig.runtimeDirGuest
    override val guestHome: String get() = backend.runtimeConfig.guestHome
    override val keymap: File get() = File(launcherFiles, "keymap.xkb")
    override val guestFs: GuestFs by lazy { chrootGuestFs(rootfs) }

    override fun guestFile(guestPath: String): File? = guestFileFor(backend.runtimeConfig, guestPath)

    override fun guestPath(host: File): String? {
        val root = rootfs.canonicalFile
        val real = host.canonicalFile
        return if (real.startsWith(root)) "/" + real.relativeTo(root).path else null
    }

    override fun hostFile(guestPath: String): File = File(rootfs, guestPath.trimStart('/'))

    /** The Termux transport has no local processes: streams go through the bridge. */
    val transportIsTermux: Boolean get() = backend.transportIsTermux

    override fun run(script: String, onLine: (String) -> Unit): Int =
        run(script, onLine, timeoutSeconds = 300)

    fun run(script: String, onLine: (String) -> Unit, timeoutSeconds: Int): Int {
        // One-shot scripts (font lists, checks, installs); the backend bounds them.
        val result = runBlocking { backend.run(script, timeoutSeconds = timeoutSeconds, onLine = onLine) }
        return result.exitCode
    }

    /** Writes a guest file as root through the bridge (the app can't write the rootfs). */
    fun writeGuestFile(guestPath: String, content: String, executable: Boolean = false): Boolean =
        backend.writeGuestFile(guestPath, content.toByteArray(), executable)

    /** The same as [run] but keeps stderr and the exit code, for widget failure reporting. */
    fun runResult(script: String, timeoutSeconds: Int, onLine: (String) -> Unit): dev.omalauncher.backend.CmdResult =
        runBlocking { backend.run(script, timeoutSeconds = timeoutSeconds, onLine = onLine) }

    /** App-side view of a stream's output/pid files (the guest sees the bound XDG dir). */
    fun streamOutput(tag: String): File = File(runtimeDir, tag)

    /**
     * Starts a long-running guest process DETACHED through the bridge, writing
     * to <tag>.out in the bound runtime dir (pid recorded in <tag>.pid). The
     * app tails the file; the pid tells when the writer is gone.
     */
    fun streamBackground(command: String, tag: String): Boolean {
        val bridge = backend.sessionBridge ?: return false
        val guestOut = "${guestRuntimeDir}/$tag.out"
        val guestPid = "${guestRuntimeDir}/$tag.pid"
        val ok =
            runCatching {
                bridge.send(
                    "nohup bash -c ${shellQuote(command)} > $guestOut 2>&1 & echo \$! > $guestPid",
                    timeoutSeconds = 10,
                ).exitCode == 0
            }.getOrElse { false }
        dev.omalauncher.chroot.ChrootLog.log("stream start ok=$ok tag=$tag")
        return ok
    }

    /** Kills the detached stream writer by the recorded pid. */
    fun stopStream(tag: String) {
        val bridge = backend.sessionBridge ?: return
        val pid = runCatching { streamOutput("$tag.pid").readText().trim() }.getOrNull()
        if (!pid.isNullOrEmpty()) {
            runCatching { bridge.send("kill $pid 2>/dev/null; true", timeoutSeconds = 5) }
            dev.omalauncher.chroot.ChrootLog.log("stream stop tag=$tag pid=$pid")
        }
    }

    override fun process(script: String): ProcessBuilder = backend.processBuilder(script)

    /** The Kali guest needs no proot bwrap shim. */
    override fun managed(): List<Managed> = emptyList()

    override fun killAll() = backend.killAll()

    override var graphicsEnv: List<String> = SOFTWARE_ENV
        set(value) {
            field = value
            backend.updateGuestEnv(value)
        }
}
