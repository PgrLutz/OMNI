package dev.omalauncher.termux

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Executes bounded, non-interactive commands through Termux's documented RUN_COMMAND API.
 *
 * This bridge deliberately does not expose Termux's private files to OMNI. Commands run with
 * Termux's permissions, so callers must only pass commands the user has explicitly approved.
 */
object TermuxBackend {
    const val PACKAGE = "com.termux"
    const val RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"

    private const val SERVICE = "com.termux.app.RunCommandService"
    private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
    private const val ACTION_RESULT = "dev.omalauncher.termux.COMMAND_RESULT"
    private const val COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
    private const val ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    private const val WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    private const val BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    private const val PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"

    private const val RESULT_BUNDLE = "result"
    private const val STDOUT = "stdout"
    private const val STDERR = "stderr"
    private const val EXIT_CODE = "exitCode"
    private const val ERROR = "err"
    private const val ERROR_MESSAGE = "errmsg"
    private const val STDOUT_LENGTH = "stdout_original_length"
    private const val STDERR_LENGTH = "stderr_original_length"
    private const val REQUEST_ID = "request_id"

    private const val PREFIX = "/data/data/com.termux/files/usr"
    private const val MAX_COMMAND_BYTES = 96 * 1024
    private const val RESULT_GRACE_MS = 15_000L

    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<Bundle>>()

    data class Result(
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
        val stdoutTruncated: Boolean,
        val stderrTruncated: Boolean,
    ) {
        val timedOut: Boolean get() = exitCode == 124 || exitCode == 137
    }

    fun isInstalled(context: Context): Boolean =
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(PACKAGE, 0)
        }.isSuccess

    fun hasRunCommandPermission(context: Context): Boolean =
        context.checkSelfPermission(RUN_COMMAND_PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun isReady(context: Context): Boolean =
        isInstalled(context) &&
            hasRunCommandPermission(context) &&
            runCatching {
                context.packageManager.getServiceInfo(ComponentName(PACKAGE, SERVICE), 0)
            }.isSuccess

    /**
     * Run one command in Termux. A null workdir leaves Termux's normal home directory in effect.
     * The shell-level timeout stops the remote process even if the caller stops waiting.
     */
    suspend fun run(
        context: Context,
        command: String,
        timeoutSeconds: Int = 10,
        workdir: String? = null,
    ): Result {
        require(command.isNotBlank()) { "Command is empty" }
        require('\u0000' !in command) { "Command contains a NUL byte" }
        require(command.toByteArray(Charsets.UTF_8).size <= MAX_COMMAND_BYTES) {
            "Command exceeds the 96 KiB limit"
        }
        require(workdir == null || (workdir.isNotBlank() && '\u0000' !in workdir)) {
            "Invalid working directory"
        }

        if (!isInstalled(context)) throw IOException("Termux is not installed")
        if (!hasRunCommandPermission(context)) {
            throw SecurityException("Termux RUN_COMMAND permission has not been granted")
        }
        if (!runCatching {
                context.packageManager.getServiceInfo(ComponentName(PACKAGE, SERVICE), 0)
            }.isSuccess
        ) {
            throw IOException("Termux RUN_COMMAND service is unavailable; update Termux")
        }

        val seconds = timeoutSeconds.coerceIn(1, 3600)
        val id = nextId.getAndUpdate { if (it == Int.MAX_VALUE) 1 else it + 1 }
        val response = CompletableDeferred<Bundle>()
        check(pending.putIfAbsent(id, response) == null) { "Duplicate Termux request id" }

        val callbackIntent = Intent(context, TermuxCommandResultReceiver::class.java)
            .setAction(ACTION_RESULT)
            .putExtra(REQUEST_ID, id)
        val flags = PendingIntent.FLAG_ONE_SHOT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val callback = PendingIntent.getBroadcast(context, id, callbackIntent, flags)

        return try {
            val shellCommand =
                "exec timeout --signal=TERM --kill-after=2s " + seconds + "s " +
                    PREFIX + "/bin/bash -lc " + shellQuote(command)
            val request = Intent(ACTION_RUN_COMMAND)
                .setComponent(ComponentName(PACKAGE, SERVICE))
                .putExtra(COMMAND_PATH, PREFIX + "/bin/bash")
                .putExtra(ARGUMENTS, arrayOf("-lc", shellCommand))
                .putExtra(BACKGROUND, true)
                .putExtra(PENDING_INTENT, callback)

            if (workdir != null) request.putExtra(WORKDIR, workdir)
            context.startService(request)

            val responseIntent = withTimeout(seconds * 1000L + RESULT_GRACE_MS) { response.await() }
            // The receiver already unwraps the nested result bundle before completing this deferred.
            val result = responseIntent
            val errorCode = result.getInt(ERROR, Activity.RESULT_OK)
            if (errorCode != Activity.RESULT_OK) {
                throw IOException(
                    result.getString(ERROR_MESSAGE)?.takeIf { it.isNotBlank() }
                        ?: "Termux could not start the command (error $errorCode)",
                )
            }

            val stdout = result.getString(STDOUT).orEmpty()
            val stderr = result.getString(STDERR).orEmpty()
            val stdoutBytes = stdout.toByteArray(Charsets.UTF_8).size
            val stderrBytes = stderr.toByteArray(Charsets.UTF_8).size
            Result(
                stdout = stdout,
                stderr = stderr,
                exitCode = result.getIntCompat(EXIT_CODE, -1),
                stdoutTruncated = result.getIntCompat(STDOUT_LENGTH, stdoutBytes) > stdoutBytes,
                stderrTruncated = result.getIntCompat(STDERR_LENGTH, stderrBytes) > stderrBytes,
            )
        } finally {
            pending.remove(id)
            callback.cancel()
        }
    }

    internal fun deliver(id: Int, result: Bundle) {
        pending[id]?.complete(result)
    }

    /** Termux:API has sent some fields as Strings on recent versions; read either. */
    private fun Bundle.getIntCompat(key: String, default: Int): Int =
        when (val v = get(key)) {
            null -> default
            is Int -> v
            is String -> v.toIntOrNull() ?: default
            else -> default
        }
    internal fun fail(id: Int, error: Throwable) {
        pending[id]?.completeExceptionally(error)
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    internal const val RESULT_BUNDLE_KEY = RESULT_BUNDLE
    internal const val REQUEST_ID_KEY = REQUEST_ID
}

/** Receives the one-shot result PendingIntent sent back by Termux. */
class TermuxCommandResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val id = intent.getIntExtra(TermuxBackend.REQUEST_ID_KEY, -1)
        if (id < 1) return
        val result = intent.getBundleExtra(TermuxBackend.RESULT_BUNDLE_KEY)
        if (result == null) {
            TermuxBackend.fail(id, IOException("Termux returned no result bundle"))
        } else {
            TermuxBackend.deliver(id, result)
        }
    }
}
