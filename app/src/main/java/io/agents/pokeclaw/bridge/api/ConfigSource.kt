package io.agents.pokeclaw.bridge.api

/**
 * Bridge 的运行参数来源。
 *
 * 调用约定：
 *  - [load] 在 Bridge dispatcher 线程上被调用；实现方可直接同步读 MMKV / SharedPreferences。
 *  - 返回 null 表示 "未配置"；Bridge 见到任一关键字段（serverUrl 或 deviceToken）为空
 *    会保持 DISCONNECTED 状态，不发起连接（需求 10.2）。
 */
interface ConfigSource {
    fun load(): BridgeConfig?
}

/**
 * @param serverUrl 例如 "wss://bridge.pokeclaw.dev/ws/device"，须以 "wss://" 或 "ws://" 开头。
 * @param deviceToken Bearer token；Bridge 绝不会在日志里原文输出这个值（需求 10.4）。
 * @param advertisedCapabilities 启动时向云端广播的 kind 列表。
 */
data class BridgeConfig(
    val serverUrl: String,
    val deviceToken: String,
    val advertisedCapabilities: List<String>,
)
