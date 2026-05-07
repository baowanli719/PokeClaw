"""Entry point: python -m app"""

import sys

from pydantic import ValidationError


def main() -> None:
    try:
        from app.config import get_settings
        get_settings()  # Validate config before starting uvicorn
    except ValidationError as e:
        print("ERROR: Missing or invalid environment configuration:", file=sys.stderr)
        for err in e.errors():
            field = ".".join(str(loc) for loc in err["loc"])
            print(f"  - {field}: {err['msg']}", file=sys.stderr)
        print("\nSee .env.example for required variables.", file=sys.stderr)
        sys.exit(1)

    import uvicorn
    uvicorn.run("app.main:app", host="0.0.0.0", port=8080, log_level="info")


if __name__ == "__main__":
    main()
