# Implementation Tasks

## Task 1: Project Scaffolding and Configuration

- [x] 1. Set up project scaffolding and configuration system
  - [x] 1.1 Create `cloud/app/__init__.py` and `cloud/app/main.py` with FastAPI app factory
  - [x] 1.2 Create `cloud/app/config.py` with Pydantic Settings class loading from env vars (`OB_DSN`, `DEVICE_TOKENS`, `ADMIN_TOKEN`, `LOG_LEVEL`)
  - [x] 1.3 Create `cloud/requirements.txt` with pinned dependencies (fastapi, uvicorn, sqlalchemy[asyncio], asyncmy, pydantic, python-ulid, python-dotenv, structlog)
  - [x] 1.4 Create `cloud/.env.example` documenting all supported environment variables with placeholder values
  - [x] 1.5 Create `cloud/app/logging_setup.py` with structured JSON logging configuration using structlog
  - [x] 1.6 Validate that missing required env vars cause a clear error message and non-zero exit at startup

**Requirements covered:** R8 (Configuration and Environment), R11.1 (structured JSON logs)

---

## Task 2: Authentication Module

- [x] 2. Implement authentication service
  - [x] 2.1 Create `cloud/app/auth.py` with `AuthService` class that loads device tokens (comma-separated from env) and admin token
  - [x] 2.2 Implement `validate_device_token(token: str) -> bool` method
  - [x] 2.3 Implement `validate_admin_token(token: str) -> bool` method
  - [x] 2.4 Implement token masking utility (`***{last4}`) for log output
  - [x] 2.5 Create FastAPI dependency `require_admin` that extracts Bearer token from Authorization header and returns 401 if invalid
  - [x] 2.6 Write unit tests for token validation and masking

**Requirements covered:** R7 (Authentication)

---

## Task 3: Pydantic Schemas and Frame Models

- [x] 3. Define all Pydantic schemas for WebSocket frames and REST API
  - [x] 3.1 Create `cloud/app/schemas/frames.py` with `Frame`, `HelloPayload`, `HeartbeatPayload`, `TaskDispatchPayload`, `TaskAcceptedPayload`, `TaskProgressPayload`, `TaskResultPayload`, `TaskErrorPayload`
  - [x] 3.2 Create `cloud/app/schemas/holdings.py` with `PositionItem`, `HoldingsSummary`, `ThsSyncHoldingsResult`
  - [x] 3.3 Create `cloud/app/schemas/api.py` with `TaskSubmitRequest`, `TaskSubmitResponse`, `TaskStatusResponse`, `DeviceInfo`, `HoldingsQueryResponse`
  - [x] 3.4 Write unit tests for schema validation (valid payloads, null numeric fields, invalid payloads)

**Requirements covered:** R6.1, R6.4 (schema validation, null tolerance)

---

## Task 4: OceanBase Persistence Layer

- [x] 4. Implement the persistence layer with SQLAlchemy async
  - [x] 4.1 Create `cloud/app/db/__init__.py` and `cloud/app/db/engine.py` with async engine factory, connection pool config (`pool_size=5`, `max_overflow=10`, `pool_recycle=3600`), and `health_check()` method
  - [x] 4.2 Create `cloud/app/db/models.py` with SQLAlchemy ORM models: `HoldingsSnapshot` table (unique constraint on device_id+account_alias+captured_at) and `TaskLog` table (ULID primary key)
  - [x] 4.3 Create `cloud/app/db/persistence.py` with `PersistenceLayer` class implementing `upsert_holdings()` (INSERT ON DUPLICATE KEY UPDATE), `log_task()`, `update_task_log()`, `query_holdings()`
  - [x] 4.4 Implement retry logic (3 attempts, exponential backoff) wrapping write operations
  - [x] 4.5 Add `ensure_tables()` function that creates tables if not exist at startup
  - [x] 4.6 Write unit tests with SQLite in-memory for persistence logic (idempotent upsert, retry behavior)

**Requirements covered:** R5 (OceanBase Persistence)

---

## Task 5: Handler Registry and THS Handler

- [x] 5. Implement the handler registry and `ths.sync_holdings` handler
  - [x] 5.1 Create `cloud/app/handlers/__init__.py` and `cloud/app/handlers/registry.py` with `HandlerRegistry` class (register, get, supported_kinds)
  - [x] 5.2 Create `cloud/app/handlers/ths_holdings.py` implementing the `ths.sync_holdings` handler: validate with `ThsSyncHoldingsResult` schema, call `upsert_holdings()`, store `captured_at` as-is from device
  - [x] 5.3 Create `cloud/app/handlers/setup.py` with `register_handlers()` function called at app startup
  - [x] 5.4 Handle unregistered kinds: log warning, store raw result in task_log without calling handler
  - [x] 5.5 Write unit tests for handler registry (register, lookup, missing kind) and THS handler (valid result, validation failure, null fields)

**Requirements covered:** R6 (THS Sync Holdings Handler), R9 (Handler Extensibility)

---

## Task 6: DeviceHub (WebSocket Connection Manager)

- [x] 6. Implement the WebSocket device hub
  - [x] 6.1 Create `cloud/app/hub/__init__.py` and `cloud/app/hub/device_hub.py` with `DeviceEntry` dataclass and `DeviceHub` class (in-memory dict registry)
  - [x] 6.2 Implement `register()`: store device entry, close existing connection (code 4401) if duplicate device_id
  - [x] 6.3 Implement `unregister()`: remove device from registry on disconnect
  - [x] 6.4 Implement `send_frame()`: serialize Frame and send via WebSocket
  - [x] 6.5 Implement `close_stale()`: background sweep every 30s, close connections with no frame for >90s (code 4408)
  - [x] 6.6 Implement `list_devices()` and `get()` accessors
  - [x] 6.7 Write unit tests for registry operations (register, duplicate replace, unregister, stale detection)

**Requirements covered:** R1 (WebSocket Device Connection), R2 (Heartbeat and Liveness)

---

## Task 7: TaskDispatcher (Lifecycle Manager)

- [x] 7. Implement the task dispatcher
  - [x] 7.1 Create `cloud/app/hub/task_dispatcher.py` with `TaskState` enum, `TaskRecord` dataclass, and `TaskDispatcher` class
  - [x] 7.2 Implement `dispatch()`: validate device online + capability, generate ULID, send `task.dispatch` frame, record task with status `dispatched`
  - [x] 7.3 Implement `handle_accepted()`, `handle_progress()`: update task state
  - [x] 7.4 Implement `handle_result()`: invoke handler registry, persist result, update task to `completed`, flush to task_log DB
  - [x] 7.5 Implement `handle_error()`: update task to `failed`, flush to task_log DB
  - [x] 7.6 Implement `check_deadlines()`: background task every 10s, send `task.cancel` for expired tasks, mark as `timed_out`
  - [x] 7.7 Write unit tests for dispatch lifecycle (happy path, offline device, unsupported kind, deadline timeout)

**Requirements covered:** R3 (Task Dispatch)

---

## Task 8: WebSocket Endpoint

- [x] 8. Implement the WebSocket endpoint that ties DeviceHub and TaskDispatcher together
  - [x] 8.1 Create `cloud/app/ws/__init__.py` and `cloud/app/ws/endpoint.py` with FastAPI WebSocket route at `/ws/device`
  - [x] 8.2 Implement connection auth: extract token from header or `?token=` query, reject with 4403 if invalid
  - [x] 8.3 Implement message loop: parse incoming frames, route by `type` to appropriate handler (hello, heartbeat, ping, task.accepted, task.progress, task.result, task.error)
  - [x] 8.4 Implement `hello` handling: extract device_id from payload, register in DeviceHub, respond with `hello.ack`
  - [x] 8.5 Implement disconnect handling: unregister device on WebSocket close or exception
  - [x] 8.6 Ignore unknown frame types with a warning log (do not disconnect)
  - [x] 8.7 Wire background tasks (stale sweep, deadline check) into FastAPI lifespan

**Requirements covered:** R1, R2, R3 (WebSocket integration)

---

## Task 9: REST Control Plane

- [x] 9. Implement the REST API endpoints
  - [x] 9.1 Create `cloud/app/api/__init__.py` and `cloud/app/api/routes.py` with FastAPI APIRouter
  - [x] 9.2 Implement `POST /api/tasks`: validate body with `TaskSubmitRequest`, call TaskDispatcher.dispatch(), return `TaskSubmitResponse`
  - [x] 9.3 Implement `GET /api/tasks/{request_id}`: lookup task in dispatcher or DB, return `TaskStatusResponse`
  - [x] 9.4 Implement `GET /api/devices`: return list of connected devices from DeviceHub
  - [x] 9.5 Implement `GET /api/holdings`: query persistence layer with optional `device_id` and `account_alias` filters
  - [x] 9.6 Implement `GET /health`: check service running + DB connectivity, return 200 or 503
  - [x] 9.7 Apply `require_admin` dependency to all `/api/*` routes (except `/health`)
  - [x] 9.8 Write integration tests for REST endpoints (auth, validation errors, happy paths)

**Requirements covered:** R4 (REST Control Plane), R11.4 (health endpoint)

---

## Task 10: Application Wiring and Lifespan

- [x] 10. Wire all components together in the FastAPI application
  - [x] 10.1 Update `cloud/app/main.py`: create app with lifespan that initializes DB engine, creates tables, starts background tasks (stale sweep, deadline check), and registers handlers
  - [x] 10.2 Mount WebSocket endpoint and REST router on the app
  - [x] 10.3 Add structured logging context (device_id, request_id) to all task-related log entries
  - [x] 10.4 Log elapsed time from dispatch to completion on task finish
  - [x] 10.5 Verify the full startup sequence: config load → DB connect → table ensure → handler register → background tasks → ready

**Requirements covered:** R11 (Observability and Logging), integration

---

## Task 11: Operational Scripts

- [x] 11. Create operational CLI scripts
  - [x] 11.1 Create `cloud/scripts/issue_task.py`: accepts `--device`, `--kind`, `--params` (JSON string), `--host`, reads admin token from env or `--token` flag, POSTs to `/api/tasks`, prints result
  - [x] 11.2 Create `cloud/scripts/list_devices.py`: accepts `--host`, reads admin token from env or `--token` flag, GETs `/api/devices`, prints formatted table
  - [x] 11.3 Both scripts: print usage and exit non-zero when required args are missing
  - [x] 11.4 Add a `cloud/scripts/README.md` with usage examples

**Requirements covered:** R10 (Operational Scripts)

---

## Task 12: End-to-End Integration Test

- [x] 12. Write end-to-end integration tests
  - [x] 12.1 Create `cloud/tests/test_e2e.py` with a test that: starts the FastAPI app in-process, connects a mock WebSocket device, dispatches a `ths.sync_holdings` task via REST, simulates device accepted → result flow, verifies task status is `completed` and holdings are in DB
  - [x] 12.2 Test error path: device sends `task.error`, verify task status is `failed`
  - [x] 12.3 Test deadline timeout: dispatch task, do not respond, verify `timed_out` status after deadline
  - [x] 12.4 Test auth rejection: invalid device token → WS close 4403, invalid admin token → HTTP 401
  - [x] 12.5 Test idempotent holdings write: send same result twice, verify only one row in DB
