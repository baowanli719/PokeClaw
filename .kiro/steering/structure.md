# PokeClaw — Project Structure

## Root Layout

```
PokeClaw/
├── app/                          # Single Gradle module (all source)
├── gradle/libs.versions.toml     # Version catalog
├── settings.gradle.kts           # Project settings
├── scripts/                      # QA and automation scripts
├── docs/                         # GitHub Pages site
├── .github/workflows/            # CI (build, release, CLA)
├── .kiro/specs/                  # Spec-driven development docs
├── .kiro/steering/               # Steering rules (this directory)
├── README.md                     # Product direction, roadmap, changelog
├── CLAUDE.md                     # Agent/project working rules
├── AI_INDEX.md                   # Repo map for coding agents
├── QA_CHECKLIST.md               # E2E test cases + debug changelog
├── BACKLOG.md                    # Prioritized bugs, features, ideas
├── ARCHITECTURE_RECONSTRUCTION.md # Refactor guardrails
└── RELEASING.md                  # Signing and release workflow
```

## Source Package Map (`app/src/main/java/io/agents/pokeclaw/`)

| Package | Purpose |
|---------|---------|
| `(root)` | App entry, `AppViewModel`, `TaskOrchestrator`, `TaskSessionStore`, `TaskEvent` |
| `agent/` | Core agent logic — `AgentService`, `TaskClassifier`, `PipelineRouter`, `PlaybookManager`, `StuckDetector`, `TaskBudget` |
| `agent/llm/` | LLM client abstraction — `LlmClient` interface, OpenAI/Anthropic/Local implementations, `LocalModelRuntime`, `EngineHolder` |
| `agent/knowledge/` | Knowledge base tools (read, write, search, append, add-todo) |
| `agent/langchain/` | LangChain4j tool bridge + OkHttp HTTP adapter for Android |
| `agent/skill/` | Skill system — `Skill`, `SkillRegistry`, `SkillExecutor`, `BuiltInSkills` |
| `automation/` | External automation entrypoints (Tasker, MacroDroid, ADB) |
| `base/` | Base Activity/App classes |
| `bridge/` | **Cloud Bridge** — architecturally isolated WebSocket client |
| `bridge/api/` | Bridge interfaces (`TaskExecutor`, `CapabilityProvider`, `ConfigSource`, `BridgeLogger`) |
| `bridge/connection/` | Connection management, backoff, heartbeat, network monitor |
| `bridge/protocol/` | Frame sealed class, codec, payload data classes |
| `bridge/queue/` | Offline outbox (JSONL persistence) |
| `bridge/task/` | Task bridge, in-flight task tracking |
| `cloudbridge/` | App-side adapters implementing Bridge interfaces (outside bridge boundary) |
| `channel/` | Messaging channel handlers (Discord, Telegram, WeChat) |
| `floating/` | Floating circle/pill overlay |
| `server/` | Embedded HTTP config server (NanoHTTPD) |
| `service/` | Android services — Accessibility, Notification Listener, Foreground, KeepAlive, AutoReply |
| `tool/` | Tool framework — `BaseTool`, `ToolRegistry`, `ToolParameter`, `ToolResult` |
| `tool/impl/` | Concrete tools (tap, swipe, input, open app, send message, screenshot, etc.) |
| `ui/chat/` | Chat UI (Compose) — `ChatScreen`, `ComposeChatActivity`, `ConversationStore` |
| `ui/settings/` | Settings screens |
| `ui/guide/` | Onboarding |
| `ui/splash/` | Splash screen |
| `utils/` | Logging (XLog), KV storage, contact matching, UI text matching |
| `widget/` | Reusable UI widgets |

## Key Architectural Boundaries

- **Cloud Bridge isolation**: The `bridge/` package must NOT import from other `io.agents.pokeclaw.*` packages. Enforced by a custom detekt rule (`BridgeBoundaryRule`) and CI.
- **App-side adapters** in `cloudbridge/` implement Bridge interfaces and live outside the boundary.
- **Single source of truth**: `CloudBridgeClient` is the only public facade for the Bridge subsystem.

## Assets

```
app/src/main/assets/
├── playbooks/       # Built-in agent playbooks (markdown)
├── web/             # Embedded HTML (debug UI, index)
└── .pcfp            # Fingerprint file
```

## Test Layout

```
app/src/test/java/io/agents/pokeclaw/
├── agent/           # Guard tests, TaskParser, prompt envelope
├── automation/      # External automation contract tests
├── bridge/          # Cloud Bridge unit + property tests
├── cloudbridge/     # Adapter tests with fakes
├── ui/chat/         # Chat UI logic tests
└── utils/           # Utility tests (contact match, noise filter, text match)
```
