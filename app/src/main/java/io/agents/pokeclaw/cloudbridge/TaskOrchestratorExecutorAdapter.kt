// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.cloudbridge

import com.google.gson.JsonObject
import io.agents.pokeclaw.TaskEvent
import io.agents.pokeclaw.TaskOrchestrator
import io.agents.pokeclaw.bridge.api.TaskExecutor
import io.agents.pokeclaw.bridge.api.TaskExecutorCallback
import io.agents.pokeclaw.bridge.api.TaskHandle
import io.agents.pokeclaw.channel.Channel

/**
 * Thin abstraction over [TaskOrchestrator] to enable unit testing.
 */
interface OrchestratorPort {
    var taskEventCallback: ((TaskEvent) -> Unit)?
    val inProgressTaskMessageId: String
    val inProgressTaskChannel: Channel?
    fun startNewTask(channel: Channel, task: String, messageID: String)
    fun cancelCurrentTask()
}

/**
 * Production implementation that delegates to the real [TaskOrchestrator].
 */
class RealOrchestratorPort(private val orchestrator: TaskOrchestrator) : OrchestratorPort {
    override var taskEventCallback: ((TaskEvent) -> Unit)?
        get() = orchestrator.taskEventCallback
        set(value) { orchestrator.taskEventCallback = value }

    override val inProgressTaskMessageId: String
        get() = orchestrator.inProgressTaskMessageId

    override val inProgressTaskChannel: Channel?
        get() = orchestrator.inProgressTaskChannel

    override fun startNewTask(channel: Channel, task: String, messageID: String) {
        orchestrator.startNewTask(channel, task, messageID)
    }

    override fun cancelCurrentTask() {
        orchestrator.cancelCurrentTask()
    }
}

/**
 * Bridges the Bridge's [TaskExecutor] interface to [TaskOrchestrator].
 *
 * On [execute]:
 *  1. Registers the requestId in [resultSink].
 *  2. Maps kind+params to a task text via [CloudKindMapper].
 *  3. Installs a [TaskEvent] listener that forwards events to callback.
 *  4. Calls startNewTask with Channel.CLOUD.
 *  5. Immediately calls callback.onAccepted.
 */
class TaskOrchestratorExecutorAdapter(
    private val orchestrator: OrchestratorPort,
    private val resultSink: CloudTaskResultSink,
    private val kindMapper: KindMapper = CloudKindMapper,
) : TaskExecutor {

    /** Convenience constructor accepting the real TaskOrchestrator. */
    constructor(
        orchestrator: TaskOrchestrator,
        resultSink: CloudTaskResultSink,
        kindMapper: KindMapper = CloudKindMapper,
    ) : this(RealOrchestratorPort(orchestrator), resultSink, kindMapper)

    override fun execute(
        requestId: String,
        kind: String,
        params: JsonObject,
        deadlineTsMillis: Long?,
        callback: TaskExecutorCallback,
    ): TaskHandle {
        // 1. Register in result sink for structured result delivery
        resultSink.register(requestId, kind, callback)

        // 2. Map kind to task text
        val taskText = kindMapper.toTaskText(kind, params)
            ?: "/cloud $kind $params"

        // 3. Install event listener bridging TaskEvent → TaskExecutorCallback
        val previousCallback = orchestrator.taskEventCallback
        orchestrator.taskEventCallback = { event ->
            // Only handle events for our cloud task
            if (orchestrator.inProgressTaskMessageId == requestId &&
                orchestrator.inProgressTaskChannel == Channel.CLOUD
            ) {
                when (event) {
                    is TaskEvent.Progress -> {
                        callback.onProgress(requestId, event.description, null)
                    }
                    is TaskEvent.ToolAction -> {
                        callback.onProgress(requestId, event.toolName, null)
                    }
                    is TaskEvent.Completed -> {
                        resultSink.clear(requestId)
                        val result = JsonObject().apply {
                            addProperty("kind", kind)
                            addProperty("summary", event.answer)
                        }
                        callback.onResult(requestId, kind, result)
                        orchestrator.taskEventCallback = previousCallback
                    }
                    is TaskEvent.Failed -> {
                        resultSink.clear(requestId)
                        callback.onError(
                            requestId, "execution_failed",
                            event.error, retryable = true,
                        )
                        orchestrator.taskEventCallback = previousCallback
                    }
                    is TaskEvent.Cancelled -> {
                        resultSink.clear(requestId)
                        callback.onError(
                            requestId, "cancelled",
                            "Task cancelled", retryable = false,
                        )
                        orchestrator.taskEventCallback = previousCallback
                    }
                    is TaskEvent.Blocked -> {
                        resultSink.clear(requestId)
                        callback.onError(
                            requestId, "blocked",
                            "System dialog blocked execution",
                            retryable = true,
                        )
                        orchestrator.taskEventCallback = previousCallback
                    }
                    else -> { /* LoopStart, TokenUpdate, Thinking — ignore */ }
                }
            }
            previousCallback?.invoke(event)
        }

        // 4. Start the task via orchestrator
        orchestrator.startNewTask(
            channel = Channel.CLOUD,
            task = taskText,
            messageID = requestId,
        )

        // 5. Immediately report accepted
        callback.onAccepted(requestId)

        return CloudTaskHandle(requestId, orchestrator)
    }

    override fun cancel(requestId: String) {
        if (orchestrator.inProgressTaskMessageId == requestId &&
            orchestrator.inProgressTaskChannel == Channel.CLOUD
        ) {
            orchestrator.cancelCurrentTask()
        }
    }
}

private class CloudTaskHandle(
    override val requestId: String,
    private val orchestrator: OrchestratorPort,
) : TaskHandle {
    override fun isActive(): Boolean {
        return orchestrator.inProgressTaskMessageId == requestId &&
            orchestrator.inProgressTaskChannel == Channel.CLOUD
    }
}
