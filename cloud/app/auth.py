"""Authentication service for device (WebSocket) and admin (REST) tokens."""

from fastapi import Depends, HTTPException, Request, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from app.config import get_settings

_bearer_scheme = HTTPBearer(auto_error=False)


class AuthService:
    """Validates device and admin bearer tokens."""

    def __init__(self) -> None:
        settings = get_settings()
        self._device_tokens: set[str] = settings.device_token_set
        self._admin_token: str = settings.admin_token

    def validate_device_token(self, token: str) -> bool:
        """Check if a device token is in the allowed set."""
        return token in self._device_tokens

    def validate_admin_token(self, token: str) -> bool:
        """Check if the admin token matches."""
        return token == self._admin_token

    @staticmethod
    def mask_token(token: str) -> str:
        """Mask a token for safe log output: ***{last4}."""
        if len(token) <= 4:
            return "***"
        return f"***{token[-4:]}"


def get_auth_service() -> AuthService:
    """Factory for AuthService (can be overridden in tests)."""
    return AuthService()


async def require_admin(
    request: Request,
    credentials: HTTPAuthorizationCredentials | None = Depends(_bearer_scheme),
) -> None:
    """FastAPI dependency that enforces admin token on REST endpoints.

    Raises HTTP 401 if the token is missing or invalid.
    """
    if credentials is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Missing Authorization header",
            headers={"WWW-Authenticate": "Bearer"},
        )
    auth = get_auth_service()
    if not auth.validate_admin_token(credentials.credentials):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid admin token",
            headers={"WWW-Authenticate": "Bearer"},
        )
