package com.mobileagent.core.agent

import com.mobileagent.core.model.TaskStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskStateMachineTest {

    @Test
    fun `created task can move to planning and running`() {
        assertTrue(TaskStateMachine.canTransition(TaskStatus.CREATED, TaskStatus.PLANNING))
        assertTrue(TaskStateMachine.canTransition(TaskStatus.PLANNING, TaskStatus.RUNNING_HEADLESS))
        assertTrue(TaskStateMachine.canTransition(TaskStatus.RUNNING_HEADLESS, TaskStatus.RUNNING_INTERACTIVE))
    }

    @Test
    fun `terminal states do not leak into running except an explicit follow-up`() {
        assertTrue(TaskStateMachine.canTransition(TaskStatus.COMPLETED, TaskStatus.RECOVERING))
        assertFalse(TaskStateMachine.canTransition(TaskStatus.CANCELLED, TaskStatus.RUNNING_HEADLESS))
        assertFalse(TaskStateMachine.canTransition(TaskStatus.FAILED, TaskStatus.COMPLETED))
    }

    @Test
    fun `waiting states can return to execution`() {
        assertTrue(TaskStateMachine.canTransition(TaskStatus.WAITING_FOR_CONFIRMATION, TaskStatus.RUNNING_HEADLESS))
        assertTrue(TaskStateMachine.canTransition(TaskStatus.WAITING_FOR_UI, TaskStatus.RUNNING_INTERACTIVE))
        assertTrue(TaskStateMachine.canTransition(TaskStatus.WAITING_FOR_NETWORK, TaskStatus.RECOVERING))
    }

    @Test
    fun `recovering can resume but cannot jump to completed`() {
        assertTrue(TaskStateMachine.canTransition(TaskStatus.RECOVERING, TaskStatus.RUNNING_HEADLESS))
        assertFalse(TaskStateMachine.canTransition(TaskStatus.RECOVERING, TaskStatus.COMPLETED))
    }

    @Test
    fun `illegal backwards transition is rejected`() {
        assertFalse(TaskStateMachine.canTransition(TaskStatus.COMPLETED, TaskStatus.RUNNING_HEADLESS))
        assertFalse(TaskStateMachine.canTransition(TaskStatus.CREATED, TaskStatus.COMPLETED))
    }
}
