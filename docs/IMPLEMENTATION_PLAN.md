# Implementation plan

Status legend: [x] done, [~] partial / implemented but not device-verified, [ ] not done.

## Phase 0 — Environment

- [x] Verified JDK 21 (Android Studio JBR), Android SDK 36, build-tools 36.1.0, Gradle 8.13, AGP 8.11.1.
- [x] Validated the toolchain with a minimal APK before adding features.
- [x] Worked around the AGP non-ASCII path check (`android.overridePathCheck=true`) and identified the
      Windows unit-test argfile issue.
- [ ] Physical device or working emulator (system image incomplete on this machine).

## Phase 1 — Project skeleton

- [x] Gradle Kotlin DSL, version catalog, wrapper.
- [x] Modules `core`, `execution`, `integrations`, `app`.
- [x] Compose Material 3 theme with dynamic color and dark mode.
- [x] Manual DI graph (`AppGraph`), application-scoped runtime.

## Phase 2 — Persistence

- [x] Room database v1 with 11 tables and DAOs.
- [x] Repositories for tasks/messages/tool calls/checkpoints/events/artifacts.
- [x] Repositories for providers, MCP servers, skills, memory, schedules.
- [x] DataStore-backed settings with safe defaults.
- [ ] Room schema export and migrations (v1 uses destructive fallback while pre-release).

## Phase 3 — Shell runtime

- [x] `LocalShellExecutor` with async streams, timeout, cancellation, exit code, output caps.
- [x] Catastrophic command blocklist.
- [x] Shizuku client with status, permission checks, user-service binding and release.
- [x] Shizuku user service (Messenger IPC) running with the shell UID.
- [x] `shell.exec`, `adb.exec`, `runtime.status` tools.
- [~] Shizuku end-to-end on a real device.

## Phase 4 — LLM

- [x] OpenAI-compatible client (streaming SSE, tool calls, usage, errors).
- [x] Capability probe and provider persistence.
- [x] Role router (chat/planning/execution/vision/summary).
- [x] Vision requests with image data URLs.
- [ ] Live-key request verification.

## Phase 5 — Agent harness

- [x] Task state machine with explicit transitions.
- [x] Planner with persisted plans and skip heuristic for simple goals.
- [x] Orchestrator loop with streaming, tool calls, retries, verification, artifacts, events.
- [x] Policy engine + blocking approval bus with timeout rejection.
- [x] Checkpoint manager, recovery note, interrupted-task pausing.
- [x] Context manager with transcript folding and budget trimming.
- [x] Memory manager (keyword retrieval; embedding hook defined).
- [x] Diagnostics service.
- [ ] Harness integration test with a fake model (unit tests cover pure components only).

## Phase 6 — Tools and integrations

- [x] Device, app, file, HTTP tools with sandboxing and SSRF guard.
- [x] Accessibility bridge + UI tree + actions with verification.
- [x] Screenshot capture and vision tool.
- [x] MCP Streamable HTTP client, manager, registry adapters.
- [x] Skills loader, YAML parser, zip import, six bundled skills, script runner.
- [x] Memory and schedule tools.
- [ ] stdio MCP transport (runtime unavailable on Android by default).
- [ ] Embedded Python/Node runtime.

## Phase 7 — Background execution

- [x] Foreground service with stop action while tasks run.
- [x] WorkManager periodic schedules with unique names and rate limits.
- [x] Boot receiver restoring schedules and pausing interrupted tasks.
- [~] Long-running scheduled tasks beyond the worker window (task continues in-process; if the
      process dies it is paused).
- [ ] Expedited work / battery-optimization guidance.

## Phase 8 — UI

- [x] Navigation drawer + bottom bar + 14 screens.
- [x] Chat with stable input, streaming draft, tool trace, stop/resume.
- [x] Tasks with plan, tool calls, events, checkpoints, artifacts, export.
- [x] Terminal with local/Shizuku modes and cancellation.
- [x] Device, Shizuku guide, accessibility guide.
- [x] MCP and skills management.
- [x] Models with add/edit/probe/activate.
- [x] Settings, diagnostics, memory, workspace, logs.
- [x] Global approval dialog.
- [ ] Compose UI tests on a device.

## Phase 9 — Verification and delivery

- [x] Debug APK builds.
- [x] 28 JVM unit tests passing (ASCII path).
- [x] Test report and limitations documented.
- [ ] Real-device test matrix (Shizuku, accessibility, notifications, reboot).
- [ ] Live provider integration test.
