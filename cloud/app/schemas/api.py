"""Pydantic schemas for the REST control plane API."""

from pydantic import BaseModel, Field


class TaskSubmitRequest(BaseModel):
    """POST /api/tasks request body."""
    device_id: str
    kind: str
    params: dict = {}
    timeout_sec: int = Field(default=120, ge=10, le=600)


class TaskSubmitResponse(BaseModel):
    """POST /api/tasks response."""
    request_id: str
    status: str


class TaskStatusResponse(BaseModel):
    """GET /api/tasks/{request_id} response."""
    request_id: str
    device_id: str
    kind: str
    status: str
    dispatched_at: str
    accepted_at: str | None = None
    completed_at: str | None = None
    progress_step: str | None = None
    progress_ratio: float | None = None
    result: dict | None = None
    error_code: str | None = None
    error_message: str | None = None


class DeviceInfo(BaseModel):
    """GET /api/devices response item."""
    device_id: str
    app_version: str
    capabilities: list[str]
    connected_at: str
    last_seen: str
    busy: bool


class HoldingsQueryResponse(BaseModel):
    """GET /api/holdings response item."""
    device_id: str
    account_alias: str
    captured_at: str
    summary: dict | None = None
    positions: list[dict] = []
