"""Download endpoints for deployable app artifacts."""

from pathlib import Path

from fastapi import APIRouter, HTTPException
from fastapi.responses import FileResponse

from app.config import get_settings

router = APIRouter()

LATEST_APK_NAME = "PokeClaw_latest.apk"


def _artifact_dir() -> Path:
    """Resolve the configured APK artifact directory."""
    configured = Path(get_settings().apk_artifact_dir)
    if configured.is_absolute():
        return configured
    return Path(__file__).resolve().parents[1] / configured


def _apk_response(path: Path) -> FileResponse:
    if not path.is_file():
        raise HTTPException(status_code=404, detail="APK not found")
    return FileResponse(
        path,
        media_type="application/vnd.android.package-archive",
        filename=path.name,
    )


@router.get("/downloads/PokeClaw_latest.apk")
async def download_latest_named_apk() -> FileResponse:
    """Download the stable latest APK filename used by the admin console."""
    return _apk_response(_artifact_dir() / LATEST_APK_NAME)


@router.get("/downloads/apk/latest")
async def download_latest_apk() -> FileResponse:
    """Download the newest APK in the artifact directory."""
    apk_dir = _artifact_dir()
    candidates = sorted(
        (path for path in apk_dir.glob("*.apk") if path.is_file()),
        key=lambda path: path.stat().st_mtime,
        reverse=True,
    )
    if not candidates:
        raise HTTPException(status_code=404, detail="No APK artifacts found")
    return _apk_response(candidates[0])


@router.get("/downloads/apk/{filename}")
async def download_apk(filename: str) -> FileResponse:
    """Download a named APK artifact without allowing path traversal."""
    if "/" in filename or "\\" in filename or not filename.endswith(".apk"):
        raise HTTPException(status_code=404, detail="APK not found")
    return _apk_response(_artifact_dir() / filename)
