// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.cloudbridge

import com.google.gson.JsonObject
import io.agents.pokeclaw.TaskEvent
import io.agents.pokeclaw.bridge.api.TaskExecutorCallback
import io.agents.pokeclaw.channel.Channel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [TaskOrchestratorExecutorAdapter].
 */
class TaskOrchestratorExecutorAdapterTest {

    private lateinit var fakeOrchestrator: FakeOrchestratorPort
    private lateinit var resultSink: DefaultCloudTaskResultSink
    private lateinit var adapter: TaskOrchestratorExecutorAdapter

    @BeforeEach
    fun setUp() {
        fakeOrchestrator = FakeOrchestratorPort()
        resultSink = DefaultCloudTaskResultSink()
        adapter = TaskOrchestratorExecutorAdapter(
            orchestrator = fakeOrchestrator,
            resultSink = resultSink,
        )
    }

    @Test
    fun `execute calls startNewTask with CLOUD channel and requestId`() {
        val callback = RecordingCallback()
        val params = JsonObject().apply { addProperty("account", "main") }

        adapter.execute("req_001", "ths.sync_holdings", params, null, callback)

        assertThat(fakeOrchestrator.startedTasks).hasSize(1)
        val task = fakeOrchestrator.startedTasks[0]
        assertThat(task.channel).isEqualTo(Channel.CLOUD)
        assertThat(task.messageId).isEqualTo("req_001")
        assertThat(task.taskText).contains("ths")
        assertThat(task.taskText).contains("sync_holdings")
    }

    @Test
    fun `execute immediately calls onAccepted`() {
        val callback = RecordingCallback()
        adapter.execute("req_002", "ths.sync_holdings", JsonObject(), null, callback)

        assertThat(callback.acceptedIds).containsExactly("req_002")
    }

    @Test
    fun `TaskEvent Completed maps to onResult`() {
        val callback = RecordingCallback()

        adapter.execute("req_003", "ths.sync_holdings", JsonObject(), null, callback)
        fakeOrchestrator.simulateEvent(TaskEvent.Completed("Done"))

        assertThat(callback.results).hasSize(1)
        assertThat(callback.results[0].requestId).isEqualTo("req_003")
        assertThat(callback.results[0].kind).isEqualTo("ths.sync_holdings")
    }

    @Test
    fun `TaskEvent Failed maps to onError`() {
        val callback = RecordingCallback()

        adapter.execute("req_004", "ths.sync_holdings", JsonObject(), null, callback)
        fakeOrchestrator.simulateEvent(TaskEvent.Failed("Something went wrong"))

        assertThat(callback.errors).hasSize(1)
        assertThat(callback.errors[0].requestId).isEqualTo("req_004")
        assertThat(callback.errors[0].code).isEqualTo("execution_failed")
        assertThat(callback.errors[0].message).isEqualTo("Something went wrong")
    }

    @Test
    fun `TaskEvent Cancelled maps to onError with cancelled code`() {
        val callback = RecordingCallback()

        adapter.execute("req_005", "ths.sync_holdings", JsonObject(), null, callback)
        fakeOrchestrator.simulateEvent(TaskEvent.Cancelled)

        assertThat(callback.errors).hasSize(1)
        assertThat(callback.errors[0].code).isEqualTo("cancelled")
    }

    @Test
    fun `TaskEvent Progress maps to onProgress`() {
        val callback = RecordingCallback()

        adapter.execute("req_006", "ths.sync_holdings", JsonObject(), null, callback)
        fakeOrchestrator.simulateEvent(TaskEvent.Progress(1, "Opening app"))

        assertThat(callback.progressSteps).hasSize(1)
        assertThat(callback.progressSteps[0].step).isEqualTo("Opening app")
    }

    @Test
    fun `cancel is no-op when requestId does not match`() {
        val callback = RecordingCallback()

        adapter.execute("req_007", "ths.sync_holdings", JsonObject(), null, callback)
        // Cancel a different requestId — should be no-op
        fakeOrchestrator.clearTask()
        adapter.cancel("req_999")

        assertThat(fakeOrchestrator.cancelCalled).isFalse()
    }

    @Test
    fun `cancel calls cancelCurrentTask when requestId matches`() {
        val callback = RecordingCallback()

        adapter.execute("req_008", "ths.sync_holdings", JsonObject(), null, callback)
        adapter.cancel("req_008")

        assertThat(fakeOrchestrator.cancelCalled).isTrue()
    }

    @Test
    fun `execute returns TaskHandle that reflects active state`() {
        val callback = RecordingCallback()

        val handle = adapter.execute(
            "req_009", "ths.sync_holdings", JsonObject(), null, callback
        )

        assertThat(handle.requestId).isEqualTo("req_009")
        assertThat(handle.isActive()).isTrue()

        // Simulate task completion
        fakeOrchestrator.simulateEvent(TaskEvent.Completed("Done"))
        fakeOrchestrator.clearTask()

        assertThat(handle.isActive()).isFalse()
    }
}
