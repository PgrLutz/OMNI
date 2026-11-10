package dev.omalauncher.chroot

import dev.omalauncher.backend.Backend
import dev.omalauncher.backend.BackendState
import dev.omalauncher.backend.ChrootConfig
import dev.omalauncher.backend.CmdResult
import dev.omalauncher.backend.Escalator
import dev.omalauncher.backend.MountLedger
import dev.omalauncher.backend.MountLedgerFile
import dev.omalauncher.backend.MountSupervisor
import dev.omalauncher.backend.ProcessFactory
import dev.omalauncher.backend.ProcessRegistry
import dev.omalauncher.backend.SuEscalator
import dev.omalauncher.backend.drain
import dev.omalauncher.backend.drainProcessBlocking
import dev.omalauncher.backend.guestFileFor
import dev.omalauncher.backend.homeVisibilityCommand
import dev.omalauncher.backend.shellQuote
import dev.omalauncher.termux.TermuxEscalator
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.coroutines.coroutineContext

/**
 * Kali chroot backend. Mounts are shared and presence-guarded; each consumer
 * retains its own process, pipes, cwd and environment.
 *
 * This is not a sandbox: the guest runs as uid 0 with the escalator's
 * capabilities and SELinux domain. Host access depends on mounted paths,
 * capabilities and SELinux, never on chroot alone.
 */
class ChrootBackend(
    private val config: ChrootConfig,
    private val stateDir: File,
    private val context: android.content.Context,
) : Backend {

    private val _state = MutableStateFlow<BackendState>(BackendState.Checking)
    override val state: StateFlow<BackendState> = _state.asStateFlow()

    /** True when escalation runs through Termux (this app's own su is hidden by the ephemeral-root snapshot). */
    private var termuxMode = false

    override val guestHome: String get() = config.guestHome

    /** The rootfs directory the backend runs on, as the APP sees it: the real
     *  rootfs bind-mounted into the app's own dir (the sandbox cannot stat
     *  Termux's private paths). The escalated chroot/mount commands keep using
     *  the real [ChrootConfig.rootfs]. */
    val rootfs: File get() = File(config.appRootfs)

    /** The runtime config, for consumers that map guest paths or build raw processes. */
    internal val runtimeConfig: ChrootConfig get() = config

    /** A raw guest process (independent pipes, not registered); the caller manages its lifecycle. */
    fun processBuilder(script: String): ProcessBuilder {
        check(!termuxMode) { "persistent guest processes need the Termux session bridge (next migration group)" }
        return requireFactory().spawn(script, cwd = config.guestHome, token = "raw-${UUID.randomUUID()}")
    }

    /** Kills every registered guest process; used by the launcher's own cleanup paths. */
    fun killAll() {
        registry?.killAll()
    }

    /** Writes a guest file through the bridge (root); false when the bridge is down. */
    fun writeGuestFile(guestPath: String, content: ByteArray, executable: Boolean = false): Boolean =
        sessionBridge?.writeFile(guestPath, content, executable) ?: false

    /** Merges `K=V` pairs into every subsequent guest process's environment. */
    fun updateGuestEnv(env: List<String>) {
        for (pair in env) {
            val i = pair.indexOf('=')
            if (i > 0) config.env[pair.substring(0, i)] = pair.substring(i + 1)
        }
    }

    /** The first host tool the guest needs that is missing, via the escalated probe; null when all are present. */
    private suspend fun missingToolVia(esc: Escalator): Pair<String, String>? {
        val paths = config.hostTools.paths()
        val probe =
            "for t in ${paths.joinToString(" ") { shellQuote(it) }}; do test -x \"\$t\" || { echo \"\$t\"; exit 1; }; done; echo ok"
        val (code, out, _) = drain(esc, probe, 15)
        if (code == 0) return null
        val missing = out.lines().firstOrNull { it.isNotBlank() } ?: return null
        return File(missing).name to missing
    }

    private val ledgerFile = File(stateDir, "chroot-state.ledger")

    private var escalator: dev.omalauncher.backend.Escalator? = null
    private var factory: ProcessFactory? = null
    private var registry: ProcessRegistry? = null
    private var pool: ChrootShellPool? = null
    private var sessionBridge: SessionBridge? = null

    suspend fun prepare(): BackendState = withContext(Dispatchers.IO) {
        _state.value = BackendState.Checking
        ChrootLog.log("prepare start (termuxMode=$termuxMode)")
        val result = prepareInner()
        Log.i(TAG, "prepare -> ${result::class.simpleName}: ${(result as? BackendState.Failed)?.message}")
        ChrootLog.log("prepare -> ${result::class.simpleName}: ${(result as? BackendState.Failed)?.message}")
        _state.value = result
        result
    }

    private suspend fun prepareInner(): BackendState {
        _state.value = BackendState.Checking
        // The app sandbox cannot stat Termux's private dirs (the Kali rootfs
        // and the host tools live under /data/data/com.termux), so every
        // presence check is escalated through su, never an app-side File stat.
        // Try each su candidate in order until one actually answers as root:
        // the first is often a path that does not exist (Termux's bin/su), and
        // the sandbox hides su from un-allowlisted uids, so neither stat nor
        // the order can be trusted — the exec probe decides. A throw (exec
        // denied) must become a concrete state, never a silent stuck-Checking.
        val rootfsTest = "test -d ${shellQuote(config.rootfs)} && echo ok"
        // A transport only counts once it BOTH answers as root AND can read the
        // rootfs: this app's su sometimes answers uid=0 in a SELinux context
        // that cannot traverse Termux's private dirs (silent deny, no output).
        suspend fun directWorks(candidate: String): Boolean {
            val e = SuEscalator(candidate)
            val ok = runCatching { probeRoot(e) && drain(e, rootfsTest, 10).first == 0 }.getOrElse { false }
            if (!ok) ChrootLog.log("direct su $candidate rejected (root or rootfs probe)")
            return ok
        }
        suspend fun termuxWorks(): Boolean {
            val te = TermuxEscalator(context)
            val ok = runCatching { probeRoot(te) && drain(te, rootfsTest, 10).first == 0 }.getOrElse { false }
            if (!ok) ChrootLog.log("Termux transport rejected (root or rootfs probe)")
            return ok
        }
        val esc =
            config.suCandidates.filter { it.isNotBlank() }.firstOrNull { directWorks(it) }?.let(::SuEscalator)
                ?: if (termuxWorks()) TermuxEscalator(context)
                else return fail(
                    BackendState.PrivilegeUnavailable(
                        "no privilege escalation can read the rootfs: direct su and Termux RUN_COMMAND both failed (allow Termux:API in Settings → Termux first)",
                    ),
                )
        termuxMode = esc is TermuxEscalator
        escalator = esc
        ChrootLog.log("transport: ${if (termuxMode) "Termux RUN_COMMAND" else "direct su"} (rootfs readable)")
        val (rootfsOk, rootfsOut, rootfsErr) = drain(esc, rootfsTest, 10)
        ChrootLog.log("rootfs probe: code=$rootfsOk out=${rootfsOut.trim()} err=${rootfsErr.trim()}")
        if (rootfsOk != 0) {
            // Unreachable in practice (the transport was chosen by this probe),
            // kept for the detail in the reported state.
            val detail = listOfNotNull(rootfsOut, rootfsErr).filter { it.isNotBlank() }.joinToString(" | ").trim()
            return fail(BackendState.RootfsMissing(config.rootfs, detail))
        }
        val missingTool = runCatching { missingToolVia(esc) }.getOrElse { return fail(BackendState.PrivilegeUnavailable("escalation exec failed: ${it.message ?: it}")) }
        if (missingTool != null) return fail(BackendState.HostToolMissing(missingTool.first, missingTool.second))

        val (mountState, report) =
            runCatching { MountSupervisor(config, esc).ensureMounted() }
                .getOrElse { return fail(BackendState.MountFailed(listOf("mount escalation failed: ${it.message ?: it}"))) }
        if (mountState is BackendState.Failed) {
            report.log.forEach { ChrootLog.log("mount: $it") }
            return mountState
        }
        report.log.forEach { ChrootLog.log("mount: $it") }
        ChrootLog.log("mounts present=${report.present} byUs=${report.mountedByUs}")
        // Best-effort: make the guest home traversable by the launcher's own
        // uid (it reads .desktop entries, themes and agent state through the
        // app-visible rootfs bind). Never fatal.
        runCatching { drain(esc, homeVisibilityCommand(config), 20) }

        val tokens =
            if (termuxMode) emptyList() // no pooled shells over RUN_COMMAND
            else List(config.shellCount) { "omarchy-${UUID.randomUUID()}" }
        val factory = ProcessFactory(config, esc)
        this@ChrootBackend.factory = factory
        if (!termuxMode) {
            val registry = ProcessRegistry(esc, config.hostTools.ps)
            this@ChrootBackend.registry = registry
            pool = ChrootShellPool(tokens.map { ChrootShell(config, factory, registry, it) })
        } else {
            // The persistent session: RUN_COMMAND in the background runs the
            // guest bridge shell; commands flow over the xdg FIFOs.
            val bridge = SessionBridge(stateDir, factory, context)
            val started = runCatching { bridge.start() }.getOrElse { false }
            ChrootLog.log("session bridge started=$started")
            if (started) sessionBridge = bridge
        }

        MountLedgerFile.write(
            ledgerFile,
            MountLedger(config.rootfs, readBootId(esc), System.currentTimeMillis(), report.present, report.mountedByUs, tokens),
        )
        ChrootLog.log("ledger written, pools=${if (termuxMode) 0 else config.shellCount}")
        _state.value = BackendState.Ready
        return BackendState.Ready
    }

    override suspend fun run(
        script: String,
        timeoutSeconds: Int,
        onLine: (String) -> Unit,
    ): CmdResult = withContext(Dispatchers.IO) {
        if (termuxMode) {
            // The persistent session bridge when it is up; otherwise a fresh
            // one-shot through RUN_COMMAND.
            val bridge = sessionBridge
            if (bridge != null) {
                val result = bridge.send(script, timeoutSeconds)
                result.stdout.lineSequence().forEach(onLine)
                return@withContext result
            }
            // One-shot through Termux: no local process, so no marker protocol.
            val f = requireFactory()
            val esc = escalator ?: error("not prepared")
            val cmd = f.commandString(f.guestLine("bash --noprofile --norc -c ${shellQuote(script)}", config.guestHome))
            val (code, out, err) = esc.runToCompletion(cmd, timeoutSeconds.toLong())
            out.lines().forEach(onLine)
            return@withContext CmdResult(out, err, code)
        }
        val f = requireFactory()
        val r = requireRegistry()
        val token = "run-${UUID.randomUUID()}"
        val process = f.spawnBash(script, cwd = config.guestHome, token = token).start()
        val entry = r.register(process, token)
        try {
            awaitDrain(process, timeoutSeconds.toLong(), onLine)
        } catch (e: Throwable) {
            runCatching { r.kill(entry) }
            throw e
        }
    }

    /**
     * Drains the process on a daemon thread while polling coroutine
     * cancellation. Cancellation exits this loop; run() then kills the
     * registered guest process tree and rethrows.
     */
    private suspend fun awaitDrain(
        process: Process,
        timeoutSeconds: Long,
        onLine: (String) -> Unit,
    ): CmdResult {
        var result: CmdResult? = null
        var failure: Throwable? = null
        val thread = Thread {
            runCatching { drainProcessBlocking(process, timeoutSeconds, onLine) }
                .onSuccess { result = it }
                .onFailure { failure = it }
        }.apply { isDaemon = true; start() }
        while (thread.isAlive) {
            coroutineContext.ensureActive()
            thread.join(50)
        }
        failure?.let { throw it }
        return result ?: error("process drain completed without a result")
    }

    override suspend fun cmd(
        command: String,
        timeoutSeconds: Int,
        retry: Boolean,
    ): CmdResult {
        if (termuxMode) {
            // The persistent session when connected; otherwise a fresh one-shot.
            val bridge = sessionBridge
            if (bridge != null) return bridge.send(command, timeoutSeconds)
            return run(command, timeoutSeconds) { }
        }
        val p = pool ?: error("backend not prepared: ${_state.value}")
        return p.run(command, timeoutSeconds, retry)
    }

    override suspend fun stream(
        command: String,
        onLine: (String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val f = requireFactory()
        val r = requireRegistry()
        val token = "stream-${UUID.randomUUID()}"
        val process = f.spawnBash(command, cwd = config.guestHome, token = token).start()
        val entry = r.register(process, token)
        try {
            process.inputStream.bufferedReader().useLines { lines -> lines.forEach(onLine) }
            process.waitFor()
        } finally {
            runCatching { r.kill(entry) }
        }
        Unit
    }

    override fun spawn(script: String): Process {
        check(!termuxMode) { "persistent guest processes need the Termux session bridge (next migration group)" }
        val f = requireFactory()
        val r = requireRegistry()
        val token = "agent-${UUID.randomUUID()}"
        val process = f.spawn(script, cwd = config.guestHome, token = token).start()
        r.register(process, token)
        return process
    }

    override fun guestFile(guestPath: String): File? = guestFileFor(config, guestPath)

    override fun close() {
        synchronized(processLock) {
            pool?.close()
            pool = null
            val r = registry
            registry = null
            if (r != null) runCatching { r.killAll() }
            runCatching { sessionBridge?.close() }
            sessionBridge = null
        }
        // Keep the last state: resetting to Checking here left the UI on a
        // "Preparing" that nothing would ever finish. prepare() sets Checking
        // itself when it starts.
    }

    private val processLock = Any()

    private fun requireFactory() = factory ?: error("backend not prepared: ${_state.value}")

    private fun requireRegistry() = registry ?: error("backend not prepared: ${_state.value}")

    private fun fail(state: BackendState.Failed): BackendState {
        _state.value = state
        return state
    }

    private suspend fun probeRoot(esc: dev.omalauncher.backend.Escalator): Boolean {
        val (code, out, _) = drain(esc, "id -Z; id", 10)
        return code == 0 && out.contains("uid=0")
    }

    private suspend fun readBootId(esc: dev.omalauncher.backend.Escalator): String {
        val (_, out, _) = drain(esc, "cat /proc/sys/kernel/random/boot_id", 10)
        return out.lines().firstOrNull { it.isNotBlank() } ?: "unknown"
    }

    private companion object {
        const val TAG = "chroot"
    }
}
