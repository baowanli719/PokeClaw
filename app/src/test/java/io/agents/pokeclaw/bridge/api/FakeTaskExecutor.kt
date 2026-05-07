package io.agents.pokeclaw.bridge.api

import com.google.gson.JsonObject

/**
 * Programmable fake [TaskExecutor] for unit tests.
 * Records all execute/cancel calls and returns a simple [FakeTaskHandle].
 */
class FakeTaskExecutor : TaskExecutor {

    data class ExecuteCall(
        val requestId: String,
        val kind: String,
        val params: JsonObject,
        val deadlineTsMillis: Long?,
        val callback: TaskExecutorCallback,
    )

    val executeCalls = mutableListOf<ExecuteCall>()
    val cancelCalls = mutableListOf<String>()

    override fun execute(
        requestId: String,
        kind: String,
        params: JsonObject,
        deadlineTsMillis: Long?,
        callback: TaskExecutorCallback,
    ): TaskHandle {
        executeCalls += ExecuteCall(requestId, kind, params, deadlineTsMillis, callback)
        return FakeTaskHandle(requestId)
    }

    override fun cancel(requestId: String) {
        cancelCalls += requestId
    }
}

class FakeTaskHandle(
    override val requestId: String,
    private var active: Boolean = true,
) : TaskHandle {
    override fun isActive(): Boolean = active
    fun deactivate() { active = false }
}
