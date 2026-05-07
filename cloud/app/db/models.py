"""SQLAlchemy ORM models for OceanBase persistence."""

from datetime import datetime

from sqlalchemy import (
    BigInteger,
    DateTime,
    Index,
    JSON,
    String,
    Text,
    UniqueConstraint,
    func,
)
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


class Base(DeclarativeBase):
    pass


class HoldingsSnapshot(Base):
    """Stores a point-in-time portfolio snapshot from a device."""

    __tablename__ = "holdings_snapshot"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    device_id: Mapped[str] = mapped_column(String(64), nullable=False, index=True)
    account_alias: Mapped[str] = mapped_column(String(64), nullable=False)
    captured_at: Mapped[str] = mapped_column(String(64), nullable=False)
    summary: Mapped[dict | None] = mapped_column(JSON, nullable=True)
    positions: Mapped[list | None] = mapped_column(JSON, nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime, server_default=func.now(), nullable=False
    )

    __table_args__ = (
        UniqueConstraint(
            "device_id", "account_alias", "captured_at",
            name="uq_snapshot_identity",
        ),
        Index("ix_snapshot_device_account", "device_id", "account_alias"),
    )
