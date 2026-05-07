# Implementation Tasks

## 概览

本计划把 Android Cloud Bridge 拆解为可按顺序实现、可独立编译与测试的任务。Bridge 位于独立 package `io.agents.pokeclaw.bridge`，通过 `TaskExecutor` / `CapabilityProvider` / `ConfigSource` / `BridgeLogger` 四个接口与宿主 app 解耦。任务按设计文档的 package 结构推进（internal → api → protocol → connection → task → queue → facade → adapter → 静态检查 → 集成测试 → 文档）。

- 所有核心实现任务（`- [ ]`）不可跳过；测试类子任务（`- [ ]*`）为可选，可在 MVP 阶段略过。
- 属性测试子任务显式引用 design §10 Correctness Properties 的编号（Property 1~10）。
- 顶层任务之间设置 checkpoint，用于保证增量可验证。

## Tasks


- [x] 1. 建立 bridge package 骨架与内部基础设施
  - [x] 1.1 创建 bridge package 目录结构
    - 新建 `app/src/main/java/io/agents/pokeclaw/bridge/` 及子目录 `api/`、`protocol/`、`connection/`、`task/`、`queue/`、`internal/`
    - 新建对应测试目录 `app/src/test/java/io/agents/pokeclaw/bridge/` 镜像子目录
    - 每个子目录放一个 `package-info.kt`（或空 `.gitkeep`）确保目录可被 git 追踪
    - _Requirements: 1.1_

  - [x] 1.2 引入测试依赖（jqwik + MockWebServer + AssertJ）
    - 修改 `app/build.gradle.kts` 的 `dependencies` 块，添加 `testImplementation`：jqwik、okhttp-mockwebserver、assertj-core
    - 确认 `junit` 已存在；不引入 android-specific 依赖
    - _Requirements: 1.6_

  - [x] 1.3 实现 `internal/Clock.kt`
    - 定义 `interface Clock { fun nowMillis(): Long; fun monotonicMillis(): Long }`
    - 提供默认实现 `SystemClock` 调用 `System.currentTimeMillis()` 与 `SystemClock.elapsedRealtime()` 的 JVM 等价 `System.nanoTime()/1_000_000`
    - 预留 `FakeClock` 供测试使用（放在 `test/` 目录）
    - _Requirements: 3.1, 3.4, 5.8_

  - [x] 1.4 实现 `internal/BridgeDispatcher.kt`
    - 封装 `Executors.newSingleThreadScheduledExecutor { Thread(it, "bridge-dispatcher").apply { isDaemon = true } }`
    - 暴露 `execute(Runnable)`、`schedule(delayMs, Runnable): ScheduledFuture<*>`、`submit(Callable<T>): Future<T>`、`shutdown()`
    - 每个 runnable 用顶层 try/catch 包裹，异常走注入的 `BridgeLogger.e(...)` 不再上抛（需求 9.5 基础）
    - _Requirements: 7.6, 9.5_

  - [x] 1.5 为 BridgeDispatcher 写单元测试
    - 验证所有 runnable 串行执行（单线程不变量）
    - 验证 runnable 抛异常不会让 dispatcher 死亡，后续任务仍能调度
    - _Requirements: 7.6, 9.5_

- [x] 2. 定义注入接口（bridge.api 子包）
  - [x] 2.1 实现 `api/TaskExecutor.kt`
    - 定义 `interface TaskExecutor { fun execute(...) : TaskHandle; fun cancel(requestId: String) }`
    - 定义 `interface TaskHandle { val requestId: String; fun isActive(): Boolean }`
    - 定义 `interface TaskExecutorCallback { onAccepted / onProgress / onResult / onError }`
    - 所有 result/params 使用 `com.google.gson.JsonObject`
    - _Requirements: 1.2, 1.4, 5.2, 5.4, 5.5, 5.6, 5.7_

  - [x] 2.2 实现 `api/CapabilityProvider.kt`
    - 定义 `interface CapabilityProvider { fun currentSnapshot(): CapabilitySnapshot }`
    - 定义 `data class CapabilitySnapshot(supportedKinds, accessibilityReady, installedTargetApps, batteryLevel, charging)`
    - _Requirements: 1.2, 6.1, 6.2, 6.3, 6.4_

  - [x] 2.3 实现 `api/ConfigSource.kt`
    - 定义 `interface ConfigSource { fun load(): BridgeConfig? }`
    - 定义 `data class BridgeConfig(serverUrl, deviceToken, advertisedCapabilities)`
    - 在 KDoc 中声明：`deviceToken` 绝不会被 Bridge 原文写入日志（需求 10.4）
    - _Requirements: 1.2, 10.1, 10.2_

  - [x] 2.4 实现 `api/BridgeLogger.kt`
    - 定义 `interface BridgeLogger { d / i / w / e(tag, message, throwable?) }`
    - 不导入任何具体日志实现
    - _Requirements: 1.2, 11.5_

  - [x] 2.5 为四个接口写示例 fake 实现（放在 test 目录）
    - `FakeTaskExecutor`（可编程回调序列、记录 execute/cancel 调用）
    - `FakeCapabilityProvider`（构造时传入 snapshot）
    - `FakeConfigSource`（构造时传入 `BridgeConfig?`）
    - `CapturingBridgeLogger`（记录所有日志调用供断言）
    - 这些 fake 后续被 TaskBridge / FrameCodec / ConnectionManager 的单元测试共享
    - _Requirements: 1.6_

- [x] 3. 协议层：Frame 数据模型与 Codec
  - [x] 3.1 实现 `protocol/Payloads.kt`
    - 定义所有 payload data class：`HelloPayload`、`HelloAckPayload`、`HeartbeatPayload`、`TaskDispatchPayload`、`TaskAcceptedPayload`、`TaskProgressPayload`、`TaskResultPayload`、`TaskErrorPayload`、`TaskCancelPayload`
    - 字段顺序、可空性、默认值严格遵守 design §"Frame Data Model" 的定义
    - _Requirements: 8.1_

  - [x] 3.2 实现 `protocol/Frame.kt`
    - 定义 `sealed class Frame`，每种帧类型为一个 `data class` 子类（Hello / Heartbeat / TaskAccepted / TaskProgress / TaskResult / TaskError / Pong / HelloAck / TaskDispatch / TaskCancel / Ping）
    - 定义两个容错占位子类 `Frame.Unknown(type, id, ts, payload)` 与 `Frame.ParseError(raw, cause)`
    - 每个子类覆写 `override val type: String` 为协议字符串常量
    - _Requirements: 8.1, 8.3, 8.4_

  - [x] 3.3 实现 `protocol/FrameCodec.kt`
    - 定义内部 envelope data class `RawFrame(type, id?, ts, payload: JsonObject)`
    - `encode(frame: Frame): String`：对非 Unknown/ParseError 的 Frame 通过 Gson 生成规范 JSON（envelope 固定字段 + payload）
    - `decode(text: String): Frame`：
      - Gson 解析 envelope 失败 → 返回 `Frame.ParseError(text, cause)`
      - `type` 未命中已知集合 → 返回 `Frame.Unknown(type, id, ts, payload)`
      - payload 字段缺失/类型不符 → 返回 `Frame.ParseError(text, cause)`
      - 任何抛出的异常在顶层 catch 转 ParseError，**绝不抛到调用方**
    - 复用单一 `Gson` 实例；必要时注册自定义 `TypeAdapter`
    - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5_

  - [x] 3.4 FrameCodec 的 example 单元测试
    - 对每种 outbound Frame 写序列化 example（断言 `type`、`ts`、payload 字段形状符合 protocol.md）
    - 对每种 inbound Frame 写反序列化 example
    - 断言 envelope 中 `type="task.dispatch"` 且 payload 合法时返回具体 `Frame.TaskDispatch`
    - _Requirements: 8.1, 8.2_

  - [x] 3.5 FrameCodec 属性测试（Property 1 / Property 2）
    - **Property 1: Frame round-trip**
    - **Validates: Requirements 8.1, 8.5**
    - 用 jqwik 生成任意合法 Frame（sealed class 子类并集，排除 Unknown/ParseError），断言 `decode(encode(f)) == f`
    - **Property 2: Codec never throws**
    - **Validates: Requirements 8.3, 8.4, 9.5**
    - 用 jqwik 生成任意字符串（包括非 ASCII、控制字符、`\u0000`、长达 10k 字符的输入），断言 `FrameCodec.decode(s)` 不抛异常，结果为 `Frame` 具体子类、`Frame.Unknown` 或 `Frame.ParseError`
    - _Requirements: 8.1, 8.3, 8.4, 8.5, 9.5_

- [x] 4. Checkpoint — 协议层就绪
  - 确保 FrameCodec 所有测试通过，ensure all tests pass, ask the user if questions arise.

- [x] 5. 连接层基础：状态枚举 + BackoffPolicy + NetworkMonitor
  - [x] 5.1 实现 `ConnectionState.kt`
    - 定义 `sealed class ConnectionState`：`Disconnected` / `Connecting` / `Connected` / `Authenticated` / `Stopped(reason: StopReason)`
    - 定义 `enum class StopReason { USER_STOPPED, AUTH_FAILED, REPLACED }`
    - _Requirements: 4.2, 4.3, 4.5, 7.2, 7.4, 10.2_

  - [x] 5.2 实现 `connection/BackoffPolicy.kt`
    - 构造参数 `initialMs = 1000L`、`maxMs = 60_000L`
    - `nextDelayMs()`：返回 currentMs，并把 currentMs 翻倍至 maxMs 上限
    - `reset()`：恢复到 initialMs
    - 保证序列 `1s → 2s → 4s → 8s → 16s → 32s → 60s → 60s → …`
    - _Requirements: 4.1, 4.6_

  - [x] 5.3 BackoffPolicy 属性测试（Property 4）
    - **Property 4: Backoff is monotonic, bounded, and resettable**
    - **Validates: Requirements 4.1, 4.4, 4.6**
    - 用 jqwik 生成 0..200 次连续 `nextDelayMs()` 调用，断言序列单调非减且 ≤ 60000
    - 生成随机位置插入 `reset()`，断言 reset 后第一次 `nextDelayMs()` = 1000
    - _Requirements: 4.1, 4.4, 4.6_

  - [x] 5.4 实现 `connection/NetworkMonitor.kt`
    - 定义 `interface NetworkMonitor { fun start(listener: Listener); fun stop() }`，`Listener.onAvailable()` / `onLost()`
    - 默认实现 `AndroidNetworkMonitor(context: Context)` 包装 `ConnectivityManager.NetworkCallback`
    - 回调通过注入的 `BridgeDispatcher` post 到 bridge 线程再触发 listener
    - 预留测试替身 `FakeNetworkMonitor`（JVM 测试）
    - _Requirements: 4.4_

- [x] 6. 连接层核心：ConnectionManager + HeartbeatScheduler
  - [x] 6.1 实现 `connection/ConnectionManager.kt` —— WebSocket 生命周期
    - 构造参数：`OkHttpClient`、`BridgeDispatcher`、`BackoffPolicy`、`NetworkMonitor`、`FrameCodec`、`BridgeLogger`、`Clock`
    - `connect(config: BridgeConfig, deviceId, appVersion)`：
      - 构造 Request：URL = `<serverUrl>?device_id=<id>&app_version=<ver>`，Header `Authorization: Bearer <token>`
      - 调用 `okHttpClient.newWebSocket(request, listener)`
      - OkHttp listener 回调全部 `dispatcher.execute { ... }` 后处理
    - `sendFrame(frame: Frame): Boolean`：通过 codec.encode 发送，返回是否成功（连接关闭/队列满返回 false）
    - `close(code: Int, reason: String)`：主动关闭
    - _Requirements: 2.1, 2.5, 2.6, 4.5_

  - [x] 6.2 ConnectionManager 实现 hello 握手与 10s 超时
    - `onOpen` 后立即切 state 到 `Connected`，调用方随后通过 `sendHello(capabilitySnapshot)` 发出 `hello`
    - 发 hello 后 `dispatcher.schedule(10_000)` 注册超时任务：到期仍非 `Authenticated` 则 `close(1001, "hello_timeout")` 触发重连
    - 收到 `Frame.HelloAck` 后取消超时任务，回调上层进入 `Authenticated`，并把 `heartbeat_sec` / `accepted_capabilities` 传出
    - _Requirements: 2.2, 2.3, 2.4_

  - [x] 6.3 ConnectionManager 实现 stale 连接检测（90s）
    - 每次收到任意入站 frame 时更新 `lastServerFrameMs = clock.monotonicMillis()`
    - 启动一个周期 5s 的 `dispatcher.schedule` 扫描：若 `now - lastServerFrameMs > 90_000` 则 `close(1001, "stale")` 触发重连
    - _Requirements: 3.4_

  - [x] 6.4 ConnectionManager 实现 close code 路由与重连调度
    - 在 `onClosed` / `onFailure`：
      - code `1000` + 来源为 stop() → 终态 `Stopped(USER_STOPPED)`，不再重连
      - code `4401` → 终态 `Stopped(REPLACED)`
      - code `4403` → 终态 `Stopped(AUTH_FAILED)`
      - 其他 → `dispatcher.schedule(backoffPolicy.nextDelayMs()) { connect(...) }`，保持 state = `Connecting`
    - 接入 `NetworkMonitor.onAvailable`：调用 `backoffPolicy.reset()` 并立即 `connect(...)`
    - 握手成功（`hello.ack` 到达）时调用 `backoffPolicy.reset()`
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6_

  - [x] 6.5 实现 `connection/HeartbeatScheduler.kt`
    - 构造参数：`BridgeDispatcher`、`Clock`、`(busy: Boolean, currentRequestId: String?) -> Unit` 的 tick 回调
    - `start(heartbeatSec: Int)`：每 `heartbeatSec * 1000` ms `dispatcher.schedule` 一次；调用方在 tick 里组装 `Frame.Heartbeat` 经 `ConnectionManager.sendFrame` 发出
    - 处理入站 ping：暴露 `onPing(id: String?)` 立即调度一次 `Frame.Pong(id)` 发送
    - `stop()`：取消定时器，取消正在等待的 schedule future
    - _Requirements: 3.1, 3.2, 3.3_

  - [x] 6.6 HeartbeatScheduler 属性测试（Property 5）
    - **Property 5: Heartbeat period matches negotiated interval**
    - **Validates: Requirements 3.1**
    - 用 `FakeClock` + 捕获 dispatcher，跑 jqwik 在 `heartbeatSec ∈ [5, 300]` 上验证连续 heartbeat 帧的间隔在 `[H·1000 − 1000 ms, H·1000 + 1000 ms]`
    - _Requirements: 3.1_

  - [x] 6.7 ConnectionManager 属性测试（Property 3）
    - **Property 3: Connection state machine legality**
    - **Validates: Requirements 2.3, 4.5, 7.2, 7.4, 10.2**
    - 用 jqwik 生成任意事件序列 `(start/stop/onOpen/onClose(code)/onHelloAck/networkOnAvailable/helloTimeout/staleTimeout)*`
    - 驱动 ConnectionManager + FakeWebSocket，断言相邻状态转换都属于 design §10 列出的合法转换集合；若序列以 `stop()` 结尾则终态为 `Stopped(USER_STOPPED)`
    - _Requirements: 2.3, 4.5, 7.2, 7.4, 10.2_

  - [x] 6.8 ConnectionManager 的 example 单元测试
    - hello.ack 10s 超时 → close + backoff 重连
    - 90s 无帧 → 主动关闭
    - close 4403 → `Stopped(AUTH_FAILED)` 且不再重连
    - close 4401 → `Stopped(REPLACED)` 且不再重连
    - NetworkMonitor.onAvailable → backoff 立即重置、触发重连尝试
    - _Requirements: 2.4, 3.4, 4.2, 4.3, 4.4_

- [x] 7. 任务层：TaskBridge + InFlightTask
  - [x] 7.1 实现 `task/InFlightTask.kt`
    - 定义 `data class InFlightTask(requestId, kind, params, deadlineTsMillis?, acceptedAtMs, deadlineFuture: ScheduledFuture<*>?, terminalSent: Boolean)`
    - 只被 TaskBridge 在 dispatcher 线程上读写，不需要并发结构
    - _Requirements: 5.1, 5.8, 9.3_

  - [x] 7.2 实现 `task/TaskBridge.kt` —— dispatch 路由与前置检查
    - 构造参数：`BridgeDispatcher`、`CapabilityProvider`、`TaskExecutor`、`Clock`、`OfflineOutbox`、`BridgeLogger`、`sendFrame: (Frame) -> Boolean`、`currentCapabilities: () -> List<String>`
    - `onDispatchFrame(frame: Frame.TaskDispatch)` 在 dispatcher 线程执行：
      - 若 `inFlight != null` → 发 `task.error(code=internal, "device busy", retryable=true)`（需求 9.3）
      - 否则调 `capabilityProvider.currentSnapshot()` 做前置检查：
        - `kind` 不在 `supportedKinds` → `task.error(code=internal, "unsupported capability: $kind", retryable=false)`
        - `installedTargetApps[kind] == false` → `task.error(code=app_not_installed, retryable=false)`
        - `accessibilityReady == false` → `task.error(code=accessibility_not_ready, retryable=false)`
      - 全部通过 → 调用 `outbox.enqueueAndSend(Frame.TaskAccepted(...))`，然后 `taskExecutor.execute(...)`
    - _Requirements: 5.1, 5.2, 5.3, 6.4, 9.3_

  - [x] 7.3 TaskBridge 桥接 executor 回调到 frame 流
    - 实现一个内部的 `TaskExecutorCallback` 包装：每次回调 `dispatcher.execute { ... }` 后：
      - `onAccepted(requestId)` → 幂等确认（accepted 已在 7.2 发过，此处只记日志）
      - `onProgress(requestId, step, ratio)` → `sendFrame(Frame.TaskProgress)`（发送失败静默丢弃，**不** 入 outbox）
      - `onResult(requestId, kind, result)` → 若 `inFlight.terminalSent == false` → `outbox.enqueueAndSend(Frame.TaskResult)`，置 `terminalSent=true`，清理 `inFlight`，取消 deadline 定时器
      - `onError(requestId, code, message, retryable)` → 同 result 的终态处理流程，产出 `Frame.TaskError`
      - 对同一 requestId 的重复终态事件：记 warning，丢弃（需求 5.5/5.6 边界）
    - _Requirements: 5.2, 5.4, 5.5, 5.6_

  - [x] 7.4 TaskBridge 实现 deadline 监控
    - 在 `onDispatchFrame` 中若 `deadline_ts != null` → `dispatcher.schedule(deadline_ts - clock.nowMillis()) { ... }`
    - 到期回调：若 `inFlight?.requestId` 仍匹配且 `terminalSent==false` → `taskExecutor.cancel(requestId)` 并 `outbox.enqueueAndSend(Frame.TaskError(code=deadline_exceeded, retryable=false))`
    - _Requirements: 5.8_

  - [x] 7.5 TaskBridge 处理入站 task.cancel
    - `onCancelFrame(frame: Frame.TaskCancel)`：若 `inFlight?.requestId == frame.payload.request_id` → `taskExecutor.cancel(requestId)`
    - 不直接发 error 帧；等待 executor 回调 `onError(code="cancelled")` 走 7.3 的终态路径
    - _Requirements: 5.7_

  - [x] 7.6 TaskBridge 暴露状态给 HeartbeatScheduler
    - 提供 `currentBusy(): Boolean`、`currentRequestId(): String?`，均在 dispatcher 线程调用
    - 供 `HeartbeatScheduler` tick 时读取 payload
    - _Requirements: 3.2_

  - [x] 7.7 TaskBridge 属性测试（Property 6）
    - **Property 6: Single in-flight task invariant**
    - **Validates: Requirements 5.1, 9.3**
    - 用 jqwik 生成任意 distinct-requestId 的 task.dispatch 帧序列（穿插 executor onResult/onError 事件）
    - 断言任何时刻 "已发 accepted 但未发 result/error" 的 requestId 集合大小 ≤ 1
    - 断言 busy 状态下所有新 dispatch 都只收到 `task.error(internal, "busy", retryable=true)`，且 `taskExecutor.execute` 未被调用
    - _Requirements: 5.1, 9.3_

  - [x] 7.8 TaskBridge 属性测试（Property 7）
    - **Property 7: Task frame stream mirrors executor callbacks**
    - **Validates: Requirements 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 5.7, 5.8, 6.4**
    - 用 jqwik 生成合法 executor 回调序列 `onAccepted?, onProgress*, (onResult | onError)`
    - 断言 Bridge 发出的帧序列为 `task.accepted, task.progress*, (task.result | task.error)`，且 request_id / payload 字段匹配
    - 生成 `task.cancel` 或 `deadline` 到期事件 → 断言终态帧 code 为 `cancelled` 或 `deadline_exceeded`
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 5.7, 5.8, 6.4_

  - [x] 7.9 TaskBridge example 单元测试
    - busy 拒绝新 dispatch
    - accessibility_not_ready / app_not_installed / unsupported capability 分别命中对应 error code
    - 正常派发：accepted → progress → result 全部发出
    - deadline 到期发出 deadline_exceeded
    - cancel 帧触发 executor.cancel
    - _Requirements: 5.1, 5.2, 5.3, 5.7, 5.8, 6.4_

- [x] 8. 离线队列：OfflineOutbox
  - [x] 8.1 实现 `queue/OfflineOutbox.kt`
    - 构造参数：`filesDir: File`、`FrameCodec`、`BridgeLogger`、`BridgeDispatcher`、`sendFrame: (Frame) -> Boolean`
    - 存储路径：`filesDir/bridge/outbox.jsonl`，每行 `{"enqueued_at": <ms>, "frame": <encoded frame JSON>}`
    - `enqueueAndSend(frame: Frame)`：
      - 如果当前处于 `Authenticated` 且 outbox 空 → 直接 `sendFrame(frame)`，失败才落盘
      - `task.progress` **绝不落盘**（终态帧才入 outbox）
      - 落盘后若超过上限 200 条 → drop-oldest（重写文件或按行数删首行）
    - `drain()`：按插入顺序逐条 `sendFrame`，成功一条删一条；发送失败立即停止，保留剩余
    - 启动时若文件存在：按行读取，解析失败的行 log warning 后跳过
    - 所有操作在 `BridgeDispatcher` 线程上执行，无需跨线程锁
    - _Requirements: 9.1, 9.4_

  - [x] 8.2 OfflineOutbox example 单元测试
    - FIFO 顺序：多次 enqueue 后 drain 应按插入顺序发出
    - drop-oldest 边界：enqueue 199 / 200 / 201 条，断言文件行数与内容
    - 跨进程持久化：写入后 new 一个 OfflineOutbox 从相同目录读，断言内容一致
    - drain 中途 send 失败（模拟 sendFrame 返回 false）→ 剩余帧保留
    - `task.progress` 尝试入队时应被忽略（不写文件）
    - _Requirements: 9.1, 9.4_

  - [x] 8.3 OfflineOutbox 属性测试（Property 8）
    - **Property 8: Offline outbox is FIFO, bounded, and drained on reconnect**
    - **Validates: Requirements 9.1, 9.4**
    - 用 jqwik 生成任意终态 frame 序列（`n ∈ [0, 500]`）
    - 断言：`|outbox| ≤ 200`；`n ≤ 200` 时内容 = F 顺序；`n > 200` 时内容 = F 的最后 200 条；drain 后 outbox 为空且云端按序接收；`task.progress` 永不入 outbox
    - _Requirements: 9.1, 9.4_

- [x] 9. Checkpoint — 连接层与任务层就绪
  - 确保 FrameCodec / BackoffPolicy / ConnectionManager / TaskBridge / OfflineOutbox 所有测试通过，ensure all tests pass, ask the user if questions arise.

- [x] 10. 门面层：CloudBridgeClient
  - [x] 10.1 实现 `CloudBridgeClient.kt` 门面
    - 构造参数：`configSource, capabilityProvider, taskExecutor, logger, deviceId, appVersion, filesDir`
    - 内部组合：`BridgeDispatcher`、`OkHttpClient`、`ConnectionManager`、`HeartbeatScheduler`、`TaskBridge`、`OfflineOutbox`
    - `private val state = MutableStateFlow<ConnectionState>(Disconnected)`，`observeState()` 返回只读 `StateFlow`
    - 所有状态修改通过 `dispatcher.execute { state.value = ... }`
    - _Requirements: 7.1, 7.5_

  - [x] 10.2 CloudBridgeClient 实现 start() / stop() / reconfigure()
    - `start()`：幂等；`configSource.load()` 为 null 或关键字段空 → 保持 `Disconnected` 并 log warning（需求 10.2）；否则触发 `connectionManager.connect(...)` 并置 state = `Connecting`
    - `stop()`：幂等；若当前有 in-flight task → `taskExecutor.cancel(requestId)`；`connectionManager.close(1000, "user_stopped")`；取消 backoff/heartbeat/deadline 定时器；state → `Stopped(USER_STOPPED)`
    - `reconfigure()`：重新读 `configSource.load()`；若 serverUrl/token/capabilities 变化则 close 当前连接 + 重建连接
    - 所有外部入口 post 到 dispatcher 后用 `Future.get()` 等待完成（调用方看到同步语义）
    - _Requirements: 7.1, 7.2, 7.3, 10.3_

  - [x] 10.3 CloudBridgeClient 接线状态转换与回调路由
    - 把 ConnectionManager 的 `onAuthenticated(heartbeatSec)` 路由到：state → Authenticated；启动 `HeartbeatScheduler`；调用 `OfflineOutbox.drain()`
    - 把入站 `Frame.TaskDispatch` / `Frame.TaskCancel` / `Frame.Ping` 路由到 TaskBridge / HeartbeatScheduler
    - 把入站 `Frame.Unknown` / `Frame.ParseError` 记 warning 后丢弃（**不** 断开连接，需求 8.3 / 8.4）
    - 退出 Authenticated 时停 HeartbeatScheduler
    - _Requirements: 3.3, 4.5, 4.6, 8.3, 8.4_

  - [x] 10.4 CloudBridgeClient 实现 hello 组装
    - 在 `ConnectionManager` 通知 `onOpen` 后读取 `capabilityProvider.currentSnapshot()` 与 `config.advertisedCapabilities`，合成 `HelloPayload(device_id, app_version, os="android", os_version, capabilities, battery, charging)`
    - 发出 `Frame.Hello(ts = clock.nowMillis(), payload)` 后启动 10s hello.ack 超时（由 ConnectionManager 托管）
    - _Requirements: 2.2, 6.1, 6.2, 6.3_

  - [x] 10.5 CloudBridgeClient 实现日志脱敏
    - 在任何路径上记 `deviceToken` 时，只输出 `***${token.takeLast(4)}` 形式
    - Provide a private extension `String.masked()` used consistently
    - _Requirements: 10.4, 11.5_

  - [x] 10.6 实现日志埋点
    - 连接状态转换：Connecting → Connected → Authenticated（带时间戳）
    - 发送/接收 frame 的 type（不记 payload 明文）
    - 任务生命周期：dispatch 收到 / accepted / progress / result / error（带 requestId）
    - 重连尝试：打印当前 backoff 延迟
    - 所有日志通过 `BridgeLogger`
    - _Requirements: 11.1, 11.2, 11.3, 11.4, 11.5_

  - [x] 10.7 CloudBridgeClient 属性测试（Property 9）
    - **Property 9: Public API never propagates internal exceptions**
    - **Validates: Requirements 9.5**
    - 用 jqwik 生成：畸形 frame 输入、抛异常的 FakeTaskExecutor、返回畸形值的 FakeConfigSource、IO 异常的 FakeOutbox、抛异常的 FakeBridgeLogger
    - 断言 `start() / stop() / reconfigure() / currentState() / observeState()` 都不抛异常，且异常通过 logger.e 报告
    - _Requirements: 9.5_

  - [x] 10.8 CloudBridgeClient 属性测试（Property 10）
    - **Property 10: Token is never logged in cleartext**
    - **Validates: Requirements 10.4**
    - 用 jqwik 生成 deviceToken（长度 ≥ 8 的随机字符串），让 Bridge 跑完整场景（start/hello/dispatch/stop 等）
    - 断言 `CapturingBridgeLogger` 记录的所有消息中不含 token 原文作为子串
    - _Requirements: 10.4_

  - [x] 10.9 CloudBridgeClient example 单元测试
    - ConfigSource 返回 null → 保持 Disconnected，不建连（需求 10.2）
    - start() 后 stop() 若有 in-flight 任务则先 cancel（需求 7.3）
    - reconfigure() 改变 URL 后重建连接
    - observeState() 返回的 Flow 能观察到完整状态序列
    - _Requirements: 7.1, 7.2, 7.3, 7.5, 10.2, 10.3_

- [x] 11. Checkpoint — Bridge 内部全部就绪
  - 确保 Bridge 所有单元测试与属性测试通过（可选测试按需跳过），ensure all tests pass, ask the user if questions arise.

- [x] 12. 宿主 app 适配器（位于 `io.agents.pokeclaw.cloudbridge` 外部 package）
  - [x] 12.1 实现 `cloudbridge/KVUtilsConfigSource.kt`
    - 读取 `KVUtils` 中的 `cloud_bridge_url`、`cloud_bridge_device_token`
    - 任一为空则 `load()` 返回 null
    - `advertisedCapabilities` 固定为 `listOf("ths.sync_holdings")`（v1）
    - _Requirements: 1.4, 1.5, 10.1, 10.2_

  - [x] 12.2 实现 `cloudbridge/XLogBridgeLogger.kt`
    - 把 `BridgeLogger` d/i/w/e 路由到 `XLog.d/i/w/e`，tag 前缀 `Bridge.`
    - _Requirements: 1.4, 1.5, 11.5_

  - [x] 12.3 实现 `cloudbridge/AppCapabilityProviderAdapter.kt`
    - 调用 `AppCapabilityCoordinator.snapshot(context)` 判断 `accessibilityReady`
    - 读 `isAppInstalled(context, "com.hexin.plat.android")` 判断 THS 是否安装 → 填 `installedTargetApps["ths.sync_holdings"]`
    - 实现 `readBatteryLevel(context)` / `readCharging(context)`（通过 `BatteryManager` 或 `Context.registerReceiver(ACTION_BATTERY_CHANGED)`）
    - 构造时接收 `staticSupportedKinds: List<String>` 作为 hello 广播内容
    - _Requirements: 1.4, 1.5, 6.1, 6.2, 6.3_

  - [x] 12.4 扩展 `TaskOrchestrator` 与相关类型以支持 cloud channel
    - 在 `io.agents.pokeclaw.agent`（或现有 Channel 枚举所在位置）新增 `Channel.CLOUD`
    - `ChannelManager.sendMessage(Channel.CLOUD, ...)` 实现为 no-op（不走文本 IM 回显）
    - 保证现有 LOCAL / 其他 channel 行为不受影响
    - _Requirements: 1.4_

  - [x] 12.5 实现 `cloudbridge/CloudKindMapper.kt`
    - v1 支持单一 kind `ths.sync_holdings`，合成 task 文本 `"/ths sync_holdings <params_json>"`
    - 预留后续 registry 扩展点（interface + 默认实现）
    - _Requirements: 5.2_

  - [x] 12.6 实现 `cloudbridge/CloudTaskResultSink.kt`
    - 定义 `interface CloudTaskResultSink { fun register(requestId, kind, callback); fun submitResult(requestId, JsonObject); fun clear(requestId) }`
    - 提供默认实现：以 requestId 为 key 的 `ConcurrentHashMap<String, Pair<String, TaskExecutorCallback>>`
    - 由 skill / playbook 的最后一步调用 `submitResult(requestId, result)` 把结构化 JSON 推给 adapter
    - _Requirements: 5.5_

  - [x] 12.7 实现 `cloudbridge/TaskOrchestratorExecutorAdapter.kt`
    - 构造参数：`TaskOrchestrator`、`CloudTaskResultSink`
    - `execute(...)`：
      - `resultSink.register(requestId, kind, callback)`
      - `val taskText = CloudKindMapper.toTaskText(kind, params)`
      - 订阅 `TaskOrchestrator.taskEventCallback`：用 `CloudTaskEventBridge` 过滤 `channel == CLOUD && messageId == requestId`，再转 `onProgress/onError`
      - `orchestrator.startNewTask(Channel.CLOUD, taskText, requestId)`
      - 立即 `callback.onAccepted(requestId)`
      - 返回 `TaskHandle`：`isActive() = orchestrator.inProgressTaskMessageId == requestId`
    - `cancel(requestId)`：若当前 inProgress 匹配 → `orchestrator.cancelCurrentTask()`
    - _Requirements: 1.4, 1.5, 5.2, 5.4, 5.5, 5.6, 5.7_

  - [x] 12.8 在 `ClawApplication.onCreate` 的 async-init 中装配 CloudBridgeClient
    - 构造四个 adapter + CloudBridgeClient，通过单例 `CloudBridgeHolder.client` 持有
    - **不** 在 `onCreate` 里调 start()
    - _Requirements: 1.5, 7.7_

  - [x] 12.9 在 `ForegroundService` 生命周期中调用 start/stop
    - `ForegroundService.onStartCommand`（成功进入前台后）→ `CloudBridgeHolder.client.start()`
    - `ForegroundService.onDestroy` → `CloudBridgeHolder.client.stop()`
    - 确认 Bridge package 内 **无** 对 `ForegroundService` 的 import（需求 7.7）
    - _Requirements: 7.1, 7.2, 7.7_

  - [x] 12.10 为 `TaskOrchestratorExecutorAdapter` 写单元测试
    - 用 fake `TaskOrchestrator` 验证 execute → startNewTask 被调用，channel = CLOUD，messageId = requestId
    - 验证 TaskEvent 的 Progress/Completed/Failed/Cancelled 正确映射到 TaskExecutorCallback
    - 验证 `cancel(requestId)` 在非当前任务时为 no-op
    - _Requirements: 1.4, 1.5, 5.4, 5.5, 5.6, 5.7_

  - [x] 12.11（可选）配置 UI 入口
    - 在现有 Setting 页面添加 `cloud_bridge_url` / `cloud_bridge_device_token` 读写入口
    - 修改保存后调用 `CloudBridgeHolder.client.reconfigure()`
    - _Requirements: 10.3_

- [x] 13. 静态边界检查（detekt 自定义规则）
  - [x] 13.1 引入 detekt 插件
    - 在 root `build.gradle.kts` 或 app `build.gradle.kts` 添加 `io.gitlab.arturbosch.detekt` 插件与基础配置
    - 创建 `config/detekt/detekt.yml`（关闭大部分默认规则，只启用自定义 rule set）
    - _Requirements: 1.3_

  - [x] 13.2 实现自定义规则 `BridgePackageImportBoundary`
    - 新建 `buildSrc/` 或 detekt custom rule 模块，实现 `Rule`：
      - 扫描路径在 `io/agents/pokeclaw/bridge/` 下的所有 `.kt` 文件
      - 若文件存在 `import io.agents.pokeclaw.(?!bridge\.).*` 形式的 import → 报告违规
    - 把规则注册进 detekt 的 `RuleSetProvider`
    - _Requirements: 1.3_

  - [x] 13.3 在 CI 中运行 detekt
    - 在 `.github/workflows/` 的对应 workflow 里加一步 `./gradlew detekt`
    - 失败时阻塞 PR
    - _Requirements: 1.3_

- [x] 14. 集成测试：OkHttp MockWebServer 端到端场景
  - [x] 14.1 搭建 MockWebServer + WebSocket 测试基设
    - 在 `app/src/test/java/io/agents/pokeclaw/bridge/integration/` 下建测试基类：启动 `MockWebServer` + `MockResponse().withWebSocketUpgrade(serverListener)`，暴露 outbound 帧断言工具
    - 构造真实 `CloudBridgeClient`（注入 fake `TaskExecutor` / `CapabilityProvider` / `ConfigSource` / `BridgeLogger`）
    - _Requirements: 2.6, 8.2_

  - [x] 14.2 握手 + task 派发端到端场景
    - 场景：client start → 收到 hello → 服务端回 hello.ack → 服务端派发 task.dispatch → 客户端发 task.accepted → fake executor 回 onResult → 客户端发 task.result
    - _Requirements: 2.1, 2.2, 2.3, 5.1, 5.2, 5.5_

  - [x] 14.3 认证失败（4403）场景
    - 服务端在 hello 后立即 close(4403) → 断言客户端进入 `Stopped(AUTH_FAILED)`，后续 60s 内 **不** 再发起任何连接
    - _Requirements: 4.2_

  - [x] 14.4 断线重连 + outbox 重放场景
    - 场景：连接成功 → 派发任务 → 结果产出前服务端 abrupt 关闭 → 客户端 backoff 重连 → 新连接握手成功 → outbox 中的 task.result 按序重放
    - _Requirements: 4.1, 4.6, 9.1, 9.4_

  - [x] 14.5 被替换（4401）场景
    - 服务端发送 close(4401) → 断言客户端进入 `Stopped(REPLACED)`，不再重连
    - _Requirements: 4.3_

- [x] 15. 最终 checkpoint — 所有测试通过
  - 确保单元测试 + 属性测试 + 集成测试 + detekt 全部通过，ensure all tests pass, ask the user if questions arise.

- [x] 16. 文档与发布
  - [x] 16.1 在 `AI_INDEX.md` 与 `README.md` 中新增 Cloud Bridge 相关章节
    - 简述 Bridge package 结构、对外门面 `CloudBridgeClient`、四个注入接口、detekt 边界规则
    - _Requirements: 1.1, 1.2, 1.3_

  - [x] 16.2 在配置文档中添加 `cloud_bridge_url` / `cloud_bridge_device_token` 说明
    - 说明默认空值行为（不建连）、token 脱敏策略
    - _Requirements: 10.1, 10.2, 10.4_

  - [x] 16.3（可选）在 `ARCHITECTURE_RECONSTRUCTION.md` 补充 Bridge 线程模型与 OfflineOutbox 持久化说明
    - _Requirements: 1.1, 7.6, 9.1_

## Notes

- 标记 `*` 的任务为可选；MVP 可跳过，但属性测试强烈建议在合并前补齐（尤其 Property 1/2/3/6/7/8）。
- 每个 checkpoint 之前的所有核心任务必须完成编译与基本单元验证。
- 属性测试任务显式标注了对应的 Property 编号（1~10），与 design §10 Correctness Properties 一一对应。
- Bridge 内部任何异常都 **不得** 逃逸到宿主 app（需求 9.5 与 Property 9 覆盖）。
- 本工作流只产出计划与代码实现任务列表，不涵盖人工 UAT / 生产部署 / 运营指标收集。开始执行时，打开 `tasks.md` 并在任务项上点击 "Start task" 即可。
