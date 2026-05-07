"""Unit tests for authentication service."""

import os
import pytest


@pytest.fixture(autouse=True)
def _env_setup(monkeypatch):
    """Set required env vars for Settings to load."""
    monkeypatch.setenv("OB_DSN", "mysql+asyncmy://user:pass@localhost:3306/test")
    monkeypatch.setenv("DEVICE_TOKENS", "token_a,token_b, token_c ")
    monkeypatch.setenv("ADMIN_TOKEN", "admin_secret")
    # Clear lru_cache so settings reload with test env
    from app.config import get_settings
    get_settings.cache_clear()


def test_validate_device_token_valid():
    from app.auth import AuthService
    auth = AuthService()
    assert auth.validate_device_token("token_a") is True
    assert auth.validate_device_token("token_b") is True
    assert auth.validate_device_token("token_c") is True


def test_validate_device_token_invalid():
    from app.auth import AuthService
    auth = AuthService()
    assert auth.validate_device_token("token_d") is False
    assert auth.validate_device_token("") is False
    assert auth.validate_device_token("admin_secret") is False


def test_validate_admin_token_valid():
    from app.auth import AuthService
    auth = AuthService()
    assert auth.validate_admin_token("admin_secret") is True


def test_validate_admin_token_invalid():
    from app.auth import AuthService
    auth = AuthService()
    assert auth.validate_admin_token("wrong") is False
    assert auth.validate_admin_token("token_a") is False


def test_mask_token():
    from app.auth import AuthService
    assert AuthService.mask_token("abcdefgh") == "***efgh"
    assert AuthService.mask_token("ab") == "***"
    assert AuthService.mask_token("abcd") == "***"
    assert AuthService.mask_token("abcde") == "***bcde"


def test_device_tokens_whitespace_handling(monkeypatch):
    """Tokens with surrounding whitespace should be trimmed."""
    monkeypatch.setenv("DEVICE_TOKENS", " x , y , z ")
    from app.config import get_settings
    get_settings.cache_clear()
    from app.auth import AuthService
    auth = AuthService()
    assert auth.validate_device_token("x") is True
    assert auth.validate_device_token(" x ") is False
