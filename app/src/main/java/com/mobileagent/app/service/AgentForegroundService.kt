package com.mobileagent.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mobileagent.app.MainActivity
import com.mobileagent.app.MobileAgentApplication
import com.mobileagent.app.R
import com.mobileagent.app.work.ScheduleScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the process alive while an agent task is executing, and gives the
 * user a stop action. It does not run any work itself: the harness owns the task.
 */
class AgentForegroundService : android.app.Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
        startForeground(NOTIFICATION_ID, buildNotification("Agent is starting…", null))
        val graph = (application as? MobileAgentApplication)?.graph ?: return
        scope.launch {
            graph.runs.runs.collect { runs ->
                val active = runs.values.filter { it.status.isRunning }
                if (active.isEmpty()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@collect
                }
                val first = active.first()
                val label = first.currentTool?.let { "Running $it" } ?: "Agent is working…"
                getSystemService(NotificationManager::class.java)
                    ?.notify(NOTIFICATION_ID, buildNotification(label, first.taskId))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val graph = (application as? MobileAgentApplication)?.graph
        when (intent?.action) {
            ACTION_STOP -> {
                val taskId = intent.getStringExtra(EXTRA_TASK_ID)
                if (graph != null && taskId != null) {
                    scope.launch { graph.runtime.cancel(taskId) }
                }
            }

            ACTION_START -> {
                val taskId = intent.getStringExtra(EXTRA_TASK_ID)
                if (taskId != null && graph != null) {
                    graph.runtime.start(taskId)
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(text: String, taskId: String?): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Mobile Agent")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openIntent)

        if (taskId != null) {
            val stopIntent = PendingIntent.getService(
                this,
                1,
                Intent(this, AgentForegroundService::class.java)
                    .setAction(ACTION_STOP)
                    .putExtra(EXTRA_TASK_ID, taskId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, "Stop", stopIntent)
        }
        return builder.build()
    }

    companion object {
        const val CHANNEL_ID = "agent_runs"
        const val NOTIFICATION_ID = 4201
        const val ACTION_START = "com.mobileagent.action.START_TASK"
        const val ACTION_STOP = "com.mobileagent.action.STOP_TASK"
        const val EXTRA_TASK_ID = "taskId"

        fun start(context: Context, taskId: String? = null) {
            val intent = Intent(context, AgentForegroundService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_TASK_ID, taskId)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun stopTask(context: Context, taskId: String) {
            val intent = Intent(context, AgentForegroundService::class.java)
                .setAction(ACTION_STOP)
                .putExtra(EXTRA_TASK_ID, taskId)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Agent runs",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Shows when the agent is executing a task"
                    setShowBadge(false)
                }
                context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
            }
        }
    }
}
