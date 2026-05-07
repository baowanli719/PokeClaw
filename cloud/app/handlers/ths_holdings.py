"""Handler for ths.sync_holdings task kind.

Validates the result payload and persists the holdings snapshot to OceanBase.
"""

import structlog
from pydantic import BaseModel

from app.db.persistence import persistence
from app.schemas.holdings import ThsSyncHoldingsResult

logger = structlog.get_logger()


async def persist_ths_holdings(
    device_id: str,
    request_id: str,
    result: BaseModel,
) -> None:
    """Persist a validated THS holdings result to the database.

    Args:
        device_id: The device that produced this result.
        request_id: The task request ID for tracing.
        result: A validated ThsSyncHoldingsResult instance.
    """
    assert isinstance(result, ThsSyncHoldingsResult)

    summary_dict = result.summary.model_dump() if result.summary else None
    positions_list = [p.model_dump() for p in result.positions]

    await persistence.upsert_holdings(
        device_id=device_id,
        account_alias=result.account_alias,
        captured_at=result.captured_at,
        summary=summary_dict,
        positions=positions_list,
    )

    logger.info(
        "ths_holdings_persisted",
        device_id=device_id,
        request_id=request_id,
        account_alias=result.account_alias,
        captured_at=result.captured_at,
        position_count=len(result.positions),
    )
