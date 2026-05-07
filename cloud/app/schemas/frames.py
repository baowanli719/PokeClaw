"""WebSocket frame envelope and payload schemas."""

from pydantic import BaseModel


class Frame(BaseModel):
    """Generic WebSocket frame envelope."""
    type: str
    id: str | None = None
    ts: int  # unix millis at sender
    payload: dict = {}


class HelloPayload(BaseModel):
    """Device hello frame payload."""
    device_id: str
    app_version: str
    os: str = "android"
    os_version: str | None = None
    capabilities: list[str] = []
    battery: float | None = None
    charging: bool | None = None


class HeartbeatPayload(BaseModel):
    """Device heartbeat frame payload."""
    busy: bool = False
    current_request_id: str | None = None


class TaskDispatchPayload(BaseModel):
    """Cloud → device task dispatch payload."""
    request_id: str
    kind: str
    params: dict = {}
    deadline_ts: int


class TaskAcceptedPayload(BaseModel):
    """Device → cloud task accepted payload."""
    request_id: str


class TaskProgressPayload(BaseModel):
    """Device → cloud task progress payload."""
    request_id: str
    step: str | None = None
    ratio: float | None = None
