"""WebSocket endpoint for device connections."""

import time

import structlog
from fastapi import APIRouter, WebSocket, WebSocketDisconnect, Query

from app.auth import AuthService
from app.hub.device_hub import device_hub
from app.hub.task_dispatcher import task_dispatcher
from app.schemas.frames import (
    Frame,
    HelloPayload,
    HeartbeatPayload,
)
from app.schemas.frames_result import (
    TaskAcceptedPayload,
    TaskErrorPayload,
    TaskProgressPayload,
    TaskResultPayload,
)

logger = structlog.get_logger()
router = APIRouter()


@router.websocket("/ws/device")
async def ws_device(
    websocket: WebSocket,
    token: str = Query(default=""),
):
    """WebSocket endpoint for device connections.

    Auth: Bearer token via ?token= query param or Authorization header.
    """
    # Extract token from header or query
    auth_header = websocket.headers.get("authorization", "")
    if auth_header.lower().startswith("bearer "):
        ws_token = auth_header[7:].strip()
    else:
        ws_token = token

    auth = AuthService()
    if not auth.validate_device_token(ws_token):
        logger.warning("ws_auth_rejected", token_tail=ws_token[-4:] if ws_token else "")
        await websocket.close(code=4403, reason="unauthorized")
        return

    await websocket.accept()
    device_id: str | None = None

    try:
        # Wait for hello frame
        raw = await websocket.receive_text()
        frame = Frame.model_validate_json(raw)

        if frame.type != "hello":
            logger.warning("ws_expected_hello", got=frame.type)
            await websocket.close(code=4400, reason="expected hello")
            return

        hello = HelloPayload(**frame.payload)
        device_id = hello.device_id

        # Register device
        await device_hub.register(
            device_id=device_id,
            ws=websocket,
            app_version=hello.app_version,
            capabilities=hello.capabilities,
        )

        # Send hello.ack
        from app.handlers.registry import handler_registry
        ack = Frame(
            type="hello.ack",
            id=frame.id,
            ts=int(time.time() * 1000),
            payload={
                "server_time": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                "heartbeat_sec": 30,
                "accepted_capabilities": [
                    k for k in hello.capabilities
                    if k in handler_registry.supported_kinds()
                ],
            },
        )
        await websocket.send_text(ack.model_dump_json())

        # Message loop
        while True:
            raw = await websocket.receive_text()
            device_hub.touch(device_id)
            frame = Frame.model_validate_json(raw)
            await _handle_frame(device_id, frame, websocket)

    except WebSocketDisconnect:
        logger.info("ws_disconnected", device_id=device_id)
    except Exception as e:
        logger.error("ws_error", device_id=device_id, error=str(e))
    finally:
        if device_id:
            await device_hub.unregister(device_id)


async def _handle_frame(device_id: str, frame: Frame, ws: WebSocket) -> None:
    """Route an incoming frame to the appropriate handler."""
    match frame.type:
        case "heartbeat":
            payload = HeartbeatPayload(**frame.payload)
            device_hub.update_heartbeat(
                device_id, busy=payload.busy, current_request_id=payload.current_request_id
            )
        case "ping":
            pong = Frame(type="pong", id=frame.id, ts=int(time.time() * 1000))
            await ws.send_text(pong.model_dump_json())
        case "task.accepted":
            rid = frame.payload.get("request_id", "")
            await task_dispatcher.handle_accepted(rid)
        case "task.progress":
            p = frame.payload
            await task_dispatcher.handle_progress(
                p.get("request_id", ""), p.get("step"), p.get("ratio")
            )
        case "task.result":
            p = frame.payload
            await task_dispatcher.handle_result(
                p.get("request_id", ""), p.get("kind", ""), p.get("result", {})
            )
        case "task.error":
            p = frame.payload
            await task_dispatcher.handle_error(
                p.get("request_id", ""),
                p.get("code", "internal"),
                p.get("message", ""),
                p.get("retryable", False),
            )
        case _:
            logger.warning("ws_unknown_frame_type", device_id=device_id, type=frame.type)
