# Mobile Agent

**A native Android AI agent that can chat and actually execute tasks.**

Mobile Agent turns a natural-language goal into a plan, calls policy-gated tools, verifies results, and keeps a resumable audit trail. No root. Shizuku and accessibility are optional, explicitly granted capabilities.

> Status: **experimental / pre-release**. The APK builds and 48 JVM tests pass, but the app has **not** been run on a physical device or with a live API key. See [verification](README.md#验证状态).

## Highlights

- **Native Kotlin + Compose** — foreground service, WorkManager, accessibility, Shizuku.
- **Chat and agent execution are separate** — plain chat sends no tools and does not depend on MCP, Shizuku or accessibility.
- **One policy gate** — the model cannot escalate privileges; high-risk actions require confirmation.
- **Resumable tasks** — a checkpoint after every tool call; interrupted tasks pause and require verification before replay.
- **Verified capabilities only** — model abilities come from real probes, not model names.

## The DeepSeek fix

Earlier builds could not chat with DeepSeek at all. Root cause: every agent request included tools whose names contained dots (`file.read`), which violates DeepSeek's function-name rule (`a-z A-Z 0-9 _ -`). The API rejected the request with HTTP 400 before the model ran, and the harness hid the error behind a generic failure.

Fixes: wire-name sanitization with reverse mapping, a dedicated tool-free chat engine, staged connection tests, documented provider presets, and actionable error classes.

Read the full analysis: [`docs/DEEPSEEK_FIX.md`](docs/DEEPSEEK_FIX.md).

## Quick start

```bash
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=$HOME/Android/Sdk
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On first launch: choose **DeepSeek**, paste an API key, keep `deepseek-flash`, press **Test connection** until a real text answer is returned, then **Save and start chatting**.

## Documentation

- [Architecture](docs/ARCHITECTURE.md)
- [Installation](docs/INSTALL.md)
- [User guide](docs/USER_GUIDE.md)
- [API configuration](docs/API_CONFIG.md)
- [DeepSeek root cause](docs/DEEPSEEK_FIX.md)
- [UI/UX](docs/UI_UX.md)
- [Test report](docs/TEST_REPORT.md)
- [Known limitations](docs/KNOWN_LIMITATIONS.md)
- [Roadmap](docs/ROADMAP.md)

## Security

API keys are encrypted with Android Keystore AES/GCM; approvals time out to rejection; HTTP is restricted to public hosts; shell commands are sandboxed and catastrophic patterns are blocked. See [SECURITY.md](SECURITY.md).

## License

MIT © 2026 ForrestGump618
