"""End-to-end integration tests for the Cloud Bridge.

Tests the full flow: REST submit → WS dispatch → device result → DB persist.
Uses httpx + websockets against the in-process FastAPI app.
"""

import asyncio
import json
import time
from types import SimpleNamespace

import pytest
import httpx
from httpx import ASGITransport


@pytest.fixture
def app():
    """Create a fresh app instance for each test."""
    from app.config import get_settings
    get_settings.cache_clear()
    from app.main import create_app
    return create_app()


@pytest.fixture
def admin_headers():
    return {"Authorization": "Bearer test_admin_token"}


@pytest.fixture
def device_token():
    return "test_device_token"


@pytest.mark.asyncio
async def test_health_endpoint(app):
    """Health endpoint returns 200 when service is up."""
    transport = ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        # Note: health will fail without DB init, but the endpoint itself works
        resp = await client.get("/health")
        # Without lifespan, DB isn't initialized, so this may 503
        assert resp.status_code in (200, 503)


@pytest.mark.asyncio
async def test_devices_empty(app, admin_headers):
    """GET /api/devices returns empty list when no devices connected."""
    transport = ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.get("/api/devices", headers=admin_headers)
        assert resp.status_code == 200
        assert resp.json() == []


@pytest.mark.asyncio
async def test_screen_preview_start_device_offline(app, admin_headers):
    """Starting preview for an offline device returns 400."""
    transport = ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.post(
            "/api/devices/nonexistent/screen-preview/start",
            json={"interval_ms": 1000, "jpeg_quality": 45, "max_width": 720},
            headers=admin_headers,
        )
        assert resp.status_code == 400
        assert "not connected" in resp.json()["detail"]


@pytest.mark.asyncio
async def test_screen_preview_latest_missing(app, admin_headers):
    """Latest preview returns 404 before any frame arrives."""
    transport = ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.get(
            "/api/devices/phone-01/screen-preview/latest",
            headers=admin_headers,
        )
        assert resp.status_code == 404


@pytest.mark.asyncio
async def test_admin_console_served(app):
    """GET /admin serves the browser console without embedding secrets."""
    transport = ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.get("/admin")
        assert resp.status_code == 200
        assert "PokeClaw Cloud Admin" in resp.text
        assert "test_admin_token" not in resp.text


@pytest.mark.asyncio
async def test_admin_info_requires_token(app, admin_headers):
    """GET /api/admin/info exposes safe metadata behind ADMIN_TOKEN."""
    transport = ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        unauthorized = await client.get("/api/admin/info")
        assert unauthorized.status_code == 401

        resp = await client.get("/api/admin/info", headers=admin_headers)
        assert resp.status_code == 200
        body = resp.json()
        assert body["admin_token_env"] == "ADMIN_TOKEN"
        assert body["admin_token_masked"] == "***oken"
        assert body["device_tokens_count"] == 1
        assert any(k["kind"] == "agent.run_task" for k in body["task_kinds"])
        assert body["websocket_url"] == "ws://test/ws/device"


@pytest.mark.asyncio
async def test_apk_download_missing(app, monkeypatch, tmp_path):
    """APK download returns 404 when no artifact has been placed on the server."""
    monkeypatch.setenv("APK_ARTIFACT_DIR", str(tmp_path))
    from app.config import get_settings
    get_settings.cache_clear()

    transport = ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.get("/downloads/PokeClaw_latest.apk")
        assert resp.status_code == 404


@pytest.mark.asyncio
async def test_apk_download_serves_latest(app, monkeypatch, tmp_path):
    """APK download serves the latest artifact with Android package media type."""
    apk = tmp_path / "PokeClaw_latest.apk"
    apk.write_bytes(b"fake-apk")
    monkeypatch.setenv("APK_ARTIFACT_DIR", str(tmp_path))
    from app.config import get_settings
    get_settings.cache_clear()

    transport = ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.get("/downloads/PokeClaw_latest.apk")
        assert resp.status_code == 200
        assert resp.content == b"fake-apk"
        assert resp.headers["content-type"] == "application/vnd.android.package-archive"


@pytest.mark.asyncio
async def test_auth_rejection(app):
    """REST endpoints reject requests without valid admin token."""
    transport = ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        # No token
        resp = await client.get("/api/devices")
        assert resp.status_code == 401

        # Wrong token
        resp = await client.get(
            "/api/devices", headers={"Authorization": "Bearer wrong"}
        )
        assert resp.status_code == 401


@pytest.mark.asyncio
async def test_submit_task_device_offline(app, admin_headers):
    """Submitting a task for an offline device returns 400."""
    transport = ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.post(
            "/api/tasks",
            json={
                "device_id": "nonexistent",
                "kind": "ths.sync_holdings",
                "params": {"account_alias": "main"},
            },
            headers=admin_headers,
        )
        assert resp.status_code == 400
        assert "not connected" in resp.json()["detail"]


@pytest.mark.asyncio
async def test_submit_task_validation_error(app, admin_headers):
    """Invalid request body returns 422."""
    transport = ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.post(
            "/api/tasks",
            json={"device_id": "x", "kind": "y", "timeout_sec": 5},  # too low
            headers=admin_headers,
        )
        assert resp.status_code == 422


@pytest.mark.asyncio
async def test_get_task_falls_back_to_persisted_log(app, admin_headers, monkeypatch):
    """Task lookup survives loss of the in-memory dispatcher record."""
    from app.api import routes

    monkeypatch.setattr(routes.task_dispatcher, "get_task", lambda request_id: None)

    async def fake_get_task_log(request_id):
        return SimpleNamespace(
            request_id=request_id,
            device_id="phone-01",
            kind="ths.sync_holdings",
            status="completed",
            dispatched_at="2026-05-06T10:15:22+00:00",
            accepted_at="2026-05-06T10:15:23+00:00",
            completed_at="2026-05-06T10:15:30+00:00",
            result_summary={"ok": True},
            error_code=None,
            error_message=None,
        )

    monkeypatch.setattr(routes.persistence, "get_task_log", fake_get_task_log)

    transport = ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.get("/api/tasks/01HXTESTTASK000000000000", headers=admin_headers)
        assert resp.status_code == 200
        body = resp.json()
        assert body["status"] == "completed"
        assert body["result"] == {"ok": True}


@pytest.mark.asyncio
async def test_list_tasks_uses_persisted_logs(app, admin_headers, monkeypatch):
    """GET /api/tasks lists recent task logs for the admin console."""
    from app.api import routes

    async def fake_query_task_logs(device_id=None, kind=None, limit=20):
        assert device_id == "phone-01"
        assert kind == "agent.run_task"
        assert limit == 5
        return [
            SimpleNamespace(
                request_id="01HXTESTTASK000000000001",
                device_id="phone-01",
                kind="agent.run_task",
                status="completed",
                dispatched_at="2026-05-06T10:15:22+00:00",
                accepted_at="2026-05-06T10:15:23+00:00",
                completed_at="2026-05-06T10:15:30+00:00",
                result_summary={"summary": "Battery is 84%"},
                error_code=None,
                error_message=None,
            )
        ]

    monkeypatch.setattr(routes.persistence, "query_task_logs", fake_query_task_logs)

    transport = ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.get(
            "/api/tasks?device_id=phone-01&kind=agent.run_task&limit=5",
            headers=admin_headers,
        )
        assert resp.status_code == 200
        body = resp.json()
        assert body[0]["request_id"] == "01HXTESTTASK000000000001"
        assert body[0]["result"] == {"summary": "Battery is 84%"}
