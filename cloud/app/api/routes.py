"""REST control plane endpoints."""

from fastapi import APIRouter, Depends, HTTPException, Query, status

from app.auth import require_admin
from app.db.engine import db_engine
from app.db.persistence import persistence
from app.hub.device_hub import device_hub
from app.hub.task_dispatcher import task_dispatcher
from app.schemas.api import (
    DeviceInfo,
    HoldingsQueryResponse,
    TaskStatusResponse,
    TaskSubmitRequest,
    TaskSubmitResponse,
)

router = APIRouter()


@router.post(
    "/api/tasks",
    response_model=TaskSubmitResponse,
    dependencies=[Depends(require_admin)],
)
async def submit_task(body: TaskSubmitRequest):
    """Submit a new task for dispatch to a connected device."""
    try:
        request_id = await task_dispatcher.dispatch(
            device_id=body.device_id,
            kind=body.kind,
            params=body.params,
            timeout_sec=body.timeout_sec,
        )
    except ValueError as e:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(e))
    return TaskSubmitResponse(request_id=request_id, status="dispatched")


@router.get(
    "/api/tasks/{request_id}",
    response_model=TaskStatusResponse,
    dependencies=[Depends(require_admin)],
)
async def get_task(request_id: str):
    """Query task status and result."""
    record = task_dispatcher.get_task(request_id)
    if record is None:
        raise HTTPException(status_code=404, detail="Task not found")
    return TaskStatusResponse(
        request_id=record.request_id,
        device_id=record.device_id,
        kind=record.kind,
        status=record.status.value,
        dispatched_at=record.dispatched_at.isoformat(),
        accepted_at=record.accepted_at.isoformat() if record.accepted_at else None,
        completed_at=record.completed_at.isoformat() if record.completed_at else None,
        progress_step=record.progress_step,
        progress_ratio=record.progress_ratio,
        result=record.result,
        error_code=record.error_code,
        error_message=record.error_message,
    )


@router.get(
    "/api/devices",
    response_model=list[DeviceInfo],
    dependencies=[Depends(require_admin)],
)
async def list_devices():
    """List currently connected devices."""
    entries = device_hub.list_devices()
    return [
        DeviceInfo(
            device_id=e.device_id,
            app_version=e.app_version,
            capabilities=e.capabilities,
            connected_at=e.connected_at.isoformat(),
            last_seen=e.last_seen.isoformat(),
            busy=e.busy,
        )
        for e in entries
    ]


@router.get(
    "/api/holdings",
    response_model=list[HoldingsQueryResponse],
    dependencies=[Depends(require_admin)],
)
async def query_holdings(
    device_id: str | None = Query(default=None),
    account_alias: str | None = Query(default=None),
    limit: int = Query(default=50, ge=1, le=500),
):
    """Query holdings snapshots from the database."""
    rows = await persistence.query_holdings(
        device_id=device_id, account_alias=account_alias, limit=limit
    )
    return [
        HoldingsQueryResponse(
            device_id=r.device_id,
            account_alias=r.account_alias,
            captured_at=r.captured_at,
            summary=r.summary,
            positions=r.positions or [],
        )
        for r in rows
    ]


@router.get("/health")
async def health():
    """Health check: service running + DB connectivity."""
    db_ok = await db_engine.health_check()
    if not db_ok:
        raise HTTPException(status_code=503, detail="Database unreachable")
    return {"status": "ok"}
