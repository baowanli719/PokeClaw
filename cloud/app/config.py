"""Application configuration loaded from environment variables."""

from functools import lru_cache

from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    """All configuration is externalized to environment variables.

    Required:
        OB_DSN: SQLAlchemy async DSN for OceanBase (mysql+asyncmy://...)
        DEVICE_TOKENS: Comma-separated bearer tokens, one per device.
        ADMIN_TOKEN: Bearer token for REST control plane access.

    Optional:
        LOG_LEVEL: Python log level name. Default: INFO.
        HEARTBEAT_INTERVAL_SEC: Seconds between expected heartbeats. Default: 30.
        STALE_TIMEOUT_SEC: Seconds without any frame before closing connection. Default: 90.
        DEADLINE_CHECK_INTERVAL_SEC: How often to sweep for timed-out tasks. Default: 10.
    """

    # Required
    ob_dsn: str
    device_tokens: str  # comma-separated
    admin_token: str

    # Optional
    log_level: str = "INFO"
    heartbeat_interval_sec: int = 30
    stale_timeout_sec: int = 90
    deadline_check_interval_sec: int = 10

    @property
    def device_token_set(self) -> set[str]:
        """Parse comma-separated device tokens into a set."""
        return {t.strip() for t in self.device_tokens.split(",") if t.strip()}

    class Config:
        env_file = ".env"
        env_file_encoding = "utf-8"
        case_sensitive = False


@lru_cache
def get_settings() -> Settings:
    """Cached settings singleton. Raises ValidationError if required vars are missing."""
    return Settings()  # type: ignore[call-arg]
