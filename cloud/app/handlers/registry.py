"""Handler registry: maps task kinds to validation schemas and persist functions."""

from dataclasses import dataclass
from typing import Awaitable, Callable

import structlog
from pydantic import BaseModel
from sqlalchemy.ext.asyncio import AsyncSession

logger = structlog.get_logger()

# Type alias for the persist function signature
PersistFn = Callable[[str, str, BaseModel], Awaitable[None]]
# persist(device_id, request_id, validated_result) -> None


@dataclass
class Handler:
    """A handler for a specific task kind."""
    result_schema: type[BaseModel]
    persist: PersistFn


class HandlerRegistry:
    """Registry of task kind handlers. Adding a new kind requires only
    a Pydantic schema and an async persist function."""

    def __init__(self) -> None:
        self._handlers: dict[str, Handler] = {}

    def register(self, kind: str, handler: Handler) -> None:
        """Register a handler for a task kind."""
        self._handlers[kind] = handler
        logger.info("handler_registered", kind=kind)

    def get(self, kind: str) -> Handler | None:
        """Look up a handler by kind. Returns None if not registered."""
        return self._handlers.get(kind)

    def supported_kinds(self) -> list[str]:
        """List all registered task kinds."""
        return list(self._handlers.keys())


# Singleton
handler_registry = HandlerRegistry()
