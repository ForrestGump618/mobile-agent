package com.mobileagent.core.agent

import com.mobileagent.core.model.TaskStatus

/**
 * Explicit task state machine. Any illegal transition is rejected instead of being silently
 * applied, which is what prevents duplicate execution and conflicting concurrent runs.
 */
object TaskStateMachine {

    private val transitions: Map<TaskStatus, Set<TaskStatus>> = buildMap {
        put(TaskStatus.CREATED, setOf(TaskStatus.PLANNING, TaskStatus.READY, TaskStatus.RUNNING_HEADLESS, TaskStatus.FAILED, TaskStatus.CANCELLED))
        put(
            TaskStatus.PLANNING,
            setOf(
                TaskStatus.READY, TaskStatus.RUNNING_HEADLESS, TaskStatus.WAITING_FOR_NETWORK,
                TaskStatus.PAUSED, TaskStatus.FAILED, TaskStatus.CANCELLED,
            ),
        )
        put(
            TaskStatus.READY,
            setOf(
                TaskStatus.RUNNING_HEADLESS, TaskStatus.RUNNING_INTERACTIVE, TaskStatus.WAITING_FOR_PERMISSION,
                TaskStatus.WAITING_FOR_CONFIRMATION, TaskStatus.WAITING_FOR_UI, TaskStatus.WAITING_FOR_NETWORK,
                TaskStatus.PAUSED, TaskStatus.COMPLETED, TaskStatus.FAILED, TaskStatus.CANCELLED,
            ),
        )
        val running = setOf(
            TaskStatus.RUNNING_HEADLESS, TaskStatus.RUNNING_INTERACTIVE, TaskStatus.WAITING_FOR_PERMISSION,
            TaskStatus.WAITING_FOR_CONFIRMATION, TaskStatus.WAITING_FOR_UI, TaskStatus.WAITING_FOR_NETWORK,
            TaskStatus.PAUSED, TaskStatus.READY, TaskStatus.RECOVERING,
            TaskStatus.COMPLETED, TaskStatus.FAILED, TaskStatus.CANCELLED,
        )
        put(TaskStatus.RUNNING_HEADLESS, running)
        put(TaskStatus.RUNNING_INTERACTIVE, running)
        put(
            TaskStatus.WAITING_FOR_PERMISSION,
            setOf(
                TaskStatus.RUNNING_HEADLESS, TaskStatus.RUNNING_INTERACTIVE, TaskStatus.WAITING_FOR_CONFIRMATION,
                TaskStatus.PAUSED, TaskStatus.READY, TaskStatus.RECOVERING, TaskStatus.FAILED, TaskStatus.CANCELLED,
            ),
        )
        put(
            TaskStatus.WAITING_FOR_CONFIRMATION,
            setOf(
                TaskStatus.RUNNING_HEADLESS, TaskStatus.RUNNING_INTERACTIVE, TaskStatus.PAUSED,
                TaskStatus.READY, TaskStatus.RECOVERING, TaskStatus.FAILED, TaskStatus.CANCELLED,
            ),
        )
        put(
            TaskStatus.WAITING_FOR_UI,
            setOf(
                TaskStatus.RUNNING_INTERACTIVE, TaskStatus.RUNNING_HEADLESS, TaskStatus.PAUSED,
                TaskStatus.READY, TaskStatus.RECOVERING, TaskStatus.FAILED, TaskStatus.CANCELLED,
            ),
        )
        put(
            TaskStatus.WAITING_FOR_NETWORK,
            setOf(TaskStatus.RUNNING_HEADLESS, TaskStatus.RECOVERING, TaskStatus.PAUSED, TaskStatus.FAILED, TaskStatus.CANCELLED),
        )
        put(
            TaskStatus.PAUSED,
            setOf(TaskStatus.READY, TaskStatus.RECOVERING, TaskStatus.RUNNING_HEADLESS, TaskStatus.RUNNING_INTERACTIVE, TaskStatus.CANCELLED, TaskStatus.FAILED),
        )
        put(
            TaskStatus.RECOVERING,
            setOf(TaskStatus.PLANNING, TaskStatus.READY, TaskStatus.RUNNING_HEADLESS, TaskStatus.RUNNING_INTERACTIVE, TaskStatus.PAUSED, TaskStatus.FAILED, TaskStatus.CANCELLED),
        )
        put(TaskStatus.FAILED, setOf(TaskStatus.RECOVERING, TaskStatus.CANCELLED))
        put(TaskStatus.COMPLETED, setOf(TaskStatus.RECOVERING))
        put(TaskStatus.CANCELLED, emptySet())
    }

    fun allowedTargets(from: TaskStatus): Set<TaskStatus> = transitions[from].orEmpty()

    fun canTransition(from: TaskStatus, to: TaskStatus): Boolean =
        from == to || transitions[from].orEmpty().contains(to)

    /** Returns true when the transition was legal. Illegal transitions are ignored, not forced. */
    fun isLegal(from: TaskStatus, to: TaskStatus): Boolean = canTransition(from, to)
}
