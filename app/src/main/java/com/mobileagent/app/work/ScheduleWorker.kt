package com.mobileagent.app.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mobileagent.app.MobileAgentApplication
import kotlinx.coroutines.delay

/**
 * Runs one occurrence of a schedule. The worker starts a normal task through the same runtime used
 * by the UI, so checkpoints, cancellation and logs are identical.
 */
class ScheduleWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val application = applicationContext as? MobileAgentApplication ?: return Result.failure()
        val graph = application.graph
        val scheduleId = inputData.getString(KEY_SCHEDULE_ID) ?: return Result.failure()

        val schedule = graph.integrations.getSchedule(scheduleId) ?: return Result.success()
        if (!schedule.enabled) return Result.success()

        val task = runCatching { graph.runtime.submit(schedule.goal) }.getOrElse { return Result.retry() }
        graph.integrations.upsertSchedule(
            schedule.copy(
                lastRunAt = System.currentTimeMillis(),
                nextRunAt = System.currentTimeMillis() + schedule.intervalMinutes * 60_000L,
            ),
        )

        // Keep the worker alive while the task runs, up to the worker's execution limit.
        val deadline = System.currentTimeMillis() + 9 * 60_000L
        while (System.currentTimeMillis() < deadline) {
            val current = graph.tasks.getTask(task.id)
            if (current == null || current.status.isTerminal) return Result.success()
            delay(3_000)
        }
        // The task may still be running inside the app process; if the process dies it is paused
        // on the next start and can be resumed from the Tasks screen.
        return Result.success()
    }

    companion object {
        const val KEY_SCHEDULE_ID = "scheduleId"
    }
}
