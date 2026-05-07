"""Shared test fixtures."""

import os
import pytest


@pytest.fixture(autouse=True)
def _test_env(monkeypatch):
    """Set test environment variables."""
    monkeypatch.setenv("OB_DSN", "sqlite+aiosqlite:///:memory:")
    monkeypatch.setenv("DEVICE_TOKENS", "test_device_token")
    monkeypatch.setenv("ADMIN_TOKEN", "test_admin_token")
    monkeypatch.setenv("LOG_LEVEL", "DEBUG")
    monkeypatch.setenv("STALE_TIMEOUT_SEC", "90")
    monkeypatch.setenv("DEADLINE_CHECK_INTERVAL_SEC", "1")

    # Clear cached settings
    from app.config import get_settings
    get_settings.cache_clear()
