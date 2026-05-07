package io.agents.pokeclaw.bridge.api

/**
 * 设备能力快照提供者。
 *
 * 调用约定：
 *  - [currentSnapshot] 在 Bridge dispatcher 线程上被调用，实现方应该是非阻塞的。
 *  - 每次 Bridge 需要做能力决策（发 hello、接 task.dispatch 的前置检查）都会重新调用。
 */
interface CapabilityProvider {
    fun currentSnapshot(): CapabilitySnapshot
}

/**
 * @param supportedKinds 向云端广播的 kind 列表（hello.payload.capabilities 来源）。
 * @param accessibilityReady 无障碍服务是否已连接。
 * @param installedTargetApps kind → 目标 app 是否已安装；用于 task.dispatch 前置检查。
 * @param batteryLevel 0.0~1.0，未知时为 null。
 * @param charging 未知时为 null。
 */
data class CapabilitySnapshot(
    val supportedKinds: List<String>,
    val accessibilityReady: Boolean,
    val installedTargetApps: Map<String, Boolean>,
    val batteryLevel: Double?,
    val charging: Boolean?,
)
