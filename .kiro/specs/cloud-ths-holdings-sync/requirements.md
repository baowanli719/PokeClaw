# Requirements Document

## Introduction

This document specifies the requirements for the PokeClaw Cloud Bridge Service — a standalone Python/FastAPI service that accepts outbound WebSocket connections from PokeClaw-equipped Android phones, dispatches tasks (starting with `ths.sync_holdings`), receives structured results, and persists them into OceanBase. The service is fully decoupled from the Android codebase; the only shared contract is the WebSocket protocol defined in `cloud/docs/protocol.md`.

## Glossary

- **Cloud_Bridge**: The FastAPI service that manages device connections, dispatches tasks, and persists results.
- **Device**: A PokeClaw-equipped Android phone that connects outbound to the Cloud_Bridge via WebSocket.
- **Device_Hub**: The WebSocket endpoint and in-memory registry that tracks connected devices.
- **Task_Dispatcher**: The component that assigns tasks to connected devices and tracks their lifecycle.
- **Control_Plane**: The REST API layer that allows humans or external systems to submit tasks and query state.
- **Persistence_Layer**: The OceanBase database adapter using SQLAlchemy for storing holdings snapshots and task history.
- **Handler**: A pluggable module for a specific task kind, consisting of a Pydantic result schema and an async persist function.
- **THS**: 同花顺 (Tonghuashun), a Chinese stock trading application.
- **Holdings_Snapshot**: A structured capture of portfolio positions from THS at a point in time.
- **OceanBase**: The MySQL-compatible distributed database used for persistence (abbreviated OB).
- **Device_Token**: A symmetric bearer token used to authenticate a device's WebSocket connection.
- **Admin_Token**: A symmetric bearer token used to authenticate REST control plane requests.
- **Task_Request**: A unit of work dispatched to a device, identified by a unique `request_id`.
- **Heartbeat**: A periodic frame sent by the device to signal liveness and current state.

## Requirements

### Requirement 1: WebSocket Device Connection

**User Story:** As a device operator, I want the phone to establish a persistent outbound WebSocket connection to the cloud, so that the cloud can dispatch tasks without needing to reach the phone behind NAT.

#### Acceptance Criteria

1. WHEN a Device connects to `wss://<host>/ws/device` with a valid Device_Token, THE Device_Hub SHALL accept the connection and register the device in the in-memory registry.
2. WHEN a Device connects with an invalid or missing Device_Token, THE Device_Hub SHALL reject the connection with WebSocket close code 4403.
3. WHEN a Device sends a `hello` frame after connection, THE Device_Hub SHALL respond with a `hello.ack` frame containing the server time, heartbeat interval, and accepted capabilities.
4. WHEN a second connection arrives for the same `device_id`, THE Device_Hub SHALL close the existing connection with close code 4401 and register the new connection.
5. WHILE a Device is connected, THE Device_Hub SHALL track the device's `device_id`, `app_version`, `capabilities`, connection time, and last heartbeat timestamp.
6. WHEN a Device disconnects or the WebSocket connection drops, THE Device_Hub SHALL remove the device from the in-memory registry within 5 seconds.

### Requirement 2: Heartbeat and Liveness

**User Story:** As a system operator, I want the cloud to detect stale device connections, so that tasks are not dispatched to unreachable phones.

#### Acceptance Criteria

1. WHEN a Device sends a `heartbeat` frame, THE Device_Hub SHALL update the device's last-seen timestamp and busy status.
2. WHEN a Device has not sent a heartbeat or any frame for more than 90 seconds, THE Device_Hub SHALL consider the connection stale and close it with close code 4408.
3. WHEN either side sends a `ping` frame, THE receiving side SHALL respond with a `pong` frame echoing the same `id`.

### Requirement 3: Task Dispatch

**User Story:** As a system operator, I want to dispatch tasks to connected devices, so that the phone can execute automation workflows on my behalf.

#### Acceptance Criteria

1. WHEN the Control_Plane receives a task submission for a connected device, THE Task_Dispatcher SHALL send a `task.dispatch` frame to the target device.
2. WHEN the target device is not connected, THE Task_Dispatcher SHALL reject the task submission with an appropriate error indicating the device is offline.
3. WHEN the target device does not list the requested task kind in its capabilities, THE Task_Dispatcher SHALL reject the task submission with an error indicating unsupported capability.
4. THE Task_Dispatcher SHALL generate a unique `request_id` (ULID format) for each dispatched task.
5. WHEN a task is dispatched, THE Task_Dispatcher SHALL record the task with status `dispatched` and a `deadline_ts` computed from the `timeout_sec` parameter.
6. WHEN the device sends a `task.accepted` frame, THE Task_Dispatcher SHALL update the task status to `accepted`.
7. WHEN the device sends a `task.progress` frame, THE Task_Dispatcher SHALL update the task's progress metadata.
8. WHEN the device sends a `task.result` frame, THE Task_Dispatcher SHALL update the task status to `completed` and invoke the appropriate Handler.
9. WHEN the device sends a `task.error` frame, THE Task_Dispatcher SHALL update the task status to `failed` and record the error code and message.
10. WHEN a task's deadline elapses without a terminal frame (`task.result` or `task.error`), THE Task_Dispatcher SHALL send a `task.cancel` frame to the device and mark the task as `timed_out`.

### Requirement 4: REST Control Plane

**User Story:** As a system operator, I want a REST API to submit tasks, list connected devices, and query task results, so that I can control the system without direct WebSocket interaction.

#### Acceptance Criteria

1. WHEN an authenticated admin submits a POST to `/api/tasks`, THE Control_Plane SHALL validate the request body and dispatch the task to the specified device.
2. WHEN an authenticated admin sends a GET to `/api/tasks/{request_id}`, THE Control_Plane SHALL return the task's current status, progress, and result if available.
3. WHEN an authenticated admin sends a GET to `/api/devices`, THE Control_Plane SHALL return a list of currently connected devices with their metadata.
4. WHEN an authenticated admin sends a GET to `/api/holdings`, THE Control_Plane SHALL return the most recent holdings snapshots, filterable by `device_id` and `account_alias`.
5. WHEN a request to the Control_Plane lacks a valid Admin_Token, THE Control_Plane SHALL respond with HTTP 401 Unauthorized.
6. THE Control_Plane SHALL validate all request bodies against Pydantic schemas and return HTTP 422 with field-level errors for invalid input.

### Requirement 5: OceanBase Persistence

**User Story:** As a system operator, I want holdings data and task history persisted in OceanBase, so that I have a durable record of all synced portfolio snapshots.

#### Acceptance Criteria

1. THE Persistence_Layer SHALL store holdings snapshots in a `holdings_snapshot` table with columns for `device_id`, `account_alias`, `captured_at`, `summary` (JSON), and `positions` (JSON).
2. THE Persistence_Layer SHALL enforce a unique constraint on `(device_id, account_alias, captured_at)` to guarantee idempotent writes.
3. WHEN a duplicate snapshot is written (same device_id, account_alias, captured_at), THE Persistence_Layer SHALL treat it as a no-op without raising an error.
4. THE Persistence_Layer SHALL store task history in a `task_log` table with columns for `request_id`, `device_id`, `kind`, `params`, `status`, `dispatched_at`, `completed_at`, `result_summary`, and `error`.
5. THE Persistence_Layer SHALL use SQLAlchemy async engine with connection pooling configured for OceanBase's MySQL-compatible protocol.
6. WHEN the database is unreachable at startup, THE Cloud_Bridge SHALL log an error and exit with a non-zero status code.
7. WHEN a database write fails during task result handling, THE Persistence_Layer SHALL retry up to 3 times with exponential backoff before marking the persist as failed.

### Requirement 6: THS Sync Holdings Handler

**User Story:** As a system operator, I want the `ths.sync_holdings` result to be validated and persisted, so that I have structured, queryable portfolio data in OceanBase.

#### Acceptance Criteria

1. WHEN a `task.result` frame with kind `ths.sync_holdings` is received, THE Handler SHALL validate the result payload against the `ThsSyncHoldingsResult` Pydantic schema.
2. IF the result payload fails schema validation, THEN THE Handler SHALL log the validation error and mark the task as `persist_failed` with the validation details.
3. WHEN validation succeeds, THE Handler SHALL write the holdings snapshot to the `holdings_snapshot` table using an idempotent upsert.
4. THE Handler SHALL accept `null` values for any numeric field in positions and summary, storing them as-is without inventing default values.
5. THE Handler SHALL store the `captured_at` timestamp exactly as reported by the device without server-side adjustment.

### Requirement 7: Authentication

**User Story:** As a system operator, I want device and admin authentication, so that only authorized phones and operators can interact with the service.

#### Acceptance Criteria

1. THE Cloud_Bridge SHALL authenticate WebSocket connections using a Device_Token provided in the `Authorization: Bearer` header or `?token=` query parameter.
2. THE Cloud_Bridge SHALL authenticate REST requests using an Admin_Token provided in the `Authorization: Bearer` header.
3. THE Cloud_Bridge SHALL support multiple Device_Tokens (one per device) loaded from environment configuration.
4. WHEN a Device_Token is revoked (removed from configuration), THE Cloud_Bridge SHALL reject new connections using that token on the next connection attempt.
5. THE Cloud_Bridge SHALL never log token values in plaintext; tokens SHALL be masked in log output.

### Requirement 8: Configuration and Environment

**User Story:** As a developer, I want all configuration externalized to environment variables, so that the service can be deployed across environments without code changes.

#### Acceptance Criteria

1. THE Cloud_Bridge SHALL load configuration from environment variables, with `.env` file support for local development.
2. THE Cloud_Bridge SHALL require the following environment variables: `OB_DSN` (database connection string), `DEVICE_TOKENS` (comma-separated device tokens), and `ADMIN_TOKEN`.
3. WHEN a required environment variable is missing at startup, THE Cloud_Bridge SHALL exit with a clear error message naming the missing variable.
4. THE Cloud_Bridge SHALL provide a `.env.example` file documenting all supported environment variables with placeholder values.
5. WHERE a `LOG_LEVEL` variable is configured, THE Cloud_Bridge SHALL set the application log level accordingly (default: `INFO`).

### Requirement 9: Handler Extensibility

**User Story:** As a developer, I want to add new task kinds without modifying the transport or dispatch layers, so that the system grows without coupling.

#### Acceptance Criteria

1. THE Cloud_Bridge SHALL discover handlers by task kind through a handler registry that maps `kind` strings to handler modules.
2. WHEN a new handler is registered, THE Cloud_Bridge SHALL require only a Pydantic result schema and an async `persist` function.
3. WHEN a `task.result` arrives for an unregistered kind, THE Task_Dispatcher SHALL log a warning and store the raw result in the task log without calling a handler.
4. THE Cloud_Bridge SHALL not require changes to the WebSocket layer, dispatch logic, or REST endpoints when a new handler is added.

### Requirement 10: Operational Scripts

**User Story:** As a system operator, I want CLI scripts to issue tasks and list devices, so that I can operate the system without writing HTTP requests manually.

#### Acceptance Criteria

1. THE Cloud_Bridge project SHALL include a `scripts/issue_task.py` script that submits a task via the REST API given a device ID, task kind, and JSON params.
2. THE Cloud_Bridge project SHALL include a `scripts/list_devices.py` script that queries and displays currently connected devices.
3. WHEN a script is invoked without required arguments, THE script SHALL print usage instructions and exit with a non-zero status code.
4. THE scripts SHALL read the admin token and host URL from environment variables or command-line flags.

### Requirement 11: Observability and Logging

**User Story:** As a system operator, I want structured logging for all significant events, so that I can monitor and debug the system in production.

#### Acceptance Criteria

1. THE Cloud_Bridge SHALL emit structured JSON logs for device connect, disconnect, task dispatch, task completion, and task failure events.
2. THE Cloud_Bridge SHALL include `device_id` and `request_id` as structured fields in all task-related log entries.
3. WHEN a task completes, THE Cloud_Bridge SHALL log the elapsed time from dispatch to completion.
4. THE Cloud_Bridge SHALL expose a `GET /health` endpoint that returns HTTP 200 when the service is running and the database connection is healthy.
