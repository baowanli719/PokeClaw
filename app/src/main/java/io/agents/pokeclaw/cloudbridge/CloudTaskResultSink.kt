// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.cloudbridge

import com.google.gson.JsonObject
import io.agents.pokeclaw.bridge.api.TaskExecutorCallback
import java.util.concurrent.ConcurrentHashMap

/**
 * Structured result push interface for cloud tasks.
 *
 * Skills / playbooks call [submitResult] at the end of execution to push
 * a structured JSON result back to the bridge adapter, which then forwards
 * it to the cloud via [TaskExecutorCallback.onResult].
 */
interface CloudTaskResultSink {
    /**
     * Register a pending task so that [submitResult] can route the result
     * to the correct callback.
     */
    fun register(requestId: String, kind: String, callback: TaskExecutorCallback)

    /**
     * Submit a structured result for the given requestId.
     * Calls [TaskExecutorCallback.onResult] and removes the registration.
     */
    fun submitResult(requestId: String, result: JsonObject)

    /**
     * Clear a registration without submitting a result (e.g. on cancel).
     */
    fun clear(requestId: String)
}

/**
 * Default in-memory implementation backed by [ConcurrentHashMap].
 */
class DefaultCloudTaskResultSink : CloudTaskResultSink {

    private data class Entry(val kind: String, val callback: TaskExecutorCallback)

    private val pending = ConcurrentHashMap<String, Entry>()

    override fun register(requestId: String, kind: String, callback: TaskExecutorCallback) {
        pending[requestId] = Entry(kind, callback)
    }

    override fun submitResult(requestId: String, result: JsonObject) {
        val entry = pending.remove(requestId) ?: return
        entry.callback.onResult(requestId, entry.kind, result)
    }

    override fun clear(requestId: String) {
        pending.remove(requestId)
    }
}
