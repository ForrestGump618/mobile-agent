# 贡献指南

感谢参与 Mobile Agent。这个项目重视**真实执行、权限边界与可验证性**，请优先阅读架构与安全文档。

## 开发环境

- JDK 21（Android Studio 内置 JBR 即可）
- Android SDK 36 / build-tools 36.1.0
- Gradle 8.13（wrapper 已提供）

```bash
./gradlew :app:assembleDebug
./gradlew test
```

Windows 中文路径下 `gradlew test` 可能因 Gradle `@argfile` 编码报 `initializationError`：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-tests-ascii.ps1
```

## 代码边界

- `:core` 不依赖 Compose/UI；工具通过 `AgentTool` 接口抽象。
- `:app` 不直接执行 Shell、HTTP 或无障碍操作，只调用 Harness/引擎并观察状态。
- 所有能力必须注册到 `ToolRegistry`，经过 `PolicyEngine`。
- 迁移只能前进：Room schema 变更必须附带 Migration 与测试计划，不使用 destructive fallback。

## 新增一个工具

1. 在 `:execution`（或 `:integrations`）实现 `AgentTool`：

```kotlin
override val spec = ToolSpec(
    name = "file.example",             // 内部名可读；wire 层会自动清洗
    title = "示例",
    description = "做什么、边界是什么",
    parametersSchema = Schemas.obj(...),
    risk = RiskLevel.MEDIUM,
    category = ToolCategory.FILE,
    source = "execution",
)
```

2. 在 `AppGraph.registerTools()` 注册。
3. 高风险操作使用 `RiskLevel.HIGH`；需要 Shizuku/无障碍时设置 `requiresShizuku` / `requiresAccessibility`。
4. 输出包含稳定字段，便于 `ResultVerifier` 检查。
5. 添加测试：参数校验、失败路径、超时/取消、输出截断。

禁止：

- 绕过 `PolicyEngine` 直接执行系统操作；
- 在工具里硬编码 API Key、设备路径或用户数据；
- 把失败包装成成功，或返回伪造的 stdout/HTTP 响应。

## 模型与服务商

- 服务商预设必须依据官方文档，注明模型 ID 来源。
- 不根据模型名称猜测能力；能力来自 `ConnectionTester` / `probe`。
- 新参数若并非所有兼容服务都支持，默认不发送，或放在高级选项中。

## 测试要求

- 新增纯逻辑必须有 JVM 单测。
- 网络协议改动使用 MockWebServer 覆盖请求形状、错误码与流式解析。
- 聊天/Harness 改动应使用 Fake `ChatStore` / Fake `LlmClient` 覆盖多轮、失败、取消。
- 不在测试中使用真实凭据；测试密钥使用 `sk-test` 等明显假值。

## 提交与 PR

- 提交信息建议：`feat: ...` / `fix: ...` / `docs: ...` / `test: ...` / `refactor: ...`
- PR 请说明：问题、方案、影响范围、验证方式、未验证项。
- PR 检查清单：
  - [ ] `./gradlew test :app:assembleDebug` 通过
  - [ ] 未引入 Root/绕过系统权限
  - [ ] 新工具已注册 Schema、风险与能力标记
  - [ ] 未提交密钥、Token、设备隐私数据
  - [ ] 文档与已知限制同步更新

## 文档

- 面向用户的说明放 `docs/`，保持中文为主，英文版本可同步精简更新。
- 根因分析、测试报告与限制必须如实描述验证状态。

## 行为准则

保持技术讨论对事不对人；不提交侵犯隐私、绕过他人设备安全或违反服务商条款的功能。
