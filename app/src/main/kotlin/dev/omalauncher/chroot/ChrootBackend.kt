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
    stateDir: File,
) : Backend {

    private val _state = MutableStateFlow<BackendState>(BackendState.Checking)
    override val state: StateFlow<BackendState> = _state.asStateFlow()

    override val guestHome: String get() = config.guestHome

    /** The rootfs directory the backend runs on, as the APP sees it: the real
     *  rootfs bind-mounted into the app's own dir (the sandbox cannot stat
     *  Termux's private paths). The escalated chroot/mount commands keep using
     *  the real [ChrootConfig.rootfs]. */
    val rootfs: File get() = File(config.appRootfs)

    /** The runtime config, for consumers that map guest paths or build raw processes. */
    internal val runtimeConfig: ChrootConfig get() = config

    /** A raw guest process (independent pipes, not registered); the caller manages its lifecycle. */
    fun processBuilder(script: String): ProcessBuilder =
        requireFactory().spawn(script, cwd = config.guestHome, token = "raw-${UUID.randomUUID()}")

    /** Kills every registered guest process; used by the launcher's own cleanup paths. */
    fun killAll() {
        registry?.killAll()
    }

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

    suspend fun prepare(): BackendState = withContext(Dispatchers.IO) {
        _state.value = BackendState.Checking
        // The app sandbox cannot stat Termux's private dirs (the Kali rootfs
        // and the host tools live under /data/data/com.termux), so every
        // presence check is escalated through su, never an app-side File stat.
        val su = config.suCandidates.firstOrNull { it.isNotBlank() }
            ?: return@withContext fail(BackendState.PrivilegeUnavailable("no su candidate in ${config.suCandidates}"))
        val esc = SuEscalator(su)
        // Every escalated call may throw (su exec denied by the sandbox when
        // the current install's uid is not on the KernelSU allowlist); a throw
        // must become a concrete state, never a silent stuck-at-Checking.
        val rootOk = runCatching { probeRoot(esc) }.getOrElse { return@withContext fail(BackendState.PrivilegeUnavailable("su exec failed: ${it.message ?: it}")) }
        if (!rootOk) return@withContext fail(BackendState.PrivilegeUnavailable("$su did not answer as root"))
        val (rootfsOk, rootfsOut, rootfsErr) =
            runCatching { drain(esc, "test -d ${shellQuote(config.rootfs)} && echo ok", 10) }
                .getOrElse { return@withContext fail(BackendState.PrivilegeUnavailable("su exec failed: ${it.message ?: it}")) }
        if (rootfsOk != 0) {
            // The probe detail (Permission denied = SELinux on Termux's private
            // dirs; No such file = wrong path) decides the fix.
            val detail = listOfNotNull(rootfsOut, rootfsErr).filter { it.isNotBlank() }.joinToString(" | ").trim()
            return@withContext fail(BackendState.RootfsMissing(config.rootfs, detail))
        }
        val missingTool = runCatching { missingToolVia(esc) }.getOrElse { return@withContext fail(BackendState.PrivilegeUnavailable("su exec failed: ${it.message ?: it}")) }
        if (missingTool != null) return@withContext fail(BackendState.HostToolMissing(missingTool.first, missingTool.second))
        escalator = esc

        val (mountState, report) =
            runCatching { MountSupervisor(config, esc).ensureMounted() }
                .getOrElse { return@withContext fail(BackendState.MountFailed(listOf("mount escalation failed: ${it.message ?: it}"))) }
        if (mountState is BackendState.Failed) return@withContext mountState
        // Best-effort: make the guest home traversable by the launcher's own
        // uid (it reads .desktop entries, themes and agent state through the
        // app-visible rootfs bind). Never fatal.
        runCatching { drain(esc, homeVisibilityCommand(config), 20) }

        val tokens = List(config.shellCount) { "omarchy-${UUID.randomUUID()}" }
        val factory = ProcessFactory(config, esc)
        val registry = ProcessRegistry(esc, config.hostTools.ps)
        this@ChrootBackend.factory = factory
        this@ChrootBackend.registry = registry
        pool = ChrootShellPool(tokens.map { ChrootShell(config, factory, registry, it) })

        MountLedgerFile.write(
            ledgerFile,
            MountLedger(config.rootfs, readBootId(esc), System.currentTimeMillis(), report.present, report.mountedByUs, tokens),
        )
        _state.value = BackendState.Ready
        BackendState.Ready
    }

    override suspend fun run(
        script: String,
        timeoutSeconds: Int,
        onLine: (String) -> Unit,
    ): CmdResult = withContext(Dispatchers.IO) {
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
        }
        _state.value = BackendState.Checking
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
}
