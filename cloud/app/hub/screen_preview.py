"""Low-frame-rate screen preview relay for browser admin clients."""

import asyncio
from dataclasses import dataclass, field
from datetime import datetime, timezone

import structlog
from fastapi import WebSocket

logger = structlog.get_logger()


@dataclass
class ScreenFrame:
    """Latest preview frame metadata and JPEG payload."""
    device_id: str
    session_id: str
    seq: int
    captured_at: int
    width: int
    height: int
    image_format: str
    image_base64: str
    received_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))

    def to_dict(self, include_image: bool = True) -> dict:
        body = {
            "device_id": self.device_id,
            "session_id": self.session_id,
            "seq": self.seq,
            "captured_at": self.captured_at,
            "width": self.width,
            "height": self.height,
            "image_format": self.image_format,
            "received_at": self.received_at.isoformat(),
        }
        if include_image:
            body["image_base64"] = self.image_base64
        return body


class ScreenPreviewHub:
    """Stores latest frames and broadcasts them to admin WebSocket viewers."""

    def __init__(self) -> None:
        self._latest: dict[str, ScreenFrame] = {}
        self._sessions: dict[str, dict] = {}
        self._subscribers: dict[str, set[WebSocket]] = {}
        self._lock = asyncio.Lock()

    async def set_session(self, device_id: str, session: dict) -> None:
        async with self._lock:
            self._sessions[device_id] = session

    async def clear_session(self, device_id: str, session_id: str | None = None) -> None:
        async with self._lock:
            current = self._sessions.get(device_id)
            if current is None:
                return
            if session_id is None or current.get("session_id") == session_id:
                self._sessions.pop(device_id, None)

    def get_session(self, device_id: str) -> dict | None:
        return self._sessions.get(device_id)

    def get_latest(self, device_id: str) -> ScreenFrame | None:
        return self._latest.get(device_id)

    async def handle_frame(self, device_id: str, payload: dict) -> ScreenFrame:
        frame = ScreenFrame(
            device_id=device_id,
            session_id=str(payload.get("session_id", "")),
            seq=int(payload.get("seq", 0)),
            captured_at=int(payload.get("captured_at", 0)),
            width=int(payload.get("width", 0)),
            height=int(payload.get("height", 0)),
            image_format=str(payload.get("image_format", "jpeg")),
            image_base64=str(payload.get("image_base64", "")),
        )
        self._latest[device_id] = frame
        logger.info(
            "screen_preview_frame_received",
            device_id=device_id,
            session_id=frame.session_id,
            seq=frame.seq,
            width=frame.width,
            height=frame.height,
        )
        await self._broadcast(device_id, frame.to_dict(include_image=True))
        return frame

    async def handle_status(self, device_id: str, payload: dict) -> None:
        status = {
            "device_id": device_id,
            "session_id": payload.get("session_id", ""),
            "status": payload.get("status", "unknown"),
            "message": payload.get("message"),
            "received_at": datetime.now(timezone.utc).isoformat(),
        }
        logger.info("screen_preview_status", **status)
        await self._broadcast(device_id, status, message_type="screen.preview.status")

    async def subscribe(self, device_id: str, websocket: WebSocket) -> None:
        async with self._lock:
            self._subscribers.setdefault(device_id, set()).add(websocket)
            latest = self._latest.get(device_id)
        if latest is not None:
            await websocket.send_json({"type": "screen.frame", "payload": latest.to_dict()})

    async def unsubscribe(self, device_id: str, websocket: WebSocket) -> None:
        async with self._lock:
            subscribers = self._subscribers.get(device_id)
            if subscribers is None:
                return
            subscribers.discard(websocket)
            if not subscribers:
                self._subscribers.pop(device_id, None)

    async def _broadcast(
        self,
        device_id: str,
        payload: dict,
        message_type: str = "screen.frame",
    ) -> None:
        subscribers = list(self._subscribers.get(device_id, set()))
        if not subscribers:
            return

        stale: list[WebSocket] = []
        message = {"type": message_type, "payload": payload}
        for websocket in subscribers:
            try:
                await websocket.send_json(message)
            except Exception as exc:
                stale.append(websocket)
                logger.warning("screen_preview_broadcast_failed", device_id=device_id, error=str(exc))

        for websocket in stale:
            await self.unsubscribe(device_id, websocket)


screen_preview_hub = ScreenPreviewHub()
