#!/usr/bin/env python3
"""List currently connected devices via the REST control plane.

Usage:
    python scripts/list_devices.py

Environment:
    CLOUD_HOST  — Base URL of the cloud bridge (default: http://localhost:8080)
    ADMIN_TOKEN — Admin bearer token (or use --token flag)
"""

import os
import sys

import httpx


def main():
    import argparse
    parser = argparse.ArgumentParser(description="List connected PokeClaw devices")
    parser.add_argument("--host", default=None, help="Cloud bridge base URL")
    parser.add_argument("--token", default=None, help="Admin token")
    args = parser.parse_args()

    host = args.host or os.environ.get("CLOUD_HOST", "http://localhost:8080")
    token = args.token or os.environ.get("ADMIN_TOKEN")
    if not token:
        print("ERROR: ADMIN_TOKEN env var or --token flag required", file=sys.stderr)
        sys.exit(1)

    resp = httpx.get(
        f"{host}/api/devices",
        headers={"Authorization": f"Bearer {token}"},
        timeout=10,
    )

    if resp.status_code != 200:
        print(f"✗ Failed ({resp.status_code}): {resp.text}", file=sys.stderr)
        sys.exit(1)

    devices = resp.json()
    if not devices:
        print("No devices connected.")
        return

    print(f"{'Device ID':<20} {'Version':<12} {'Capabilities':<30} {'Busy':<6} {'Last Seen'}")
    print("-" * 90)
    for d in devices:
        caps = ", ".join(d["capabilities"])
        print(f"{d['device_id']:<20} {d['app_version']:<12} {caps:<30} {d['busy']!s:<6} {d['last_seen']}")


if __name__ == "__main__":
    main()
