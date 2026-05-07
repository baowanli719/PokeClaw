#!/usr/bin/env python3
"""Issue a task to a connected device via the REST control plane.

Usage:
    python scripts/issue_task.py --device phone-01 --kind ths.sync_holdings --params '{"account_alias":"main"}'

Environment:
    CLOUD_HOST  — Base URL of the cloud bridge (default: http://localhost:8080)
    ADMIN_TOKEN — Admin bearer token (or use --token flag)
"""

import argparse
import json
import os
import sys

import httpx


def main():
    parser = argparse.ArgumentParser(description="Issue a task to a PokeClaw device")
    parser.add_argument("--device", required=True, help="Target device_id")
    parser.add_argument("--kind", required=True, help="Task kind (e.g. ths.sync_holdings)")
    parser.add_argument("--params", default="{}", help="JSON params string")
    parser.add_argument("--timeout", type=int, default=120, help="Timeout in seconds")
    parser.add_argument("--host", default=None, help="Cloud bridge base URL")
    parser.add_argument("--token", default=None, help="Admin token")
    args = parser.parse_args()

    host = args.host or os.environ.get("CLOUD_HOST", "http://localhost:8080")
    token = args.token or os.environ.get("ADMIN_TOKEN")
    if not token:
        print("ERROR: ADMIN_TOKEN env var or --token flag required", file=sys.stderr)
        sys.exit(1)

    try:
        params = json.loads(args.params)
    except json.JSONDecodeError as e:
        print(f"ERROR: Invalid JSON in --params: {e}", file=sys.stderr)
        sys.exit(1)

    body = {
        "device_id": args.device,
        "kind": args.kind,
        "params": params,
        "timeout_sec": args.timeout,
    }

    resp = httpx.post(
        f"{host}/api/tasks",
        json=body,
        headers={"Authorization": f"Bearer {token}"},
        timeout=10,
    )

    if resp.status_code == 200:
        data = resp.json()
        print(f"✓ Task dispatched: request_id={data['request_id']}")
    else:
        print(f"✗ Failed ({resp.status_code}): {resp.text}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
