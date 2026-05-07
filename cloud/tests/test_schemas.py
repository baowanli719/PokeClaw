"""Unit tests for Pydantic schema validation."""

import pytest
from pydantic import ValidationError

from app.schemas.holdings import (
    HoldingsSummary,
    PositionItem,
    ThsSyncHoldingsResult,
)
from app.schemas.frames import Frame, HelloPayload
from app.schemas.api import TaskSubmitRequest


class TestThsSyncHoldingsResult:
    def test_valid_full(self):
        data = {
            "kind": "ths.sync_holdings",
            "captured_at": "2026-05-06T10:15:22+08:00",
            "account_alias": "main",
            "summary": {"total_asset": 100000.0, "currency": "CNY"},
            "positions": [
                {"stock_code": "600519", "stock_name": "贵州茅台", "quantity": 100}
            ],
        }
        result = ThsSyncHoldingsResult(**data)
        assert result.account_alias == "main"
        assert len(result.positions) == 1
        assert result.positions[0].stock_code == "600519"

    def test_null_numeric_fields(self):
        """All numeric fields in positions/summary can be null."""
        data = {
            "captured_at": "2026-05-06T10:15:22+08:00",
            "account_alias": "main",
            "summary": {
                "total_asset": None,
                "market_value": None,
                "cash": None,
            },
            "positions": [
                {
                    "stock_code": "000001",
                    "stock_name": "平安银行",
                    "quantity": None,
                    "cost_price": None,
                    "current_price": None,
                }
            ],
        }
        result = ThsSyncHoldingsResult(**data)
        assert result.summary.total_asset is None
        assert result.positions[0].quantity is None

    def test_missing_required_fields(self):
        """captured_at and account_alias are required."""
        with pytest.raises(ValidationError):
            ThsSyncHoldingsResult(kind="ths.sync_holdings")

    def test_empty_positions(self):
        data = {
            "captured_at": "2026-05-06T10:15:22+08:00",
            "account_alias": "main",
        }
        result = ThsSyncHoldingsResult(**data)
        assert result.positions == []
        assert result.summary is None


class TestFrame:
    def test_minimal_frame(self):
        f = Frame(type="hello", ts=1712345678901)
        assert f.payload == {}
        assert f.id is None

    def test_full_frame(self):
        f = Frame(type="task.dispatch", id="f_1", ts=123, payload={"key": "val"})
        assert f.id == "f_1"
        assert f.payload["key"] == "val"


class TestTaskSubmitRequest:
    def test_valid(self):
        req = TaskSubmitRequest(device_id="phone-01", kind="ths.sync_holdings")
        assert req.timeout_sec == 120

    def test_timeout_bounds(self):
        with pytest.raises(ValidationError):
            TaskSubmitRequest(device_id="x", kind="y", timeout_sec=5)
        with pytest.raises(ValidationError):
            TaskSubmitRequest(device_id="x", kind="y", timeout_sec=700)
