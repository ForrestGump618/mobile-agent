package com.mobileagent.execution.shizuku

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Runs inside the Shizuku user service process with the ADB shell UID. A Messenger binder is used
 * instead of AIDL to keep the protocol self-contained and versionable. Every policy decision has
 * already been made by the app process before a command reaches this service.
 */
class ShizukuShellService : Service() {

    private val thread = HandlerThread("mobile-agent-shizuku-shell").apply { start() }
    private lateinit var messenger: Messenger

    override fun onCreate() {
        super.onCreate()
        val callback = Handler.Callback { message ->
            when (message.what) {
                MSG_EXEC -> {
                    val command = message.data?.getString("command").orEmpty()
                    val timeout = message.data?.getInt("timeoutMs", 30_000) ?: 30_000
                    val replyTo = message.replyTo
                    val result = runCommand(command, timeout)
                    val reply = Message.obtain(null, MSG_RESULT).apply {
                        arg1 = message.arg1
                        data = Bundle().apply { putString("result", result) }
                    }
                    runCatching { replyTo?.send(reply) }
                    true
                }

                MSG_DESTROY -> {
                    stopSelf()
                    true
                }

                else -> false
            }
        }
        messenger = Messenger(Handler(thread.looper, callback))
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        thread.quitSafely()
        super.onDestroy()
    }

    private fun runCommand(command: String, timeoutMs: Int): String {
        val started = System.currentTimeMillis()
        var process: Process? = null
        return try {
            val proc = ProcessBuilder("/system/bin/sh", "-c", command)
                .redirectErrorStream(false)
                .apply { environment()["PATH"] = "/sbin:/system/sbin:/system/bin:/system/xbin" }
                .start()
            process = proc

            val stdout = StringBuilder()
            val stderr = StringBuilder()
            val outThread = Thread { drain(proc.inputStream, stdout) }.apply { start() }
            val errThread = Thread { drain(proc.errorStream, stderr) }.apply { start() }

            val finished = proc.waitFor(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            if (!finished) {
                proc.destroy()
                if (!proc.waitFor(1, TimeUnit.SECONDS)) proc.destroyForcibly()
            }
            outThread.join(2_000)
            errThread.join(2_000)

            buildJsonObject {
                put("exitCode", JsonPrimitive(if (finished) runCatching { proc.exitValue() }.getOrDefault(-1) else -1))
                put("stdout", JsonPrimitive(stdout.toString()))
                put("stderr", JsonPrimitive(stderr.toString()))
                put("timedOut", JsonPrimitive(!finished))
                put("truncated", JsonPrimitive(stdout.length >= MAX_CHARS || stderr.length >= MAX_CHARS))
                put("uid", JsonPrimitive(runCatching { android.os.Process.myUid() }.getOrDefault(-1)))
                put("durationMs", JsonPrimitive(System.currentTimeMillis() - started))
            }.toString()
        } catch (t: Throwable) {
            buildJsonObject {
                put("exitCode", JsonPrimitive(-1))
                put("stdout", JsonPrimitive(""))
                put("stderr", JsonPrimitive(""))
                put("timedOut", JsonPrimitive(false))
                put("error", JsonPrimitive(t.message ?: t::class.java.simpleName))
                put("durationMs", JsonPrimitive(System.currentTimeMillis() - started))
            }.toString()
        } finally {
            runCatching { process?.destroyForcibly() }
        }
    }

    private fun drain(input: InputStream, sink: StringBuilder) {
        val buffer = ByteArray(8_192)
        try {
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                if (sink.length < MAX_CHARS) {
                    val remaining = MAX_CHARS - sink.length
                    sink.append(String(buffer, 0, minOf(read, remaining), Charsets.UTF_8))
                }
            }
        } catch (_: Throwable) {
            // stream closed when the process dies
        }
    }

    companion object {
        const val MSG_EXEC = 1
        const val MSG_RESULT = 2
        const val MSG_DESTROY = 3
        private const val MAX_CHARS = 200_000
    }
}
