package dev.chx.claudeprivacy

import org.junit.Assert.*
import org.junit.Test

class PlayIntegrityHooksTest {
    class Task {
        val errors = mutableListOf<Exception>()
        fun reject(error: Exception) { errors.add(error) }
    }

    @Test fun requestSuppressionReturnsACompletedFailure() {
        val task = failedIntegrityTask(Task::class.java)() as Task
        assertEquals(1, task.errors.size)
        assertTrue(task.errors.single() is SecurityException)
    }
}
