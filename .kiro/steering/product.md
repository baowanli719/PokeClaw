# PokeClaw — Product Summary

PokeClaw (PocketClaw) is an open-source Android app that turns a phone into an AI-operated device. It is a **mobile agent harness** — not a chatbot with phone tricks bolted on.

## What It Does

- Runs Gemma 4 on-device for local, private phone automation (no account or API key needed)
- Optionally connects to cloud models (OpenAI GPT-4.1, Anthropic Claude) for harder tasks
- Reads the screen via Accessibility Service, picks tools, operates apps, and completes multi-step workflows autonomously
- Monitors messaging apps (WhatsApp, Telegram, WeChat, LINE, SMS) for context-aware auto-reply
- Exposes an external automation API for Tasker, MacroDroid, and ADB triggers
- Includes a Cloud Bridge for cloud-initiated task dispatch over WebSocket

## Product Principles

- PokeClaw is a **generic harness**, not a collection of hardcoded demo flows
- Prefer fixing deterministic harness/runtime/device problems before tuning stochastic model tasks
- Keep prompts, tools, skills, and playbooks generic
- Treat exploratory task success as a repeated-trial metric, not a single-run truth
- Local-first when you want it, optional cloud when you need it

## Target

- Android 9+ (API 28), arm64, 8GB+ RAM recommended
- Current version: v0.6.12
