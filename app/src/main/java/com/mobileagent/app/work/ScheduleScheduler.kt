package com.mobileagent.app.work

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.mobileagent.core.data.IntegrationRepository
import com.mobileagent.core.model.ScheduleRecord
import java.util.concurrent.TimeUnit

/** Registers each enabled schedule as unique periodic work. */
class ScheduleScheduler(
    private val context: Context,
    private val integrations: IntegrationRepository,
) {

    fun schedule(record: ScheduleRecord) {
        val interval = record.intervalMinutes.coerceAtLeast(MIN_INTERVAL_MINUTES).toLong()
        val request = PeriodicWorkRequestBuilder<ScheduleWorker>(interval, TimeUnit.MINUTES)
            .setInputData(workDataOf(ScheduleWorker.KEY_SCHEDULE_ID to record.id))
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            uniqueName(record.id),
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancel(scheduleId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(uniqueName(scheduleId))
    }

    suspend fun restoreAll() {
        runCatching {
            integrations.enabledSchedules().forEach { schedule(it) }
        }
    }

    private fun uniqueName(scheduleId: String) = "mobile-agent-schedule-$scheduleId"

    companion object {
        const val MIN_INTERVAL_MINUTES = 15
    }
}
