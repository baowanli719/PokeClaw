# PokeClaw Project Direction

This file is the source of truth for product and engineering direction. Read it before changing prompts, skills, task routing, local runtime, QA, or release behavior.

## North Star

PokeClaw is a generic Android mobile-agent harness with a usable product shell on top.

The goal is not to make one hand-picked task pass by hardcoding that task. The goal is to make the harness reliable enough that different models can observe the phone, choose tools, recover from normal device conditions, and complete broad classes of tasks.

## What We Optimize

Prioritize these before narrow workflow tuning:

1. **Runtime and hardware correctness**
   - model download and storage
   - LiteRT local runtime startup and teardown
   - GPU to CPU fallback truthfulness
   - foreground service survival
   - accessibility and notification listener connection state
   - install, signing, updater, and release artifact correctness

2. **Generic harness quality**
   - screen observation quality
   - tool result truthfulness
   - task lifecycle cleanup
   - cancellation and timeout behavior
   - chat/task result bridge
   - reusable tool APIs and playbooks
   - QA harness reliability

3. **Device-specific evidence**
   - debug ZIPs, logcat, app logs, ROM details, and reproducible ADB flows beat guessing
   - fix concrete device/runtime failures when logs show the harness is wrong
   - ask for a fresh debug ZIP after every release that changes the relevant path

## What We Do Not Optimize First

Do not overfit PokeClaw to one stochastic model task.

The following are not automatically product bugs:

- one Cloud model fails one exploratory workflow once
- one Local model cannot reason through a complex app flow
- a prompt needs many attempts to choose the right cross-app path
- a workflow depends on app UI variance that the model can sometimes solve and sometimes cannot

Treat these as model-performance or exploratory-agent limits unless there is evidence that the harness blocked the model.

Examples of real harness bugs:

- the model could not observe the relevant screen text
- a tool returned success when it actually failed
- a direct parser routed the task to the wrong tool
- task state leaked into the next run
- Accessibility or foreground service died during a normal task
- download/runtime/storage failed before the model could run
- GPU fallback was mislabeled, crashed, or hid the real reason

## Prompt and Skill Policy

Prompts, skills, and playbooks must stay generic.

Add structure only when it improves a reusable class of tasks. Do not add one-off prompt hacks for a single user report unless the pattern is general and can be verified across related tasks.

Good changes:

- a generic send-message parser that rejects unsafe ambiguous targets
- a reusable close-button detector that avoids false matches
- better task cancellation and cleanup across every task
- clearer model/runtime diagnostics in bug reports

Bad changes:

- "if this exact QA prompt appears, tap this coordinate"
- app-specific magic that only makes one demo pass
- prompt drilling until one model happens to pass one flaky flow
- changing GPU behavior without logs showing the current selection/fallback is wrong

## GPU and Local Runtime Policy

GPU issues are hardware and runtime issues first, not prompt issues.

When users report "AI Edge Studio uses GPU but PokeClaw uses CPU":

1. collect device, ROM, model, LiteRT, and debug ZIP data
2. log which backend was selected and why
3. log exact delegate init/fallback errors
4. keep CPU fallback safe and truthful
5. only change selection logic when the evidence shows the harness picked the wrong path

Do not force GPU as a blind fix. A slow CPU fallback is better than a crash or a fake GPU label.

## QA and Release Policy

Release rhythm:

1. fix concrete harness or hardware/runtime bugs
2. update `QA_CHECKLIST.md`
3. run the relevant regression bundle
4. batch fixes into a stable signed release
5. ask affected users to retest that stable version
6. use fresh user debug ZIPs for the next round

Release gates distinguish deterministic harness behavior from model-dependent exploration:

- deterministic runtime, install, storage, permissions, direct-tool, and state-truth flows should be effectively `10/10`
- exploratory Cloud and Local tasks should be measured by repeated-trial success rate
- do not block a harness hotfix release only because a generic exploratory task is below target, unless that task is being promoted as a headline capability
- never claim a release is fully QA-passed when known timeouts or blocked cases remain

## Issue Triage Policy

When triaging user issues:

1. classify the report as harness/runtime, device/ROM, model-performance, UX/documentation, or feature request
2. fix harness/runtime bugs first
3. avoid arguing with model-performance limits; document the limitation and gather data
4. release stable builds before asking many users to retest
5. keep issue comments honest about what was fixed and what remains open

## Current Focus

As of 2026-04-28, the next work should focus on:

- analyzing model download/debug ZIP evidence such as issue `#39`
- improving local runtime and GPU diagnostics without blindly changing backend behavior
- making `QA_CHECKLIST.md` a formal release gate
- keeping prompts and skills generic instead of tuning for one flaky task
