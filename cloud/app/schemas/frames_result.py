"""WebSocket frame result/error payload schemas (split to stay under line limit)."""

from pydantic import BaseModel


class TaskResultPayload(BaseModel):
    """Device → cloud task result payload."""
    request_id: str
    kind: str
    result: dict


class TaskErrorPayload(BaseModel):
    """Device → cloud task error payload."""
    request_id: str
    code: str
    message: str = ""
    retryable: bool = False


class TaskCancelPayload(BaseModel):
    """Cloud → device task cancel payload."""
    request_id: str


class PingPayload(BaseModel):
    """Ping/pong payload."""
    pass
