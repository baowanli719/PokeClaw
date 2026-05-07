package io.agents.pokeclaw.bridge.api

/**
 * 日志输出适配器。Bridge 不直接依赖 XLog / Logcat / Timber。
 *
 * 调用约定：
 *  - 方法可能在 Bridge dispatcher 线程或 OkHttp 回调线程上被调用；实现方应该是**非阻塞**的。
 *  - Bridge 绝不会把 Device_Token 原文传进来；但实现方仍应自行 mask 敏感字段。
 */
interface BridgeLogger {
    fun d(tag: String, message: String)
    fun i(tag: String, message: String)
    fun w(tag: String, message: String, throwable: Throwable? = null)
    fun e(tag: String, message: String, throwable: Throwable? = null)
}
