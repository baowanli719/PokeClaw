// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.llm

import io.agents.pokeclaw.agent.langchain.http.OkHttpClientBuilderAdapter
import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.utils.XLog
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.openai.OpenAiChatModel

/**
 * Single source of truth for LLM client creation.
 *
 * Eliminates duplicate client construction in ComposeChatActivity,
 * AutoReplyManager.generateReplyCloud(), and AutoReplyManager.singleLlmCall().
 *
 * Thread-safe. All methods can be called from any thread.
 */
object LlmSessionManager {

    private const val TAG = "LlmSessionManager"

    /**
     * Create a Cloud LLM ChatModel using the user's current config.
     * Returns null if no API key is configured.
     */
    fun createCloudChatModel(temperature: Double = 0.7): dev.langchain4j.model.chat.ChatModel? {
        val provider = KVUtils.getLlmProvider()
        var apiKey = KVUtils.getLlmApiKey()
        val baseUrl = KVUtils.getLlmBaseUrl()
        val modelName = KVUtils.getLlmModelName()

        if (apiKey.isEmpty()) {
            apiKey = KVUtils.getApiKeyForProvider(provider)
        }
        if (apiKey.isEmpty()) {
            XLog.w(TAG, "createCloudChatModel: no API key configured")
            return null
        }

        XLog.d(TAG, "createCloudChatModel: model=$modelName, baseUrl=$baseUrl")
        return OpenAiChatModel.builder()
            .httpClientBuilder(OkHttpClientBuilderAdapter())
            .apiKey(apiKey)
            .modelName(modelName.ifEmpty { "gpt-4o-mini" })
            .baseUrl(baseUrl.ifEmpty { "https://api.openai.com/v1" })
            .temperature(temperature)
            .build()
    }

    /**
     * Single-shot LLM call — send one prompt, get one response.
     * Uses the user's selected Cloud or Local model.
     * For quick targeted questions (not a full agent loop).
     *
     * @return LLM response text, or null if failed
     */
    fun singleShot(prompt: String, temperature: Double = 0.3): String? {
        val provider = KVUtils.getLlmProvider()

        return if (provider != "LOCAL") {
            singleShotCloud(prompt, temperature)
        } else {
            singleShotLocal(prompt, temperature)
        }
    }

    /**
     * Single-shot Cloud LLM call.
     */
    fun singleShotCloud(prompt: String, temperature: Double = 0.7): String? {
        return try {
            val chatModel = createCloudChatModel(temperature) ?: return null
            val messages = listOf<ChatMessage>(UserMessage.from(prompt))
            val request = ChatRequest.builder().messages(messages).build()
            val response = chatModel.chat(request)
            response.aiMessage().text()
        } catch (e: Exception) {
            XLog.w(TAG, "singleShotCloud failed: ${e.message}")
            null
        }
    }

    /**
     * Single-shot Cloud LLM call with system prompt.
     */
    fun singleShotCloud(systemPrompt: String, userPrompt: String, temperature: Double = 0.7): String? {
        return try {
            val chatModel = createCloudChatModel(temperature) ?: return null
            val messages = listOf<ChatMessage>(
                SystemMessage.from(systemPrompt),
                UserMessage.from(userPrompt)
            )
            val request = ChatRequest.builder().messages(messages).build()
            val response = chatModel.chat(request)
            response.aiMessage().text()
        } catch (e: Exception) {
            XLog.w(TAG, "singleShotCloud failed: ${e.message}")
            null
        }
    }

    /**
     * Single-shot Local LLM call using LiteRT-LM.
     */
    fun singleShotLocal(prompt: String, temperature: Double = 0.3): String? {
        return try {
            val modelPath = KVUtils.getLocalModelPath()
            if (modelPath.isNullOrEmpty()) return null

            val cacheDir = io.agents.pokeclaw.ClawApplication.instance.cacheDir.path
            val engine = EngineHolder.getOrCreate(modelPath, cacheDir)
            val conv = engine.createConversation(
                com.google.ai.edge.litertlm.ConversationConfig(
                    com.google.ai.edge.litertlm.Contents.of("You are a helpful assistant. Answer concisely."),
                    emptyList(), emptyList(),
                    com.google.ai.edge.litertlm.SamplerConfig(64, 0.95, temperature, 0)
                )
            )
            val response = conv.sendMessage(prompt, emptyMap())
            conv.close()
            response.contents?.toString()?.trim()
        } catch (e: Exception) {
            XLog.w(TAG, "singleShotLocal failed: ${e.message}")
            null
        }
    }

    /**
     * Check if Cloud LLM is configured (has API key).
     */
    fun isCloudConfigured(): Boolean {
        val provider = KVUtils.getLlmProvider()
        val apiKey = KVUtils.getLlmApiKey().ifEmpty { KVUtils.getApiKeyForProvider(provider) }
        return apiKey.isNotEmpty()
    }
}
