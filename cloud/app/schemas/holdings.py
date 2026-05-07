"""Pydantic schemas for the ths.sync_holdings task kind."""

from pydantic import BaseModel


class PositionItem(BaseModel):
    """A single stock position in the portfolio."""
    stock_code: str
    stock_name: str
    market: str | None = None
    quantity: int | None = None
    available: int | None = None
    cost_price: float | None = None
    current_price: float | None = None
    market_value: float | None = None
    profit_loss: float | None = None
    profit_loss_ratio: float | None = None
    position_ratio: float | None = None


class HoldingsSummary(BaseModel):
    """Portfolio-level summary numbers."""
    total_asset: float | None = None
    market_value: float | None = None
    cash: float | None = None
    profit_loss: float | None = None
    profit_loss_ratio: float | None = None
    currency: str = "CNY"


class ThsSyncHoldingsResult(BaseModel):
    """Validated result schema for ths.sync_holdings task kind."""
    kind: str = "ths.sync_holdings"
    captured_at: str  # ISO 8601 with timezone
    account_alias: str
    summary: HoldingsSummary | None = None
    positions: list[PositionItem] = []
