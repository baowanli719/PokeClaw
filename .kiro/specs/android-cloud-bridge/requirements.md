# 需求文档

## 简介

本文档定义了 PokeClaw Android 端 Cloud Bridge 客户端的需求。该客户端通过出站 WebSocket 连接到 PokeClaw Cloud Bridge Service（云端服务），实现 `cloud/docs/protocol.md` 中定义的通信协议，接收云端派发的任务并通过宿主 app 注入的任务执行器执行。客户端负责连接建立、认证、心跳维持、断线重连以及完整的任务生命周期管理。

为了使 Bridge 可以独立演进和测试，Bridge 被实现为位于宿主 app module 内的独立 package（如 `io.agents.pokeclaw.bridge`），且 Bridge 代码只依赖自己定义的一组抽象接口，不直接依赖 app 其他 package 中的具体类。所有与现有 app 的集成（TaskOrchestrator、AppCapabilityCoordinator、配置存储、日志系统等）通过接口适配器在 app 层完成注入。

## 术语表

- **Cloud_Bridge_Client**: Android 端 WebSocket 客户端，负责与云端 Cloud Bridge Service 建立和维护持久连接。位于独立 package `io.agents.pokeclaw.bridge` 内。
- **Cloud_Service**: 云端 FastAPI 服务，管理设备连接、派发任务并持久化结果。
- **TaskExecutor**: Bridge 自身定义的接口，表示一个任务执行器，暴露启动任务、取消任务、订阅任务进度/结果事件等能力。App 层通过对现有 TaskOrchestrator 的适配器来实现该接口并注入到 Bridge。
- **CapabilityProvider**: Bridge 自身定义的接口，表示设备能力查询，返回当前设备支持的能力快照（无障碍服务、已安装的目标 app 等）。App 层通过对现有 AppCapabilityCoordinator 的适配器来实现该接口并注入到 Bridge。
- **ConfigSource**: Bridge 自身定义的接口，表示配置读取源，暴露 server URL、Device_Token、广播的 capabilities 列表等参数。App 层通过对 MMKV / SharedPreferences 的适配器来实现该接口并注入到 Bridge。
- **BridgeLogger**: Bridge 自身定义的接口，表示日志输出。App 层通过对现有 XLog 的适配器来实现该接口并注入到 Bridge。
- **Device_Token**: 对称 Bearer Token，用于设备向云端认证身份。由 ConfigSource 提供。
- **Frame**: WebSocket JSON 帧，包含 `type`、`id`（可选）、`ts`、`payload` 字段。
- **Heartbeat**: 设备定期发送的心跳帧，向云端报告存活状态和忙碌状态。
- **Reconnect_Strategy**: 断线重连策略，使用指数退避算法恢复连接。
- **Connection_State**: 连接状态枚举，包括 DISCONNECTED、CONNECTING、CONNECTED、AUTHENTICATED 四种状态。

## 需求

### 需求 1: 接口解耦与独立可测试性

**用户故事:** 作为维护 Bridge 的开发者，我希望 Bridge 作为一个独立的 package 存在，只依赖自己定义的抽象接口，以便 Bridge 可以独立演进、独立单元测试，并避免与宿主 app 形成强耦合。

#### 验收标准

1. THE Cloud_Bridge_Client SHALL reside in a dedicated package (e.g. `io.agents.pokeclaw.bridge`) within the host app module.
2. THE Cloud_Bridge_Client SHALL depend only on interfaces that it defines itself (including but not limited to TaskExecutor, CapabilityProvider, ConfigSource, BridgeLogger) for all integration points with the host app.
3. THE Cloud_Bridge_Client source files SHALL NOT contain any import of concrete classes from other `io.agents.pokeclaw` sub-packages (such as TaskOrchestrator, AppCapabilityCoordinator, MMKV wrappers, or XLog).
4. THE host app SHALL provide adapter classes that implement the Bridge-defined interfaces by delegating to existing app components (e.g. a TaskOrchestrator-backed TaskExecutor adapter, an AppCapabilityCoordinator-backed CapabilityProvider adapter).
5. THE host app SHALL inject the adapter instances into the Cloud_Bridge_Client at construction time.
6. THE Cloud_Bridge_Client SHALL be unit-testable without requiring any concrete class from the host app (beyond the Android framework and standard third-party libraries such as OkHttp and Gson) to exist, by substituting fake or mock implementations of the Bridge-defined interfaces.
7. WHERE a third-party library (such as OkHttp for WebSocket transport or Gson for JSON serialization) is used, THE Cloud_Bridge_Client MAY depend on it directly without an additional abstraction layer.

### 需求 2: WebSocket 连接建立

**用户故事:** 作为设备运维人员，我希望 Android 设备能主动建立到云端的 WebSocket 连接，以便云端可以穿透 NAT 向设备派发任务。

#### 验收标准

1. WHEN the Cloud_Bridge_Client is started with a valid Device_Token and server URL obtained from the ConfigSource, THE Cloud_Bridge_Client SHALL establish a WebSocket connection to `wss://<host>/ws/device` with `device_id` and `app_version` as query parameters.
2. WHEN the WebSocket connection is established, THE Cloud_Bridge_Client SHALL send a `hello` frame containing `device_id`, `app_version`, `os`, `os_version`, `capabilities`, `battery`, and `charging` fields.
3. WHEN the Cloud_Bridge_Client receives a `hello.ack` frame, THE Cloud_Bridge_Client SHALL store the `heartbeat_sec` interval and `accepted_capabilities` list, and transition the Connection_State to AUTHENTICATED.
4. WHEN the Cloud_Bridge_Client does not receive a `hello.ack` within 10 seconds of sending `hello`, THE Cloud_Bridge_Client SHALL close the connection and initiate a reconnection attempt.
5. THE Cloud_Bridge_Client SHALL include the Device_Token in the `Authorization: Bearer` header of the WebSocket upgrade request.
6. THE Cloud_Bridge_Client SHALL use OkHttp as the WebSocket transport library, consistent with the existing codebase.

### 需求 3: 心跳维持

**用户故事:** 作为系统运维人员，我希望设备定期向云端发送心跳，以便云端能检测设备是否在线并了解设备当前状态。

#### 验收标准

1. WHILE the Connection_State is AUTHENTICATED, THE Cloud_Bridge_Client SHALL send a `heartbeat` frame at the interval specified by `hello.ack.payload.heartbeat_sec`.
2. THE Cloud_Bridge_Client SHALL include the current `busy` status and `current_request_id`（if a task is in progress）in each heartbeat frame payload.
3. WHEN the Cloud_Bridge_Client receives a `ping` frame, THE Cloud_Bridge_Client SHALL respond with a `pong` frame echoing the same `id` within 5 seconds.
4. WHEN the Cloud_Bridge_Client has not received any frame from the server for more than 90 seconds, THE Cloud_Bridge_Client SHALL consider the connection stale and close it to trigger reconnection.

### 需求 4: 断线重连

**用户故事:** 作为设备运维人员，我希望设备在连接断开后能自动重连，以便最大限度减少设备离线时间。

#### 验收标准

1. WHEN the WebSocket connection is closed unexpectedly (not by explicit user stop), THE Cloud_Bridge_Client SHALL initiate a reconnection attempt using exponential backoff starting at 1 second, doubling up to a maximum of 60 seconds.
2. WHEN the connection is closed with close code 4403 (authentication failed), THE Cloud_Bridge_Client SHALL stop reconnection attempts and report the authentication failure.
3. WHEN the connection is closed with close code 4401 (replaced by another connection), THE Cloud_Bridge_Client SHALL stop reconnection attempts and report the replacement event.
4. WHEN network connectivity is restored (detected via Android ConnectivityManager), THE Cloud_Bridge_Client SHALL reset the backoff timer and attempt an immediate reconnection.
5. WHILE the Cloud_Bridge_Client is attempting to reconnect, THE Cloud_Bridge_Client SHALL report the Connection_State as CONNECTING.
6. WHEN a reconnection attempt succeeds, THE Cloud_Bridge_Client SHALL reset the backoff timer to the initial value.

### 需求 5: 任务接收与执行

**用户故事:** 作为系统运维人员，我希望设备能接收云端派发的任务并执行，以便实现远程自动化操作。

#### 验收标准

1. WHEN the Cloud_Bridge_Client receives a `task.dispatch` frame, THE Cloud_Bridge_Client SHALL validate that the device is idle (no task currently running) and that the required capabilities are available via the CapabilityProvider.
2. WHEN precondition checks pass for a dispatched task, THE Cloud_Bridge_Client SHALL send a `task.accepted` frame and delegate the task to the TaskExecutor for execution.
3. WHEN precondition checks fail for a dispatched task, THE Cloud_Bridge_Client SHALL send a `task.error` frame with the appropriate error code (`accessibility_not_ready`, `app_not_installed`, or other relevant code) and `retryable` flag.
4. WHILE a task is executing, THE Cloud_Bridge_Client SHALL forward progress events from the TaskExecutor as `task.progress` frames to the cloud.
5. WHEN the TaskExecutor completes a task successfully, THE Cloud_Bridge_Client SHALL send a `task.result` frame containing the structured result payload.
6. WHEN the TaskExecutor reports a task failure, THE Cloud_Bridge_Client SHALL send a `task.error` frame with the error code and message.
7. WHEN the Cloud_Bridge_Client receives a `task.cancel` frame for a running task, THE Cloud_Bridge_Client SHALL invoke the TaskExecutor's cancel method and send a `task.error` frame with code `cancelled`.
8. WHEN a task's local execution time exceeds the `deadline_ts` specified in the dispatch frame, THE Cloud_Bridge_Client SHALL cancel the task and send a `task.error` frame with code `deadline_exceeded`.

### 需求 6: 能力广播

**用户故事:** 作为系统运维人员，我希望设备在连接时向云端广播其支持的任务类型，以便云端只派发设备能处理的任务。

#### 验收标准

1. THE Cloud_Bridge_Client SHALL obtain the list of supported task kinds from the ConfigSource and include them in the `hello` frame's `capabilities` field.
2. WHEN the device's capabilities change at runtime (e.g., a required app is installed or uninstalled), THE Cloud_Bridge_Client SHALL refresh the capabilities list via the CapabilityProvider for the next reconnection.
3. THE Cloud_Bridge_Client SHALL include device battery level and charging status in the `hello` frame payload.
4. WHEN the Cloud_Bridge_Client receives a `task.dispatch` for a kind not in the locally advertised capabilities, THE Cloud_Bridge_Client SHALL reject the task with a `task.error` frame containing code `internal` and message indicating unsupported capability.

### 需求 7: 连接生命周期管理

**用户故事:** 作为开发者，我希望 Cloud Bridge 客户端有清晰的生命周期管理，以便宿主 app 可以按需启停 Bridge，并自行决定 Bridge 的启停时机（例如由 ForegroundService 触发）。

#### 验收标准

1. THE Cloud_Bridge_Client SHALL expose `start()` and `stop()` methods for explicit lifecycle control by external callers.
2. WHEN `stop()` is called, THE Cloud_Bridge_Client SHALL close the WebSocket connection with close code 1000, cancel all pending reconnection attempts, and stop the heartbeat timer.
3. WHEN `stop()` is called while a task is in progress, THE Cloud_Bridge_Client SHALL cancel the running task via the TaskExecutor before closing the connection.
4. WHILE the Cloud_Bridge_Client has not been started via `start()`, THE Cloud_Bridge_Client SHALL remain in DISCONNECTED state and not attempt any connections.
5. THE Cloud_Bridge_Client SHALL emit Connection_State changes via an observable mechanism (callback or Flow) so that external callers can observe and display connection status.
6. THE Cloud_Bridge_Client SHALL operate its WebSocket I/O and heartbeat scheduling on a dedicated background thread, separate from the main thread.
7. THE Cloud_Bridge_Client SHALL NOT directly depend on or reference any Android Service (such as ForegroundService) class; the decision of when to call `start()` and `stop()` SHALL be made by the host app.

### 需求 8: 帧序列化与反序列化

**用户故事:** 作为开发者，我希望所有 WebSocket 帧的序列化和反序列化逻辑集中管理，以便协议变更时只需修改一处。

#### 验收标准

1. THE Cloud_Bridge_Client SHALL define Kotlin data classes for all frame types specified in `cloud/docs/protocol.md` (hello, hello.ack, heartbeat, ping, pong, task.dispatch, task.accepted, task.progress, task.result, task.error, task.cancel).
2. THE Cloud_Bridge_Client SHALL use Gson for JSON serialization and deserialization, consistent with the existing codebase.
3. WHEN the Cloud_Bridge_Client receives a frame with an unknown `type` value, THE Cloud_Bridge_Client SHALL log a warning and ignore the frame without disconnecting.
4. WHEN the Cloud_Bridge_Client receives a frame that fails JSON parsing, THE Cloud_Bridge_Client SHALL log the error and ignore the frame without disconnecting.
5. FOR ALL valid Frame objects, serializing then deserializing SHALL produce an equivalent object (round-trip property).

### 需求 9: 错误处理与容错

**用户故事:** 作为系统运维人员，我希望客户端能优雅地处理各种异常情况，以便系统在不稳定的网络环境下仍能可靠运行。

#### 验收标准

1. WHEN a WebSocket send operation fails (e.g., connection already closed), THE Cloud_Bridge_Client SHALL queue the frame for retry after reconnection if the frame is a task lifecycle frame (task.accepted, task.progress, task.result, task.error).
2. WHEN the Cloud_Bridge_Client encounters an unrecoverable error during task execution, THE Cloud_Bridge_Client SHALL send a `task.error` frame with code `internal` and a descriptive message.
3. IF the device receives a `task.dispatch` while another task is already running, THEN THE Cloud_Bridge_Client SHALL reject the new task with a `task.error` frame containing code `internal` and message indicating the device is busy.
4. WHEN the Cloud_Bridge_Client transitions from AUTHENTICATED to DISCONNECTED while a task result is pending delivery, THE Cloud_Bridge_Client SHALL persist the pending result locally and deliver it upon the next successful connection.
5. THE Cloud_Bridge_Client SHALL never crash the host application due to protocol errors or unexpected server behavior.

### 需求 10: 配置管理

**用户故事:** 作为开发者，我希望 Cloud Bridge 的连接参数可配置，以便在不同环境（开发、测试、生产）间切换。

#### 验收标准

1. THE Cloud_Bridge_Client SHALL read the server URL, Device_Token, and advertised capabilities from the injected ConfigSource.
2. WHEN the ConfigSource does not provide a server URL or Device_Token, THE Cloud_Bridge_Client SHALL remain in DISCONNECTED state and not attempt connections.
3. THE Cloud_Bridge_Client SHALL support runtime reconfiguration: when the ConfigSource values change, calling a `reconfigure()` method SHALL close the existing connection and establish a new one with the updated parameters.
4. THE Cloud_Bridge_Client SHALL never log the Device_Token value in plaintext; token values SHALL be masked in all log output produced through the BridgeLogger.

### 需求 11: 可观测性与日志

**用户故事:** 作为开发者，我希望 Cloud Bridge 客户端有充分的日志输出，以便排查连接和任务执行问题。

#### 验收标准

1. THE Cloud_Bridge_Client SHALL log connection state transitions (DISCONNECTED → CONNECTING → CONNECTED → AUTHENTICATED) with timestamps via the BridgeLogger.
2. THE Cloud_Bridge_Client SHALL log all sent and received frame types (without logging full payload content for privacy) via the BridgeLogger.
3. WHEN a task lifecycle event occurs (dispatch received, accepted, progress, result, error), THE Cloud_Bridge_Client SHALL log the event with the `request_id` via the BridgeLogger.
4. THE Cloud_Bridge_Client SHALL log reconnection attempts with the current backoff delay via the BridgeLogger.
5. THE Cloud_Bridge_Client SHALL route all log output through the injected BridgeLogger interface and SHALL NOT reference any concrete logging implementation (such as XLog, Logcat, or Timber) directly.
