// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.cloudbridge

import io.agents.pokeclaw.TaskEvent
import io.agents.pokeclaw.channel.Channel

/**
 * Fake [OrchestratorPort] for unit testing the adapter.
 */
class FakeOrchestratorPort : OrchestratorPort {

    data class StartedTask(
        val channel: Channel,
        val taskText: String,
        val messageId: String,
    )

    val startedTasks = mutableListOf<StartedTask>()
    var cancelCalled = false
        private set

    private var _currentMessageId: String = ""
    private var _currentChannel: Channel? = null

    override var taskEventCallback: ((TaskEvent) -> Unit)? = null

    override val inProgressTaskMessageId: String
        get() = _currentMessageId

    override val inProgressTaskChannel: Channel?
        get() = _currentChannel

    override fun startNewTask(channel: Channel, task: String, messageID: String) {
        startedTasks.add(StartedTask(channel, task, messageID))
        _currentMessageId = messageID
        _currentChannel = channel
    }

    override fun cancelCurrentTask() {
        cancelCalled = true
    }

    /** Simulate a TaskEvent being fired by the orchestrator. */
    fun simulateEvent(event: TaskEvent) {
        taskEventCallback?.invoke(event)
    }

    /** Clear the current task state (simulates task completion). */
    fun clearTask() {
        _currentMessageId = ""
        _currentChannel = null
    }
}
