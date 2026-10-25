package com.mobileagent.app.tools

import com.mobileagent.app.work.ScheduleScheduler
import com.mobileagent.core.data.IntegrationRepository
import com.mobileagent.core.model.AgentTool
import com.mobileagent.core.model.RiskLevel
import com.mobileagent.core.model.ScheduleRecord
import com.mobileagent.core.model.ToolCategory
import com.mobileagent.core.model.ToolContext
import com.mobileagent.core.model.ToolResult
import com.mobileagent.core.model.ToolSpec
import com.mobileagent.execution.tools.Schemas
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/** Creates a repeating background task through WorkManager. */
class ScheduleCreateTool(
    private val repository: IntegrationRepository,
    private val scheduler: ScheduleScheduler,
) : AgentTool {

    override val spec: ToolSpec = ToolSpec(
        name = "schedule.create",
        title = "Schedule a repeating task",
        description = "Create a repeating background task. Android may delay runs due to Doze; the interval is at least 15 minutes. " +
            "Every run is a normal task with its own log.",
        parametersSchema = Schemas.obj(
            required = listOf("goal"),
            props = mapOf(
                "goal" to Schemas.str("What the agent should do each run", maxLength = 2_000),
                "name" to Schemas.str("Display name", maxLength = 120),
                "intervalMinutes" to Schemas.int("Interval in minutes (minimum 15)", 15, 10_080),
            ),
        ),
        risk = RiskLevel.MEDIUM,
        category = ToolCategory.SYSTEM,
        source = "app",
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val startedAt = System.currentTimeMillis()
        val goal = arguments["goal"]?.jsonPrimitive?.content.orEmpty()
        if (goal.isBlank()) {
            return ToolResult(context.callId, spec.name, false, JsonObject(emptyMap()), "goal is required", error = "invalid arguments", durationMs = 0)
        }
        val interval = (arguments["intervalMinutes"]?.jsonPrimitive?.content?.toIntOrNull() ?: 60).coerceAtLeast(15)
        val name = arguments["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: goal.take(40)
        val record = ScheduleRecord(
            id = UUID.randomUUID().toString(),
            name = name,
            goal = goal,
            intervalMinutes = interval,
            enabled = true,
            lastRunAt = null,
            nextRunAt = System.currentTimeMillis() + interval * 60_000L,
        )
        repository.upsertSchedule(record)
        scheduler.schedule(record)
        return ToolResult(
            callId = context.callId,
            toolName = spec.name,
            ok = true,
            output = buildJsonObject {
                put("scheduleId", JsonPrimitive(record.id))
                put("intervalMinutes", JsonPrimitive(interval))
                put("name", JsonPrimitive(name))
            },
            summary = "Scheduled '$name' every $interval minutes.",
            durationMs = System.currentTimeMillis() - startedAt,
        )
    }
}
