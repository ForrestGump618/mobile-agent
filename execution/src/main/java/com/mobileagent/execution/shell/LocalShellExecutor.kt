package com.mobileagent.execution.shell

import android.content.Context
import com.mobileagent.core.model.ShellResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Executes commands as the normal application UID in the app sandbox. There is no privilege
 * escalation here: the shell has exactly the permissions of the app itself.
 */
class LocalShellExecutor(private val context: Context) {

    private val shellPath: String = if (java.io.File("/system/bin/sh").exists()) "/system/bin/sh" else "sh"

    suspend fun execute(
        command: String,
        timeoutMs: Long,
        maxOutputChars: Int = MAX_OUTPUT_CHARS,
    ): ShellResult = withContext(Dispatchers.IO) {
        val executionId = UUID.randomUUID().toString()
        val startedAt = System.currentTimeMillis()
        var process: Process? = null
        var timedOut = false

        try {
            val proc = ProcessBuilder(shellPath, "-c", command)
                .directory(context.filesDir)
                .redirectErrorStream(false)
                .apply {
                    environment()["PATH"] = "/sbin:/system/sbin:/system/bin:/system/xbin"
                    environment()["HOME"] = context.filesDir.absolutePath
                }
                .start()
            process = proc

            val outDeferred = async(Dispatchers.IO) { drain(proc.inputStream, maxOutputChars) }
            val errDeferred = async(Dispatchers.IO) { drain(proc.errorStream, maxOutputChars) }

            val finished = runInterruptible(Dispatchers.IO) {
                proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            }
            if (!finished) {
                timedOut = true
                proc.destroy()
                if (!proc.waitFor(1, TimeUnit.SECONDS)) proc.destroyForcibly()
            }

            val out = outDeferred.await()
            val err = errDeferred.await()
            val exitCode = if (timedOut) -1 else runCatching { proc.exitValue() }.getOrDefault(-1)

            ShellResult(
                executionId = executionId,
                executor = "local",
                command = command,
                exitCode = exitCode,
                stdout = out.first,
                stderr = err.first,
                startedAt = startedAt,
                endedAt = System.currentTimeMillis(),
                cancelled = false,
                timedOut = timedOut,
                truncated = out.second || err.second,
                error = if (timedOut) "Command timed out after $timeoutMs ms" else null,
            )
        } catch (c: CancellationException) {
            process?.destroyForcibly()
            throw c
        } catch (t: Throwable) {
            ShellResult(
                executionId = executionId,
                executor = "local",
                command = command,
                exitCode = -1,
                stdout = "",
                stderr = "",
                startedAt = startedAt,
                endedAt = System.currentTimeMillis(),
                cancelled = false,
                timedOut = timedOut,
                error = t.message ?: t::class.java.simpleName,
            )
        } finally {
            runCatching { process?.destroyForcibly() }
        }
    }

    fun isAvailable(): Boolean = java.io.File("/system/bin/sh").exists()

    private fun drain(input: InputStream, limit: Int): Pair<String, Boolean> {
        val builder = StringBuilder()
        var truncated = false
        val buffer = ByteArray(8_192)
        try {
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                if (builder.length < limit) {
                    val remaining = limit - builder.length
                    builder.append(String(buffer, 0, minOf(read, remaining), Charsets.UTF_8))
                    if (read > remaining) truncated = true
                } else {
                    truncated = true
                }
            }
        } catch (_: Throwable) {
            // stream closed after process death
        }
        return builder.toString() to truncated
    }

    companion object {
        const val MAX_OUTPUT_CHARS = 200_000
    }
}
