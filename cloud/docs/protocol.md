# Cloud ↔ Device Protocol

Version: `v1` (additive changes only inside v1; breaking changes bump the version)

## 0. Transport

- WebSocket, TLS required in production.
- URL: `wss://<host>/ws/device`
- Auth: `Authorization: Bearer <DEVICE_TOKEN>` header. `?token=` query is accepted as a fallback for environments where headers are awkward.
- Required query:
  - `device_id` — stable per phone. Reused across reconnects.
  - `app_version` — PokeClaw version the bridge is running.
- One connection per device at a time. A new connection for the same `device_id` closes the old one with code `4401 replaced`.

## 1. Frame envelope

All frames are JSON:

```json
{
  "type": "task.dispatch",
  "id": "f_01HZXYZ...",       // optional; echoed back for reply-style frames
  "ts": 1712345678901,         // unix millis at sender
  "payload": { ... }
}
```

- `type` — required. Namespaced with `.`.
- `id` — optional. Set for frames that expect a reply (dispatch/cancel/ping).
- `ts` — sender clock; used only for latency tracking, not authoritative.
- `payload` — type-specific.

Unknown `type` values MUST be ignored with a warning, not cause a disconnect.

## 2. Lifecycle

```
phone → cloud: hello
cloud → phone: hello.ack
(both sides exchange heartbeat / ping as needed)
cloud → phone: task.dispatch
phone → cloud: task.accepted
phone → cloud: task.progress*
phone → cloud: task.result | task.error
```

## 3. Device → Cloud frames

### 3.1 `hello`

```json
{
  "type": "hello",
  "id": "f_hello_1",
  "ts": 1712345678901,
  "payload": {
    "device_id": "phone-01",
    "app_version": "0.6.12",
    "os": "android",
    "os_version": "14",
    "capabilities": ["ths.sync_holdings"],
    "battery": 0.84,
    "charging": false
  }
}
```

### 3.2 `heartbeat`

Sent every ~30s.

```json
{
  "type": "heartbeat",
  "ts": 1712345678901,
  "payload": { "busy": true, "current_request_id": "req_01HZ..." }
}
```

### 3.3 `task.accepted`

Sent immediately when the phone receives `task.dispatch` and the precondition checks (idle, accessibility ok, etc.) pass.

```json
{
  "type": "task.accepted",
  "ts": 1712345678901,
  "payload": { "request_id": "req_01HZ..." }
}
```

### 3.4 `task.progress`

Optional. Free-form human readable step text plus an optional progress ratio.

```json
{
  "type": "task.progress",
  "ts": 1712345678901,
  "payload": {
    "request_id": "req_01HZ...",
    "step": "opened 同花顺",
    "ratio": 0.3
  }
}
```

### 3.5 `task.result`

Terminal success. `payload.result` is a typed object keyed by the original `kind`. See §5 for schemas.

```json
{
  "type": "task.result",
  "ts": 1712345678901,
  "payload": {
    "request_id": "req_01HZ...",
    "kind": "ths.sync_holdings",
    "result": { ... see §5.1 ... }
  }
}
```

### 3.6 `task.error`

Terminal failure.

```json
{
  "type": "task.error",
  "ts": 1712345678901,
  "payload": {
    "request_id": "req_01HZ...",
    "code": "accessibility_not_ready",
    "message": "User has not granted accessibility",
    "retryable": false
  }
}
```

Common `code` values:
- `accessibility_not_ready`
- `app_not_installed`
- `login_required` (同花顺 needs user to sign in)
- `ui_changed` (the playbook could not find the expected view)
- `deadline_exceeded`
- `cancelled`
- `internal`

## 4. Cloud → Device frames

### 4.1 `hello.ack`

```json
{
  "type": "hello.ack",
  "ts": 1712345678901,
  "payload": {
    "server_time": "2026-05-06T10:15:22Z",
    "heartbeat_sec": 30,
    "accepted_capabilities": ["ths.sync_holdings"]
  }
}
```

### 4.2 `task.dispatch`

```json
{
  "type": "task.dispatch",
  "id": "f_d_1",
  "ts": 1712345678901,
  "payload": {
    "request_id": "req_01HZ...",
    "kind": "ths.sync_holdings",
    "params": { "account_alias": "main", "timeout_sec": 120 },
    "deadline_ts": 1712345700000
  }
}
```

### 4.3 `task.cancel`

```json
{
  "type": "task.cancel",
  "ts": 1712345678901,
  "payload": { "request_id": "req_01HZ..." }
}
```

### 4.4 `ping` / `pong`

Either side may send `ping`; the other side replies with `pong` echoing the `id`.

## 5. Task kinds

### 5.1 `ths.sync_holdings`

Open 同花顺, navigate to 持仓, read the structured snapshot.

`params`:

| field | type | required | notes |
|---|---|---|---|
| `account_alias` | string | yes | Logical account name. Stored as-is into OB. |
| `timeout_sec` | int | no | Default 120. Hard stop on device. |

`result`:

```json
{
  "kind": "ths.sync_holdings",
  "captured_at": "2026-05-06T10:15:22+08:00",
  "account_alias": "main",
  "summary": {
    "total_asset": 123456.78,
    "market_value": 98765.43,
    "cash": 24691.35,
    "profit_loss": 1234.56,
    "profit_loss_ratio": 0.0125,
    "currency": "CNY"
  },
  "positions": [
    {
      "stock_code": "600519",
      "stock_name": "贵州茅台",
      "market": "SH",
      "quantity": 100,
      "available": 100,
      "cost_price": 1680.5,
      "current_price": 1725.0,
      "market_value": 172500.0,
      "profit_loss": 4450.0,
      "profit_loss_ratio": 0.0264,
      "position_ratio": 0.82
    }
  ]
}
```

All numeric fields are optional; the phone sends `null` for anything it cannot confidently parse. The cloud stores what it gets and does not invent values.

Idempotency: cloud stores the snapshot keyed on `(device_id, account_alias, captured_at)`. Re-sending the same snapshot is a no-op.

## 6. Authentication

- Device tokens are symmetric bearer tokens. Each phone has its own token. Revocation = delete from the token table.
- REST endpoints require `Authorization: Bearer <ADMIN_TOKEN>`.
- Tokens live in env / secrets manager; the dev defaults shipped in `.env.example` are for local use only.

## 7. Versioning

- Any new field is optional by default.
- Removing or repurposing a field requires bumping the URL to `wss://<host>/ws/device/v2`.
- Device advertises supported kinds in `hello.payload.capabilities`; the cloud will only dispatch kinds the device lists.
