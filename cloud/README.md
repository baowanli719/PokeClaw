# PokeClaw Cloud Bridge

A small FastAPI service that lets you drive a PokeClaw-equipped Android phone from the cloud.

Phones connect outbound over WebSocket, the cloud dispatches tasks, and structured results (e.g. 同花顺持仓) get written into OceanBase (OB).

## What lives here

- `app/` — FastAPI service, WebSocket device hub, REST control plane.
- `app/handlers/` — per-task-kind handlers. `ths.sync_holdings` is the first one and writes holdings into OB.
- `app/db/` — OceanBase adapter, SQLAlchemy models, schema bootstrap.
- `docs/protocol.md` — the full cloud ↔ device contract. Read this first.
- `scripts/` — small operational helpers (issue a task, list devices, tail a request).

## Quick start

```bash
cd cloud
python -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env           # then fill in OB + tokens
python -m app.main              # or: uvicorn app.main:app --host 0.0.0.0 --port 8080
```

Then point your phone's bridge at `wss://<this-host>/ws/device` with the device token from `.env`.

## Issuing the first task

```bash
python scripts/issue_task.py \
    --device phone-01 \
    --kind ths.sync_holdings \
    --params '{"account_alias":"main"}'
```

The cloud dispatches the task to the phone, the phone runs its 同花顺 playbook, and the resulting holdings land in OB.

## Design notes

- The phone is assumed to be behind NAT/运营商. The cloud never dials the phone directly; the phone maintains a long-lived outbound WebSocket.
- REST is only the control plane. The fast path (dispatch/result/progress) rides the WebSocket.
- Handlers are pluggable. A task kind = a pydantic result schema + an async `persist()` that writes to OB. Adding new kinds (watchlist sync, 成交记录 sync, etc.) does not touch the transport layer.
- The service is stateless enough that you can run it on one box today and scale horizontally later behind a sticky-session LB or Redis-backed device registry.
