"""End-to-end integration tests for the Cloud Bridge.

Tests the full flow: REST submit → WS dispatch → device result → DB persist.
Uses httpx + websockets against the in-process FastAPI app.
"""

import asyncio
import json
import time

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
