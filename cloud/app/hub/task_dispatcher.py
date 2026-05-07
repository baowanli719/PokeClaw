"""TaskDispatcher: manages task lifecycle from dispatch through terminal state."""

import asyncio
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from enum import Enum

import structlog
from pydantic import ValidationError
from ulid import ULID

from app.config import get_settings
from app.db.persistence import persistence
from app.handlers.registry import handler_registry
from app.hub.device_hub import device_hub
from app.schemas.frames import Frame, TaskDispatchPayload

logger = structlog.get_logger()


class TaskState(str, Enum):
    DISPATCHED = "dispatched"
    ACCEPTED = "accepted"
    COMPLETED = "completed"
    FAILED = "failed"
    TIMED_OUT = "timed_out"
    PERSIST_FAILED = "persist_failed"


@dataclass
class TaskRecord:
    request_id: str
    device_id: str
    kind: str
    params: dict
    status: TaskState
    dispatched_at: datetime
    deadline_ts: int  # unix millis
    accepted_at: datetime | None = None
    completed_at: datetime | None = None
    progress_step: str | None = None
    progress_ratio: float | None = None
    result: dict | None = None
    error_code: str | None = None
    error_message: str | None = None


class TaskDispatcher:
    """Manages the full task lifecycle."""

    def __init__(self) -> None:
        self._tasks: dict[str, TaskRecord] = {}

    async def dispatch(
        self, device_id: str, kind: str, params: dict, timeout_sec: int = 120
    ) -> str:
        """Dispatch a task to a connected device.

        Returns the generated request_id.
        Raises ValueError if device is offline or doesn't support the kind.
        """
        entry = device_hub.get(device_id)
        if entry is None:
            raise ValueError(f"Device '{device_id}' is not connected")

        if kind not in entry.capabilities:
            raise ValueError(
                f"Device '{device_id}' does not support kind '{kind}'. "
                f"Capabilities: {entry.capabilities}"
            )

        request_id = str(ULID())
        now = datetime.now(timezone.utc)
        deadline_ts = int(time.time() * 1000) + (timeout_sec * 1000)

        record = TaskRecord(
            request_id=request_id,
            device_id=device_id,
            kind=kind,
            params=params,
            status=TaskState.DISPATCHED,
            dispatched_at=now,
            deadline_ts=deadline_ts,
        )
        self._tasks[request_id] = record

        # Send dispatch frame to device
        frame = Frame(
            type="task.dispatch",
            id=f"f_d_{request_id[:8]}",
            ts=int(time.time() * 1000),
            payload=TaskDispatchPayload(
                request_id=request_id,
                kind=kind,
                params=params,
                deadline_ts=deadline_ts,
            ).model_dump(),
        )
        await device_hub.send_frame(device_id, frame)

        # Log to DB
        await persistence.log_task(
            request_id=request_id,
            device_id=device_id,
            kind=kind,
            params=params,
            status="dispatched",
            dispatched_at=now.isoformat(),
        )

        logger.info(
            "task_dispatched",
            request_id=request_id,
            device_id=device_id,
            kind=kind,
            timeout_sec=timeout_sec,
        )
        return request_id

    async def handle_accepted(self, request_id: str) -> None:
        """Device accepted the task."""
        record = self._tasks.get(request_id)
        if not record:
            logger.warning("task_accepted_unknown", request_id=request_id)
            return
        record.status = TaskState.ACCEPTED
        record.accepted_at = datetime.now(timezone.utc)
        await persistence.update_task_log(
            request_id, status="accepted", accepted_at=record.accepted_at.isoformat()
        )
        logger.info("task_accepted", request_id=request_id, device_id=record.device_id)

    async def handle_progress(
        self, request_id: str, step: str | None, ratio: float | None
    ) -> None:
        """Device reported progress."""
        record = self._tasks.get(request_id)
        if not record:
            return
        record.progress_step = step
        record.progress_ratio = ratio

    async def handle_result(
        self, request_id: str, kind: str, result: dict
    ) -> None:
        """Device completed the task with a result."""
        record = self._tasks.get(request_id)
        if not record:
            logger.warning("task_result_unknown", request_id=request_id)
            return

        record.status = TaskState.COMPLETED
        record.completed_at = datetime.now(timezone.utc)
        record.result = result

        elapsed = (record.completed_at - record.dispatched_at).total_seconds()
        logger.info(
            "task_completed",
            request_id=request_id,
            device_id=record.device_id,
            kind=kind,
            elapsed_sec=round(elapsed, 2),
        )

        # Invoke handler
        handler = handler_registry.get(kind)
        if handler is None:
            logger.warning("handler_not_found", kind=kind, request_id=request_id)
            await persistence.update_task_log(
                request_id,
                status="completed",
                completed_at=record.completed_at.isoformat(),
                result_summary=result,
            )
            return

        try:
            validated = handler.result_schema(**result)
            await handler.persist(record.device_id, request_id, validated)
            await persistence.update_task_log(
                request_id,
                status="completed",
                completed_at=record.completed_at.isoformat(),
                result_summary=result,
            )
        except ValidationError as e:
            record.status = TaskState.PERSIST_FAILED
            logger.error(
                "handler_validation_failed",
                request_id=request_id,
                kind=kind,
                errors=e.errors(),
            )
            await persistence.update_task_log(
                request_id,
                status="persist_failed",
                error_message=str(e),
            )
        except Exception as e:
            record.status = TaskState.PERSIST_FAILED
            logger.error(
                "handler_persist_failed",
                request_id=request_id,
                kind=kind,
                error=str(e),
            )
            await persistence.update_task_log(
                request_id,
                status="persist_failed",
                error_message=str(e),
            )

    async def handle_error(
        self, request_id: str, code: str, message: str, retryable: bool
    ) -> None:
        """Device reported a task error."""
        record = self._tasks.get(request_id)
        if not record:
            logger.warning("task_error_unknown", request_id=request_id)
            return
        record.status = TaskState.FAILED
        record.completed_at = datetime.now(timezone.utc)
        record.error_code = code
        record.error_message = message

        logger.error(
            "task_failed",
            request_id=request_id,
            device_id=record.device_id,
            code=code,
            message=message,
            retryable=retryable,
        )
        await persistence.update_task_log(
            request_id,
            status="failed",
            completed_at=record.completed_at.isoformat(),
            error_code=code,
            error_message=message,
        )

    async def run_deadline_check(self) -> None:
        """Background task: check for timed-out tasks every 10s."""
        settings = get_settings()
        while True:
            await asyncio.sleep(settings.deadline_check_interval_sec)
            await self._check_deadlines()

    async def _check_deadlines(self) -> None:
        """Send cancel for tasks past their deadline."""
        now_ms = int(time.time() * 1000)
        for request_id, record in list(self._tasks.items()):
            if record.status in (TaskState.DISPATCHED, TaskState.ACCEPTED):
                if now_ms > record.deadline_ts:
                    logger.warning(
                        "task_timed_out",
                        request_id=request_id,
                        device_id=record.device_id,
                    )
                    record.status = TaskState.TIMED_OUT
                    record.completed_at = datetime.now(timezone.utc)
                    # Send cancel to device
                    try:
                        cancel_frame = Frame(
                            type="task.cancel",
                            ts=now_ms,
                            payload={"request_id": request_id},
                        )
                        await device_hub.send_frame(record.device_id, cancel_frame)
                    except Exception:
                        pass
                    await persistence.update_task_log(
                        request_id,
                        status="timed_out",
                        completed_at=record.completed_at.isoformat(),
                    )

    def get_task(self, request_id: str) -> TaskRecord | None:
        """Look up a task by request_id."""
        return self._tasks.get(request_id)


# Singleton
task_dispatcher = TaskDispatcher()
