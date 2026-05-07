"""Async SQLAlchemy engine and session management for OceanBase."""

import structlog
from sqlalchemy.ext.asyncio import (
    AsyncEngine,
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)
from sqlalchemy import text

from app.db.models import Base

logger = structlog.get_logger()


class DBEngine:
    """Manages the async SQLAlchemy engine lifecycle."""

    def __init__(self) -> None:
        self._engine: AsyncEngine | None = None
        self._session_factory: async_sessionmaker[AsyncSession] | None = None

    async def init(self, dsn: str) -> None:
        """Initialize the async engine with connection pooling."""
        self._engine = create_async_engine(
            dsn,
            pool_size=5,
            max_overflow=10,
            pool_recycle=3600,
            echo=False,
        )
        self._session_factory = async_sessionmaker(
            self._engine, expire_on_commit=False
        )
        logger.info("db_engine_initialized", dsn_host=dsn.split("@")[-1] if "@" in dsn else "***")

    @property
    def engine(self) -> AsyncEngine:
        assert self._engine is not None, "DBEngine not initialized"
        return self._engine

    def session(self) -> AsyncSession:
        assert self._session_factory is not None, "DBEngine not initialized"
        return self._session_factory()

    async def health_check(self) -> bool:
        """Check database connectivity."""
        try:
            async with self.session() as session:
                await session.execute(text("SELECT 1"))
            return True
        except Exception as e:
            logger.error("db_health_check_failed", error=str(e))
            return False

    async def dispose(self) -> None:
        """Close the engine and release connections."""
        if self._engine:
            await self._engine.dispose()
            logger.info("db_engine_disposed")


async def ensure_tables() -> None:
    """Create all tables if they don't exist."""
    async with db_engine.engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    logger.info("db_tables_ensured")


# Singleton instance
db_engine = DBEngine()
