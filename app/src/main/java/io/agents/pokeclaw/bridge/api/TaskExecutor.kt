package io.agents.pokeclaw.bridge.api

import com.google.gson.JsonObject

/**
 * 宿主 app 对 Bridge 暴露的任务执行能力。
 *
 * 调用约定：
 *  - [execute] 由 Bridge 在其 dispatcher 线程上调用；实现方必须立即返回一个 [TaskHandle]，
 *    真正的执行应该异步化。
 *  - 实现方通过 [callback] 回报事件；回调可以发生在任意线程，Bridge 会把它们 post 回自己的
 *    dispatcher。
 *  - 对于同一个 requestId，必须且只能产生一次终态事件（result 或 error）。
 *  - [cancel] 是尽力而为：实现方尽快打断执行并最终回调一次 error(code = "cancelled")。
 */
interface TaskExecutor {
    fun execute(
        requestId: String,
        kind: String,
        params: JsonObject,
        deadlineTsMillis: Long?,
        callback: TaskExecutorCallback,
    ): TaskHandle

    fun cancel(requestId: String)
}

interface TaskHandle {
    val requestId: String
    fun isActive(): Boolean
}

/** 所有回调都可能在任意线程触发；Bridge 会做线程隔离。 */
interface TaskExecutorCallback {
    fun onAccepted(requestId: String)
    fun onProgress(requestId: String, step: String, ratio: Double?)
    fun onResult(requestId: String, kind: String, result: JsonObject)
    fun onError(requestId: String, code: String, message: String, retryable: Boolean)
}
