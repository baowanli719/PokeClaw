"""Register all task handlers at application startup."""

from app.handlers.registry import Handler, handler_registry
from app.handlers.ths_holdings import persist_ths_holdings
from app.schemas.holdings import ThsSyncHoldingsResult


def register_handlers() -> None:
    """Register all known task kind handlers."""
    handler_registry.register(
        "ths.sync_holdings",
        Handler(
            result_schema=ThsSyncHoldingsResult,
            persist=persist_ths_holdings,
        ),
    )
    # Future handlers go here:
    # handler_registry.register("ths.sync_watchlist", Handler(...))
    # handler_registry.register("ths.sync_trades", Handler(...))
