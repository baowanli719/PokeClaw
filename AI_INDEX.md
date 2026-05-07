# PokeClaw AI Index

This is the repo map for coding agents. Keep canonical information in existing files; do not create new root docs when one of these files already owns the topic.

## Canonical Root Docs

| File | Owns |
|------|------|
| `README.md` | Public product direction, roadmap, platform constraints, benchmark claims, changelog |
| `CLAUDE.md` | Agent/project working rules |
| `QA_CHECKLIST.md` | QA methodology, release gate, test cases, debug changelog |
| `RELEASING.md` | Release signing, tag workflow, stable APK publishing |
| `BACKLOG.md` | Prioritized bugs, features, QA gaps, ideas |
| `ARCHITECTURE_RECONSTRUCTION.md` | Historical architecture reconstruction plan and refactor guardrails |
| `CLA.md` | Contributor license agreement |
| `AI_INDEX.md` | This repo map |

## Directory Map

| Path | Purpose |
|------|---------|
| `app/src/main/java/io/agents/pokeclaw/` | Android app source |
| `app/src/main/assets/playbooks/` | Built-in playbooks used by the agent harness |
| `app/src/test/` | JVM/unit regression tests |
| `scripts/` | QA and automation scripts |
| `.github/workflows/` | CI, CLA check, signed release workflow |
| `docs/` | GitHub Pages site and public site assets |
| `docs/screenshots/` | Screenshot assets used by the landing page |
| `demo/` | Legacy demo GIF assets |
| `Screenshots/` | Legacy screenshot assets |
| `prototype/` | Historical UI prototypes |
| `mockup/` | Early interactive mockups |
| `signatures/` | CLA signature state |

## Cloud Bridge

PokeClaw includes a Cloud Bridge client that maintains a persistent WebSocket connection to the PokeClaw Cloud Bridge Service, enabling cloud-initiated task dispatch to the device.

### Package Structure

The Bridge lives in `io.agents.pokeclaw.bridge` with the following sub-packages:

| Sub-package | Purpose |
|---|---|
| `bridge/` (root) | `CloudBridgeClient` facade, `ConnectionState` sealed class |
| `bridge/api/` | Four injected interfaces: `TaskExecutor`, `CapabilityProvider`, `ConfigSource`, `BridgeLogger` |
| `bridge/protocol/` | `Frame` sealed class hierarchy, `FrameCodec` (Gson), payload data classes |
| `bridge/connection/` | `ConnectionManager`, `BackoffPolicy`, `NetworkMonitor` |
| `bridge/task/` | `TaskBridge`, `InFlightTask` |
| `bridge/queue/` | `OfflineOutbox` (jsonl-backed terminal-frame store) |
| `bridge/internal/` | `Clock`, `BridgeDispatcher` (test seams) |

App-side adapters live in `io.agents.pokeclaw.cloudbridge` (outside the bridge boundary):

- `TaskOrchestratorExecutorAdapter` — implements `TaskExecutor`
- `AppCapabilityProviderAdapter` — implements `CapabilityProvider`
- `KVUtilsConfigSource` — implements `ConfigSource`
- `XLogBridgeLogger` — implements `BridgeLogger`

### Public Facade

`CloudBridgeClient` is the only public entry point. It exposes:

- `start()` / `stop()` — idempotent lifecycle control
- `reconfigure()` — re-reads config and reconnects if parameters changed
- `observeState(): StateFlow<ConnectionState>` — hot state stream for UI
- `currentState(): ConnectionState` — snapshot read, thread-safe

### Boundary Enforcement

A custom detekt rule (`BridgeBoundaryRule`) enforces that no file inside `io.agents.pokeclaw.bridge` imports concrete classes from other `io.agents.pokeclaw.*` sub-packages. The Gradle task `checkBridgeBoundary` (via `./gradlew detekt`) runs in CI and blocks PRs on violation.

### Related Spec

Full design and requirements: `.kiro/specs/android-cloud-bridge/`

### Configuration Keys

| Key | Type | Description |
|---|---|---|
| `cloud_bridge_url` | String | WebSocket server URL (e.g. `wss://bridge.pokeclaw.dev/ws/device`). Empty or absent → Bridge stays `DISCONNECTED`, no connection attempt. |
| `cloud_bridge_device_token` | String | Bearer token for device authentication. Empty or absent → Bridge stays `DISCONNECTED`, no connection attempt. |

**Token masking policy**: The Bridge never logs the token in plaintext. All log output masks the token as `***<last4>` (e.g. a token ending in `ab3f` appears as `***ab3f`). This is enforced at the `BridgeLogger` adapter level and verified by property tests.

## Direction Rules

- PokeClaw is a generic Android mobile-agent harness with a product shell on top.
- Prefer fixing deterministic harness/runtime/device problems before tuning one stochastic task.
- Keep prompts, tools, skills, and playbooks generic.
- Treat Cloud/Local exploratory task success as a repeated-trial metric, not a single-run truth.
- For GPU/local runtime reports, collect logs and keep CPU fallback truthful before changing backend selection.
