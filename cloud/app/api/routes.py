"""REST control plane endpoints."""

import time
import uuid
from pathlib import Path

from fastapi import APIRouter, Depends, HTTPException, Query, status
from fastapi.responses import FileResponse
from pydantic import BaseModel, Field

from app.auth import require_admin
from app.db.engine import db_engine
from app.db.persistence import persistence
from app.hub.device_hub import device_hub
from app.hub.screen_preview import screen_preview_hub
from app.hub.task_dispatcher import task_dispatcher
from app.schemas.frames import Frame
from app.schemas.api import (
    DeviceInfo,
    HoldingsQueryResponse,
    TaskStatusResponse,
    TaskSubmitRequest,
    TaskSubmitResponse,
)

router = APIRouter()
APK_DOWNLOAD_PATH = Path(__file__).resolve().parents[2] / "downloads" / "PokeClaw_latest.apk"


class ScreenPreviewStartRequest(BaseModel):
    """Controls the phone-side preview encoder settings."""
    interval_ms: int = Field(default=1000, ge=500, le=5000)
    jpeg_quality: int = Field(default=45, ge=20, le=80)
    max_width: int = Field(default=720, ge=240, le=1080)


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
    "/api/tasks",
    response_model=list[TaskStatusResponse],
    dependencies=[Depends(require_admin)],
)
async def list_tasks(
    device_id: str | None = Query(default=None),
    kind: str | None = Query(default=None),
    limit: int = Query(default=20, ge=1, le=100),
):
    """List recent persisted task logs."""
    rows = await persistence.query_task_logs(device_id=device_id, kind=kind, limit=limit)
    return [
        TaskStatusResponse(
            request_id=r.request_id,
            device_id=r.device_id,
            kind=r.kind,
            status=r.status,
            dispatched_at=r.dispatched_at,
            accepted_at=r.accepted_at,
            completed_at=r.completed_at,
            result=r.result_summary,
            error_code=r.error_code,
            error_message=r.error_message,
        )
        for r in rows
    ]


@router.get(
    "/api/tasks/{request_id}",
    response_model=TaskStatusResponse,
    dependencies=[Depends(require_admin)],
)
async def get_task(request_id: str):
    """Query task status and result."""
    record = task_dispatcher.get_task(request_id)
    if record is None:
        persisted = await persistence.get_task_log(request_id)
        if persisted is None:
            raise HTTPException(status_code=404, detail="Task not found")
        return TaskStatusResponse(
            request_id=persisted.request_id,
            device_id=persisted.device_id,
            kind=persisted.kind,
            status=persisted.status,
            dispatched_at=persisted.dispatched_at,
            accepted_at=persisted.accepted_at,
            completed_at=persisted.completed_at,
            result=persisted.result_summary,
            error_code=persisted.error_code,
            error_message=persisted.error_message,
        )
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


@router.post(
    "/api/devices/{device_id}/screen-preview/start",
    dependencies=[Depends(require_admin)],
)
async def start_screen_preview(device_id: str, body: ScreenPreviewStartRequest):
    """Ask a connected phone to start low-frame-rate JPEG screen preview."""
    device = device_hub.get(device_id)
    if device is None:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=f"Device {device_id} not connected")
    if "screen.preview" not in device.capabilities:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Device {device_id} does not advertise screen.preview; install the latest APK",
        )

    session_id = uuid.uuid4().hex
    payload = {
        "session_id": session_id,
        "interval_ms": body.interval_ms,
        "jpeg_quality": body.jpeg_quality,
        "max_width": body.max_width,
    }
    frame = Frame(
        type="screen.preview.start",
        id=f"screen-preview-{session_id}",
        ts=int(time.time() * 1000),
        payload=payload,
    )
    await device_hub.send_frame(device_id, frame)
    await screen_preview_hub.set_session(device_id, payload)
    return {"status": "started", "device_id": device_id, **payload}


@router.post(
    "/api/devices/{device_id}/screen-preview/stop",
    dependencies=[Depends(require_admin)],
)
async def stop_screen_preview(device_id: str):
    """Ask a connected phone to stop screen preview."""
    session = screen_preview_hub.get_session(device_id)
    payload = {"session_id": session.get("session_id")} if session else {}
    if device_hub.get(device_id) is not None:
        frame = Frame(
            type="screen.preview.stop",
            id=f"screen-preview-stop-{uuid.uuid4().hex}",
            ts=int(time.time() * 1000),
            payload=payload,
        )
        await device_hub.send_frame(device_id, frame)
    await screen_preview_hub.clear_session(device_id)
    return {"status": "stopped", "device_id": device_id}


@router.get(
    "/api/devices/{device_id}/screen-preview/latest",
    dependencies=[Depends(require_admin)],
)
async def get_latest_screen_preview(device_id: str):
    """Return the most recent preview frame for one device."""
    frame = screen_preview_hub.get_latest(device_id)
    if frame is None:
        raise HTTPException(status_code=404, detail="No preview frame available")
    return frame.to_dict(include_image=True)


@router.get("/downloads/PokeClaw_latest.apk")
async def download_latest_apk():
    """Public APK download for quick phone-side installation."""
    if not APK_DOWNLOAD_PATH.exists():
        raise HTTPException(status_code=404, detail="APK not uploaded")
    return FileResponse(
        APK_DOWNLOAD_PATH,
        media_type="application/vnd.android.package-archive",
        filename="PokeClaw_latest.apk",
    )


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
