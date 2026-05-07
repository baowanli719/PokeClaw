# Operational Scripts

## Prerequisites

```bash
cd cloud
pip install httpx  # already in requirements.txt
```

Set environment:
```bash
export CLOUD_HOST=http://localhost:8080
export ADMIN_TOKEN=dev_admin_token
```

## issue_task.py

Submit a task to a connected device.

```bash
python scripts/issue_task.py \
    --device phone-01 \
    --kind ths.sync_holdings \
    --params '{"account_alias": "main"}'
```

Options:
- `--device` (required): target device_id
- `--kind` (required): task kind
- `--params`: JSON string (default: `{}`)
- `--timeout`: seconds (default: 120)
- `--host`: override CLOUD_HOST
- `--token`: override ADMIN_TOKEN

## list_devices.py

Show currently connected devices.

```bash
python scripts/list_devices.py
```

Options:
- `--host`: override CLOUD_HOST
- `--token`: override ADMIN_TOKEN
