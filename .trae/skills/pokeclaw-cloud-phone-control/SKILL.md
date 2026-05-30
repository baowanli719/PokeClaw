---
name: "pokeclaw-cloud-phone-control"
description: "Control a PokeClaw Android phone through the Cloud Bridge REST API. Invoke when the user wants a remote agent to inspect devices, dispatch phone tasks, or poll task results."
---

# PokeClaw Cloud Phone Control

Use this skill when the user wants you to operate a PokeClaw-connected Android phone through the Cloud Bridge service instead of direct ADB or local UI automation.

This skill assumes the phone is already connected to the cloud over WebSocket and that you have:

- `CLOUD_HOST`, for example `https://cloud.example.com`
- `ADMIN_TOKEN`
- optionally a preferred `device_id`

## What This Skill Can Do

The current Cloud Bridge supports these task kinds:

- `agent.run_task`: ask the phone-side agent to complete a natural-language task
- `android.open_url`: open a URL on the phone
- `ths.sync_holdings`: trigger the TongHuaShun holdings sync flow and persist structured results

Prefer `agent.run_task` for general phone operation requests. Use `android.open_url` only when the user explicitly wants to open a specific page. Use `ths.sync_holdings` only for holdings sync requests.

## Required Workflow

Always follow this order:

1. Verify `CLOUD_HOST` and `ADMIN_TOKEN` are available.
2. List online devices with `GET /api/devices`.
3. Choose a device:
   - use the user-provided `device_id` if present
   - otherwise ask the user which device to use if multiple devices are online
   - if no devices are online, stop and report that no phone is connected
4. Inspect the chosen device's `capabilities`.
5. Dispatch a supported task with `POST /api/tasks`.
6. Poll `GET /api/tasks/{request_id}` until terminal state.
7. Return a concise summary of:
   - target device
   - dispatched kind
   - final status
   - result payload or error details

Never claim the phone action succeeded until the task reaches a terminal success state.

## API Reference

### 1. List Devices

```bash
curl -sS \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  "$CLOUD_HOST/api/devices"
```

Expected response: a JSON array with fields such as `device_id`, `capabilities`, `busy`, and `last_seen`.

### 2. Dispatch a Task

```bash
curl -sS \
  -X POST \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  "$CLOUD_HOST/api/tasks" \
  -d '{
    "device_id": "phone-01",
    "kind": "agent.run_task",
    "params": {
      "task": "Open WeChat and tell me how many unread chats I have"
    },
    "timeout_sec": 180
  }'
```

Expected response:

```json
{
  "request_id": "req_...",
  "status": "dispatched"
}
```

### 3. Poll Task Status

```bash
curl -sS \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  "$CLOUD_HOST/api/tasks/req_..."
```

Useful fields in the response:

- `status`
- `progress_step`
- `progress_ratio`
- `result`
- `error_code`
- `error_message`

## Task Templates

### `agent.run_task`

Use for most remote control requests.

Body template:

```json
{
  "device_id": "<device_id>",
  "kind": "agent.run_task",
  "params": {
    "task": "<natural language instruction for the phone agent>"
  },
  "timeout_sec": 180
}
```

Examples of good tasks:

- `Open Alipay and check whether there are unread notifications`
- `Open Settings and tell me the current battery percentage`
- `Open WeChat, search for Mom, and summarize the latest message`

Write the phone task as a direct instruction. Keep it specific, observable, and single-goal when possible.

### `android.open_url`

Use when the user explicitly wants a URL opened on the phone.

```json
{
  "device_id": "<device_id>",
  "kind": "android.open_url",
  "params": {
    "url": "https://example.com"
  },
  "timeout_sec": 60
}
```

### `ths.sync_holdings`

Use only for TongHuaShun holdings sync.

```json
{
  "device_id": "<device_id>",
  "kind": "ths.sync_holdings",
  "params": {
    "account_alias": "main"
  },
  "timeout_sec": 120
}
```

After completion, you may also query persisted snapshots from:

```bash
curl -sS \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  "$CLOUD_HOST/api/holdings?device_id=<device_id>&account_alias=main&limit=1"
```

## Capability Rules

Before dispatching, confirm the selected device advertises the required capability:

- `agent.run_task`
- `android.open_url`
- `ths.sync_holdings`

If the capability is missing, do not dispatch the task. Tell the user the device does not support that operation.

## Failure Handling

Common failure patterns:

- no online device: `/api/devices` returns an empty array
- unsupported capability: requested kind not present in `capabilities`
- auth failure: `401` or `403`, usually bad `ADMIN_TOKEN`
- task failure: terminal response contains `error_code` and `error_message`

Common device-side error codes include:

- `accessibility_not_ready`
- `app_not_installed`
- `login_required`
- `ui_changed`
- `deadline_exceeded`
- `cancelled`
- `internal`

If a task fails, report the exact code and message, then suggest the smallest next action, such as reconnecting the phone, granting accessibility, or retrying with a clearer task.

## Operating Rules

- Always inspect devices first unless the user already provided a known-good `device_id`.
- Prefer one task at a time per device.
- If `busy` is true, warn the user before dispatching another task.
- Do not invent unsupported task kinds.
- Do not expose the full `ADMIN_TOKEN` in your response.
- Do not claim you can directly tap coordinates or read the screen without going through the cloud task channel.

## Response Style

When reporting back to the user, include:

- selected `device_id`
- dispatched `kind`
- final `status`
- concise result summary
- exact failure code/message if unsuccessful

## Minimal Playbook

If the user says "control my phone", run this playbook:

1. list devices
2. choose one device
3. use `agent.run_task`
4. poll until done
5. summarize the result

If the user says "open this URL on my phone", use `android.open_url`.

If the user says "sync my TongHuaShun holdings", use `ths.sync_holdings`.
