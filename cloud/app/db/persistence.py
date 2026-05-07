"""Persistence layer: idempotent writes and queries for OceanBase."""

import asyncio
from datetime import datetime

import structlog
from sqlalchemy import select, text
from sqlalchemy.exc import IntegrityError

from app.db.engine import db_engine
from app.db.models import HoldingsSnapshot
from app.db.models_task import TaskLog

logger = structlog.get_logger()

MAX_RETRIES = 3
BACKOFF_BASE = 0.5  # seconds


class PersistenceLayer:
    """High-level persistence operations with retry logic."""

    async def upsert_holdings(
        self,
        device_id: str,
        account_alias: str,
        captured_at: str,
        summary: dict | None,
        positions: list | None,
    ) -> None:
        """Idempotent insert of a holdings snapshot.

        If a row with the same (device_id, account_alias, captured_at) exists,
        this is a no-op (idempotent).
        """
        await self._retry_write(
            self._do_upsert_holdings,
            device_id, account_alias, captured_at, summary, positions,
        )

    async def _do_upsert_holdings(
        self,
        device_id: str,
        account_alias: str,
        captured_at: str,
        summary: dict | None,
        positions: list | None,
    ) -> None:
        async with db_engine.session() as session:
            row = HoldingsSnapshot(
                device_id=device_id,
                account_alias=account_alias,
                captured_at=captured_at,
                summary=summary,
                positions=positions,
            )
            session.add(row)
            try:
                await session.commit()
                logger.info(
                    "holdings_upserted",
                    device_id=device_id,
                    account_alias=account_alias,
                    captured_at=captured_at,
                )
            except IntegrityError:
                await session.rollback()
                logger.info(
                    "holdings_duplicate_ignored",
                    device_id=device_id,
                    captured_at=captured_at,
                )

    async def log_task(
        self,
        request_id: str,
        device_id: str,
        kind: str,
        params: dict | None,
        status: str,
        dispatched_at: str,
    ) -> None:
        """Insert a new task log entry."""
        await self._retry_write(
            self._do_log_task,
            request_id, device_id, kind, params, status, dispatched_at,
        )

    async def _do_log_task(
        self,
        request_id: str,
        device_id: str,
        kind: str,
        params: dict | None,
        status: str,
        dispatched_at: str,
    ) -> None:
        async with db_engine.session() as session:
            row = TaskLog(
                request_id=request_id,
                device_id=device_id,
                kind=kind,
                params=params,
                status=status,
                dispatched_at=dispatched_at,
            )
            session.add(row)
            await session.commit()

    async def update_task_log(self, request_id: str, **fields) -> None:
        """Update fields on an existing task log entry."""
        async with db_engine.session() as session:
            result = await session.execute(
                select(TaskLog).where(TaskLog.request_id == request_id)
            )
            row = result.scalar_one_or_none()
            if row is None:
                logger.warning("task_log_not_found", request_id=request_id)
                return
            for key, value in fields.items():
                if hasattr(row, key):
                    setattr(row, key, value)
            await session.commit()

    async def query_holdings(
        self,
        device_id: str | None = None,
        account_alias: str | None = None,
        limit: int = 50,
    ) -> list[HoldingsSnapshot]:
        """Query holdings snapshots with optional filters."""
        async with db_engine.session() as session:
            stmt = select(HoldingsSnapshot).order_by(
                HoldingsSnapshot.id.desc()
            )
            if device_id:
                stmt = stmt.where(HoldingsSnapshot.device_id == device_id)
            if account_alias:
                stmt = stmt.where(HoldingsSnapshot.account_alias == account_alias)
            stmt = stmt.limit(limit)
            result = await session.execute(stmt)
            return list(result.scalars().all())

    async def _retry_write(self, fn, *args) -> None:
        """Retry a write operation with exponential backoff."""
        for attempt in range(MAX_RETRIES):
            try:
                await fn(*args)
                return
            except IntegrityError:
                # Idempotent duplicate — not a real failure
                return
            except Exception as e:
                if attempt == MAX_RETRIES - 1:
                    logger.error(
                        "db_write_failed_after_retries",
                        fn=fn.__name__,
                        attempts=MAX_RETRIES,
                        error=str(e),
                    )
                    raise
                wait = BACKOFF_BASE * (2 ** attempt)
                logger.warning(
                    "db_write_retry",
                    fn=fn.__name__,
                    attempt=attempt + 1,
                    wait_sec=wait,
                    error=str(e),
                )
                await asyncio.sleep(wait)


# Singleton
persistence = PersistenceLayer()
