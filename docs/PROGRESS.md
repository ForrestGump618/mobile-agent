# Progress log

Updated after the build/test run in this workspace. This file records what is real, not what was
attempted.

| Area | Files | Status | Verification |
| --- | --- | --- | --- |
| Gradle project | `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, wrapper | Done | `:app:assembleDebug` passes |
| Domain models | `core/.../model/*.kt` | Done | compiled |
| Room persistence | `core/.../data/db/*`, `core/.../data/*Repository.kt` | Done | compiled (KSP), runtime DB not opened on a device |
| Settings | `core/.../settings/SettingsStore.kt` | Done | compiled |
| Secrets | `core/.../security/SecretVault.kt` | Done | compiled; Keystore not exercised on device |
| Policy/approvals | `core/.../security/PolicyEngine.kt`, `ApprovalBus.kt` | Done | `ApprovalBusTest` (2 tests) |
| Redaction | `core/.../security/Redactor.kt` | Done | `RedactorTest` (2 tests) |
| LLM client | `core/.../llm/OpenAiCompatibleClient.kt`, `ModelRouter.kt` | Done | compiled; no live API call |
| State machine | `core/.../agent/TaskStateMachine.kt` | Done | `TaskStateMachineTest` (5 tests) |
| Planner | `core/.../agent/Planner.kt` | Done | compiled; no live model call |
| Orchestrator | `core/.../agent/AgentOrchestrator.kt` | Done | compiled; not executed end-to-end |
| Context/checkpoints/recovery | `ContextManager.kt`, `CheckpointManager.kt`, `AgentRuntime.kt` | Done | compiled; recovery flow not device-tested |
| Verification | `core/.../agent/ResultVerifier.kt` | Done | compiled |
| Memory | `core/.../memory/MemoryManager.kt` | Done (keyword) | compiled |
| Diagnostics | `core/.../agent/DiagnosticsService.kt` | Done | compiled |
| Local shell | `execution/.../shell/LocalShellExecutor.kt` | Done | policy tests; executor not run on device |
| Shizuku | `execution/.../shell/ShizukuShellExecutor.kt`, `shizuku/ShizukuShellService.kt` | Done | compiled; not device-verified |
| Workspace/files | `execution/.../workspace/Workspace.kt`, `tools/FileTools.kt` | Done | compiled |
| HTTP | `execution/.../tools/HttpTool.kt` | Done | `UrlPolicyTest` (4 tests) |
| Device/apps | `execution/.../tools/DeviceTools.kt` | Done | compiled |
| Accessibility | `execution/.../accessibility/AccessibilityBridge.kt` | Done | compiled; not device-verified |
| UI tools | `execution/.../tools/UiTools.kt` | Done | compiled |
| Vision | `execution/.../tools/VisionTool.kt` | Done | compiled; requires opt-in + vision model |
| MCP | `integrations/.../mcp/*` | Done | `StreamableHttpMcpClientTest` (3 tests) |
| Skills | `integrations/.../skills/*`, `assets/skills/*` | Done | `MiniYamlTest` (4 tests); seeding not run on device |
| Tool schemas/validation | `execution/.../tools/Schemas.kt`, `core/.../tools/ToolArgValidator.kt` | Done | `ToolArgValidatorTest` (5 tests) |
| DI graph | `app/.../AppGraph.kt` | Done | compiled |
| Services/workers | `app/.../service/*`, `app/.../work/*` | Done | compiled; not device-verified |
| UI | `app/.../ui/**` | Done | compiled; not rendered on a device |
| Docs | `README.md`, `docs/*` | Done | — |

## Build history

1. Minimal skeleton APK: success (3m29s).
2. Core module: success after fixing the record `iteration` field and diagnostics destructuring.
3. Execution module: success after replacing AIDL with a Messenger user service (AIDL compilation
   fails on this non-ASCII path) and correcting `rikka.shizuku` imports.
4. Integrations: success after fixing the small YAML parser generics.
5. App: success after adding transitive compile dependencies and fixing two nullable smart casts.
6. Unit tests: 28 pass in an ASCII path; fail to load in the original Chinese path due to Gradle's
   worker argfile encoding (documented).

## Next steps (in priority order)

1. Run on a physical Android 14–16 device: install, add provider, probe, run `device.info` and a
   Shizuku command, enable accessibility and run a UI snapshot.
2. Add an instrumented harness test with a fake `LlmClient` and fake tools to exercise the full loop.
3. Verify scheduled work across reboot and Doze.
4. Add a FileProvider for sharing exported artifacts from the UI.
5. Consider Room migrations before any production release.

---

# 第二轮更新（中文化、DeepSeek 修复、UI/UX）

## 根因与修复

- 定位 DeepSeek 400 根因：工具 function name 含点号，违反官方 `[A-Za-z0-9_-]` 约束；请求在模型前被拒绝，
  且 Harness 吞掉了 HTTP 错误。
- 新增 `ToolNames` + 注册表 wire name 映射，请求与响应双向转换；工具调用恢复正常。
- 新增独立 `ChatEngine`（tools 为空），普通聊天不再依赖 Agent/MCP/Shizuku/无障碍。
- `LlmException` 增加错误类型与技术细节，Chat/Agent 都会把真实原因显示出来；空内容、流式中断不再挂起。
- DeepSeek 预设按 2026 官方文档更新为 `deepseek-flash` / `deepseek-v4-pro`，普通聊天自动关闭 thinking
  以降低首字延迟。
- 新增六阶段连接测试（配置/网络/鉴权/模型/真实文本/流式）。

## 数据与配置

- Room v2 + 1→2 迁移：`tasks.mode`，provider 的 `presetId/apiPath/lastError/lastTestedAt/verifiedCaps`。
- 首次启动向导弹窗：服务商 → Key → 模型 → 测试 → 保存并聊天；可跳过。
- 模型卡片：连接状态、已验证能力、最近测试、默认模型、测试/编辑/删除。
- 语言切换：跟随系统 / 简体中文 / English（`LocaleHelper` + 标准资源）。

## UI

- 一级导航收敛为：对话、任务、终端、工作台、我的。
- 对话页重写：普通/Agent 模式、历史会话、流式草稿、复制、重新生成、停止、错误重试、键盘避让。
- 任务页仅显示 Agent 任务；状态与主要文案中文化。
- 终端、设置主要文案中文化；我的页集中模型/语言/进阶入口。

## 验证

- `:app:assembleDebug` 通过，APK 约 63 MB。
- 单测 48 个，0 失败（新增 ToolNames、ToolRegistry、OpenAI 客户端、ConnectionTester、ChatEngine）。
- 未验证：真机/模拟器、真实 DeepSeek Key、Shizuku、无障碍、Compose UI 测试。

## 遗留

- MCP/技能/诊断/工作区/记忆/日志/设备页面尚未完全资源化，存在中英混排。
- core 错误提示为中文，英文界面不自动翻译。
- 无 UI 自动化与设备运行验证；硬性验收门槛尚未在运行时证明。