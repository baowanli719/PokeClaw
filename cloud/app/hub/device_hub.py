"""DeviceHub: manages WebSocket connections and in-memory device registry."""

import asyncio
import json
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone

import structlog
from fastapi import WebSocket

from app.config import get_settings
from app.schemas.frames import Frame

logger = structlog.get_logger()


@dataclass
class DeviceEntry:
    """Represents a connected device."""
    device_id: str
    app_version: str
    capabilities: list[str]
    ws: WebSocket
    connected_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    last_seen: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    busy: bool = False
    current_request_id: str | None = None


class DeviceHub:
    """In-memory registry of connected devices."""

    def __init__(self) -> None:
        self._devices: dict[str, DeviceEntry] = {}
        self._lock = asyncio.Lock()

    async def register(
        self, device_id: str, ws: WebSocket, app_version: str, capabilities: list[str]
    ) -> None:
        """Register a device. Closes existing connection if duplicate."""
        async with self._lock:
            existing = self._devices.get(device_id)
            if existing is not None:
                logger.info("device_replaced", device_id=device_id)
                try:
                    await existing.ws.close(code=4401, reason="replaced")
                except Exception:
                    pass

            self._devices[device_id] = DeviceEntry(
                device_id=device_id,
                app_version=app_version,
                capabilities=capabilities,
                ws=ws,
            )
        logger.info(
            "device_registered",
            device_id=device_id,
            app_version=app_version,
            capabilities=capabilities,
        )

    async def unregister(self, device_id: str) -> None:
        """Remove a device from the registry."""
        async with self._lock:
            if device_id in self._devices:
                del self._devices[device_id]
                logger.info("device_unregistered", device_id=device_id)

    def get(self, device_id: str) -> DeviceEntry | None:
        """Look up a device by ID."""
        return self._devices.get(device_id)

    def list_devices(self) -> list[DeviceEntry]:
        """Return all connected devices."""
        return list(self._devices.values())

    def update_heartbeat(self, device_id: str, busy: bool = False, current_request_id: str | None = None) -> None:
        """Update last-seen and busy state for a device."""
        entry = self._devices.get(device_id)
        if entry:
            entry.last_seen = datetime.now(timezone.utc)
            entry.busy = busy
            entry.current_request_id = current_request_id

    def touch(self, device_id: str) -> None:
        """Update last-seen timestamp (called on any incoming frame)."""
        entry = self._devices.get(device_id)
        if entry:
            entry.last_seen = datetime.now(timezone.utc)

    async def send_frame(self, device_id: str, frame: Frame) -> None:
        """Send a frame to a connected device."""
        entry = self._devices.get(device_id)
        if entry is None:
            raise ValueError(f"Device {device_id} not connected")
        await entry.ws.send_text(frame.model_dump_json())

    async def run_stale_sweep(self) -> None:
        """Background task: close stale connections every 30s."""
        settings = get_settings()
        while True:
            await asyncio.sleep(settings.heartbeat_interval_sec)
            await self._sweep_stale(settings.stale_timeout_sec)

    async def _sweep_stale(self, timeout_sec: int) -> None:
        """Close connections that haven't sent any frame for timeout_sec."""
        now = datetime.now(timezone.utc)
        stale_ids = []
        for device_id, entry in list(self._devices.items()):
            elapsed = (now - entry.last_seen).total_seconds()
            if elapsed > timeout_sec:
                stale_ids.append(device_id)

        for device_id in stale_ids:
            entry = self._devices.get(device_id)
            if entry:
                logger.warning("device_stale", device_id=device_id)
                try:
                    await entry.ws.close(code=4408, reason="stale")
                except Exception:
                    pass
                await self.unregister(device_id)


# Singleton
device_hub = DeviceHub()
