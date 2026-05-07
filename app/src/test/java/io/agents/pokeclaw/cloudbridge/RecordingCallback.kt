// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.cloudbridge

import com.google.gson.JsonObject
import io.agents.pokeclaw.bridge.api.TaskExecutorCallback

/**
 * Records all [TaskExecutorCallback] invocations for test assertions.
 */
class RecordingCallback : TaskExecutorCallback {

    data class ProgressEntry(val requestId: String, val step: String, val ratio: Double?)
    data class ResultEntry(val requestId: String, val kind: String, val result: JsonObject)
    data class ErrorEntry(
        val requestId: String,
        val code: String,
        val message: String,
        val retryable: Boolean,
    )

    val acceptedIds = mutableListOf<String>()
    val progressSteps = mutableListOf<ProgressEntry>()
    val results = mutableListOf<ResultEntry>()
    val errors = mutableListOf<ErrorEntry>()

    override fun onAccepted(requestId: String) {
        acceptedIds.add(requestId)
    }

    override fun onProgress(requestId: String, step: String, ratio: Double?) {
        progressSteps.add(ProgressEntry(requestId, step, ratio))
    }

    override fun onResult(requestId: String, kind: String, result: JsonObject) {
        results.add(ResultEntry(requestId, kind, result))
    }

    override fun onError(
        requestId: String,
        code: String,
        message: String,
        retryable: Boolean,
    ) {
        errors.add(ErrorEntry(requestId, code, message, retryable))
    }
}
