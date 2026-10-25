package com.mobileagent.execution.shell

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.mobileagent.core.model.JsonCodec
import com.mobileagent.core.model.ShellResult
import com.mobileagent.execution.shizuku.ShizukuShellService
import rikka.shizuku.Shizuku
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class ShizukuStatus(
    val installed: Boolean,
    val running: Boolean,
    val permissionGranted: Boolean,
    val connected: Boolean,
    val message: String,
)

/**
 * Client for the Shizuku user service. The binder is a Messenger; every call re-checks that
 * Shizuku is running and that the permission is granted, so a stale connection never looks healthy.
 */
class ShizukuShellExecutor(private val context: Context) {

    private val bindMutex = Mutex()
    private val execMutex = Mutex()
    private val requestIds = AtomicInteger(0)
    private val responses = ConcurrentHashMap<Int, CompletableDeferred<String>>()

    @Volatile
    private var service: Messenger? = null

    private val responseMessenger = Messenger(
        Handler(Looper.getMainLooper()) { message ->
            if (message.what == ShizukuShellService.MSG_RESULT) {
                val text = message.data?.getString("result").orEmpty()
                responses.remove(message.arg1)?.complete(text)
            }
            true
        },
    )

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = binder?.let { Messenger(it) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    fun isInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    }.getOrDefault(false)

    fun isRunning(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun hasPermission(): Boolean = runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun status(): ShizukuStatus {
        val installed = isInstalled()
        val running = isRunning()
        val permission = hasPermission()
        val connected = service != null
        val message = when {
            !installed -> "Shizuku is not installed. Install it, pair wireless debugging, then start the service."
            !running -> "Shizuku is installed but not running. Open Shizuku and start it."
            !permission -> "Shizuku is running. Grant Mobile Agent permission in Shizuku > Authorized apps."
            connected -> "Connected with the ADB shell UID."
            else -> "Permission granted; the shell service is not bound yet."
        }
        return ShizukuStatus(installed, running, permission, connected, message)
    }

    /** Binds the user service once and waits for the messenger. */
    suspend fun ensureService(timeoutMs: Long = 15_000L): Result<Messenger> = bindMutex.withLock {
        service?.let { return Result.success(it) }
        if (!isRunning()) return Result.failure(IllegalStateException("Shizuku service is not running."))
        if (!hasPermission()) return Result.failure(SecurityException("Mobile Agent is not authorized in Shizuku."))

        val bindResult = runCatching { Shizuku.bindUserService(createArgs(), connection) }
        bindResult.exceptionOrNull()?.let {
            return Result.failure(IllegalStateException("Shizuku refused the user service: ${it.message}", it))
        }

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            service?.let { return Result.success(it) }
            delay(100)
        }
        return Result.failure(
            IllegalStateException("Timed out binding the Shizuku shell service. Reopen Shizuku, then retry."),
        )
    }

    suspend fun execute(command: String, timeoutMs: Long, maxOutputChars: Int = 200_000): ShellResult {
        val executionId = UUID.randomUUID().toString()
        val startedAt = System.currentTimeMillis()
        val shell = ensureService().getOrElse { error ->
            return ShellResult(
                executionId = executionId,
                executor = "shizuku",
                command = command,
                exitCode = -1,
                stdout = "",
                stderr = "",
                startedAt = startedAt,
                endedAt = System.currentTimeMillis(),
                cancelled = false,
                timedOut = false,
                error = error.message ?: "Shizuku unavailable",
            )
        }

        return execMutex.withLock {
            val requestId = requestIds.incrementAndGet()
            val deferred = CompletableDeferred<String>()
            responses[requestId] = deferred
            val boundedTimeout = timeoutMs.coerceIn(1_000L, 300_000L)
            try {
                val message = Message.obtain(null, ShizukuShellService.MSG_EXEC).apply {
                    arg1 = requestId
                    data = Bundle().apply {
                        putString("command", command)
                        putInt("timeoutMs", boundedTimeout.toInt())
                    }
                    replyTo = responseMessenger
                }
                shell.send(message)
                val raw = withTimeoutOrNull(boundedTimeout + 10_000L) { deferred.await() }
                    ?: return@withLock ShellResult(
                        executionId = executionId,
                        executor = "shizuku",
                        command = command,
                        exitCode = -1,
                        stdout = "",
                        stderr = "",
                        startedAt = startedAt,
                        endedAt = System.currentTimeMillis(),
                        cancelled = false,
                        timedOut = true,
                        error = "The Shizuku shell service did not answer within ${boundedTimeout + 10_000} ms.",
                    )
                parseResult(executionId, command, startedAt, raw)
            } catch (t: Throwable) {
                service = null
                ShellResult(
                    executionId = executionId,
                    executor = "shizuku",
                    command = command,
                    exitCode = -1,
                    stdout = "",
                    stderr = "",
                    startedAt = startedAt,
                    endedAt = System.currentTimeMillis(),
                    cancelled = false,
                    timedOut = false,
                    error = "Shizuku call failed: ${t.message ?: t::class.java.simpleName}. Reopen Shizuku, then retry.",
                )
            } finally {
                responses.remove(requestId)
            }
        }
    }

    private fun parseResult(executionId: String, command: String, startedAt: Long, raw: String): ShellResult {
        val obj = runCatching { JsonCodec.json.parseToJsonElement(raw) as JsonObject }.getOrNull()
            ?: return ShellResult(
                executionId, "shizuku", command, -1, "", raw.take(2_000),
                startedAt, System.currentTimeMillis(), false, false,
                error = "Unreadable response from the Shizuku service",
            )
        val stdout = obj["stdout"]?.jsonPrimitive?.content.orEmpty()
        val stderr = obj["stderr"]?.jsonPrimitive?.content.orEmpty()
        return ShellResult(
            executionId = executionId,
            executor = "shizuku",
            command = command,
            exitCode = obj["exitCode"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1,
            stdout = stdout,
            stderr = stderr,
            startedAt = startedAt,
            endedAt = System.currentTimeMillis(),
            cancelled = false,
            timedOut = obj["timedOut"]?.jsonPrimitive?.content == "true",
            truncated = obj["truncated"]?.jsonPrimitive?.content == "true",
            error = obj["error"]?.jsonPrimitive?.content,
        )
    }

    fun release() {
        val messenger = service
        if (messenger != null) {
            runCatching { messenger.send(Message.obtain(null, ShizukuShellService.MSG_DESTROY)) }
        }
        runCatching { Shizuku.unbindUserService(createArgs(), connection, true) }
        service = null
    }

    private fun createArgs(): Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(ComponentName(context.packageName, SHIZUKU_SERVICE_CLASS))
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(false)
            .version(SERVICE_VERSION)

    companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val SHIZUKU_SERVICE_CLASS = "com.mobileagent.execution.shizuku.ShizukuShellService"
        private const val SERVICE_VERSION = 1
    }
}
