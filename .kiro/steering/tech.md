# PokeClaw — Tech Stack & Build

## Language & Platform

- **Primary language:** Kotlin (some legacy Java in tool bridge layer)
- **Platform:** Android (single-module Gradle project, `:app` only)
- **Java compatibility:** Java 17
- **Min SDK:** 28 (Android 9) | **Target SDK:** 36 | **Compile SDK:** 36

## Build System

- Gradle with Kotlin DSL (`build.gradle.kts`)
- Android Gradle Plugin (AGP) 9.1.0
- Version catalog at `gradle/libs.versions.toml`
- Signing config read from `local.properties` or environment variables

## Key Libraries

| Category | Library | Version |
|----------|---------|---------|
| UI | Jetpack Compose (BOM 2025.05.00), Material3 | — |
| AI (local) | LiteRT-LM (`com.google.ai.edge.litertlm`) | 0.10.0 |
| AI (cloud) | LangChain4j (core, OpenAI, Anthropic) | 1.12.2 |
| Networking | OkHttp + Retrofit + Gson | 4.12.0 / 2.11.0 |
| Storage | MMKV (Tencent) | 2.3.0 |
| Lifecycle | AndroidX Lifecycle (runtime-ktx, viewmodel-ktx) | 2.6.2 |
| Images | Glide | 5.0.5 |
| Floating UI | EasyFloat | 2.0.4 |
| HTTP server | NanoHTTPD (embedded config server) | 2.3.1 |
| Static analysis | detekt | 1.23.6 |
| Testing | JUnit 5, JQwik (property-based), MockWebServer, AssertJ | — |
| Messaging SDKs | Lark/Feishu OAPI, DingTalk stream client | — |

## Common Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Run unit tests (JUnit 5 platform)
./gradlew test

# Run detekt (includes Bridge boundary check)
./gradlew detekt

# Full check (lint + detekt + tests)
./gradlew check
```

## Testing

- Unit tests live in `app/src/test/` — JUnit 5 platform via `useJUnitPlatform()`
- Property-based tests use JQwik (see `.jqwik-database` in app root)
- Instrumented tests in `app/src/androidTest/` (Espresso)
- E2E QA is ADB-driven (see `QA_CHECKLIST.md`) — not mocked, simulates real user behavior

## Static Analysis

- detekt with a custom `BridgeBoundaryRule` that prevents the Cloud Bridge package from importing host app classes
- Runs in CI and blocks PRs on violation

## CI/CD

- GitHub Actions: `build.yml`, `release.yml`, `cla.yml`
- Release uses signed APK workflow (see `RELEASING.md`)
