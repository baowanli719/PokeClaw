"""PokeClaw Cloud Bridge — FastAPI application entry point."""

from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.config import get_settings
from app.logging_setup import setup_logging


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Application lifespan: startup and shutdown hooks."""
    settings = get_settings()
    setup_logging(settings.log_level)

    # Import here to avoid circular imports during module load
    from app.db.engine import db_engine
    from app.handlers.setup import register_handlers
    from app.hub.device_hub import device_hub
    from app.hub.task_dispatcher import task_dispatcher

    # 1. Initialize database
    await db_engine.init(settings.ob_dsn)

    # 2. Ensure tables exist
    from app.db.engine import ensure_tables
    await ensure_tables()

    # 3. Register task handlers
    register_handlers()

    # 4. Start background tasks
    import asyncio
    stale_task = asyncio.create_task(device_hub.run_stale_sweep())
    deadline_task = asyncio.create_task(task_dispatcher.run_deadline_check())

    yield

    # Shutdown
    stale_task.cancel()
    deadline_task.cancel()
    await db_engine.dispose()


def create_app() -> FastAPI:
    """Create and configure the FastAPI application."""
    app = FastAPI(
        title="PokeClaw Cloud Bridge",
        version="0.1.0",
        lifespan=lifespan,
    )

    from app.api.routes import router as api_router
    from app.ws.endpoint import router as ws_router

    app.include_router(ws_router)
    app.include_router(api_router)

    return app


app = create_app()
