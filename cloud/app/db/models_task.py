"""SQLAlchemy ORM model for task log (split file for line limit)."""

from datetime import datetime

from sqlalchemy import DateTime, JSON, String, Text, func
from sqlalchemy.orm import Mapped, mapped_column

from app.db.models import Base


class TaskLog(Base):
    """Stores the lifecycle record of each dispatched task."""

    __tablename__ = "task_log"

    request_id: Mapped[str] = mapped_column(String(26), primary_key=True)  # ULID
    device_id: Mapped[str] = mapped_column(String(64), nullable=False, index=True)
    kind: Mapped[str] = mapped_column(String(64), nullable=False)
    params: Mapped[dict | None] = mapped_column(JSON, nullable=True)
    status: Mapped[str] = mapped_column(String(20), nullable=False, default="dispatched")
    dispatched_at: Mapped[str] = mapped_column(String(64), nullable=False)
    accepted_at: Mapped[str | None] = mapped_column(String(64), nullable=True)
    completed_at: Mapped[str | None] = mapped_column(String(64), nullable=True)
    result_summary: Mapped[dict | None] = mapped_column(JSON, nullable=True)
    error_code: Mapped[str | None] = mapped_column(String(64), nullable=True)
    error_message: Mapped[str | None] = mapped_column(Text, nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime, server_default=func.now(), nullable=False
    )
