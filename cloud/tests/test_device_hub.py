"""Unit tests for DeviceHub connection ownership behavior."""

import pytest

from app.hub.device_hub import DeviceHub


class DummyWebSocket:
    def __init__(self):
        self.closed = None

    async def close(self, code=None, reason=None):
        self.closed = (code, reason)

    async def send_text(self, text):
        self.sent = text


@pytest.mark.asyncio
async def test_unregister_old_connection_does_not_remove_replacement():
    hub = DeviceHub()
    old_ws = DummyWebSocket()
    new_ws = DummyWebSocket()

    await hub.register("phone-01", old_ws, "0.1", ["ths.sync_holdings"])
    await hub.register("phone-01", new_ws, "0.2", ["ths.sync_holdings"])

    await hub.unregister("phone-01", old_ws)

    entry = hub.get("phone-01")
    assert entry is not None
    assert entry.ws is new_ws
    assert old_ws.closed == (4401, "replaced")


@pytest.mark.asyncio
async def test_unregister_current_connection_removes_device():
    hub = DeviceHub()
    ws = DummyWebSocket()

    await hub.register("phone-01", ws, "0.1", ["ths.sync_holdings"])
    await hub.unregister("phone-01", ws)

    assert hub.get("phone-01") is None
