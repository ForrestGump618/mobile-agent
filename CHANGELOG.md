# Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 与语义化版本。

## [0.1.0] - 2026-10-25

首个可构建的实验版本。**未完成真机与真实 API Key 验收**，详见 `docs/TEST_REPORT.md`。

> GitHub Release：[v0.1.0（Pre-release）](https://github.com/ForrestGump618/mobile-agent/releases/tag/v0.1.0) —
> 附带 Debug APK 与 SHA-256 校验文件。

### Added

- 原生 Android 工程：`:app` / `:core` / `:execution` / `:integrations`。
- 普通聊天引擎 `ChatEngine`：无工具、SSE 流式、多轮、停止、重新生成、历史会话。
- Agent Harness：状态机、计划、工具注册与 Schema 校验、策略审批、检查点、恢复、上下文裁剪、结果校验、审计事件。
- 模型层：OpenAI 兼容客户端、SSE/tool calling/usage、能力探测、角色路由、六阶段连接测试。
- 服务商预设：DeepSeek、OpenAI、通义千问、火山方舟、智谱、Moonshot、自定义。
- 执行工具：本机 Shell、Shizuku ADB、HTTP（SSRF 防护）、工作区文件、设备/应用、UI 自动化、截图、视觉、MCP、Skills、定时任务。
- Room v2（11 张表）+ `1 → 2` 迁移。
- 安全：Android Keystore AES/GCM、风险分级、审批超时即拒绝、日志脱敏、工作区隔离。
- Compose UI：5 个一级 Tab + 二级页面、首次配置向导、模型卡片、中文/英文主流程资源化。
- GitHub Actions CI、Issue/PR 模板、文档体系。

### Fixed

- **DeepSeek 无法对话（阻断性）**：工具 function name 含点号违反服务商约束，导致每次请求 400。
  - 新增 `ToolNames` 与注册表 wire name 双向映射。
  - 普通聊天不再发送任何工具。
  - 服务端错误不再被 Harness 吞掉；按 401/403/404/429/400/5xx/网络/超时/流式/空内容分类。
- 空回复导致无限等待：现在立即失败并提示。
- DeepSeek 默认 thinking 导致首字慢：普通聊天自动关闭 thinking。
- 旧模型 ID（`deepseek-chat` 等）导致的 404：预设更新为 `deepseek-flash` / `deepseek-v4-pro`。
- 流式解析对多行 SSE、注释、`[DONE]`、JSON 回退的兼容性。

### Changed

- 首次启动改为模型向导，Shizuku/无障碍不再阻塞聊天。
- 导航从 14 个平铺入口收敛为 5 个一级 Tab。
- 普通对话与 Agent 任务分表存储（`tasks.mode`），任务页只显示 Agent 任务。
- 模型能力只显示经过真实验证的部分。

### Known

- 未在真机/模拟器运行；未使用真实 DeepSeek Key 做端到端对话。
- Shizuku、无障碍、WorkManager、Room 迁移未真机验证。
- MCP/技能/诊断/工作区等旧页面未完全资源化，存在中英混排。
- 无 Compose UI 自动化测试。
- stdio MCP、内置 Python/Node、语义记忆、视觉闭环未实现。
