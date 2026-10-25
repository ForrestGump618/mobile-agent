# DeepSeek 无法对话：根因与修复记录

## 1. 现象

用户在设置中配置了 DeepSeek（Base URL、API Key、模型）后：

- 发送普通问候也无法得到回复；
- 界面只显示“任务失败 / 模型请求失败”，没有可操作的错误；
- 多次重试无效；
- 编译、单元测试均通过，容易误判为“已实现”。

## 2. 排查路径

按“API 配置 → 请求构造 → 传输 → 流式解析 → Harness → UI”逐段检查：

1. **密钥存取**：`SecretVault` 用 Android Keystore AES/GCM 保存，读取后注入 `Authorization: Bearer ...`；
   路径正确，失败时会被包装成 401，而不是当前现象。
2. **地址拼接**：旧逻辑会把 `https://api.deepseek.com` 拼成 `/v1/chat/completions`（DeepSeek 也接受），
   但用户仍可能填写错误地址或旧模型名。
3. **请求体**：Agent 每次请求都附带全部工具。检查工具定义发现：`shell.exec`、`file.read`、`device.info`、
   `ui.snapshot` 等名称包含点号。
4. **官方协议核对**：DeepSeek Chat Completions 文档明确要求
   `function.name` 只能由 `a-z A-Z 0-9 _ -` 组成，最长 128；点号属于非法字符。请求在进入模型前即返回 400。
5. **错误传递**：`AgentOrchestrator.callModel` 捕获 400 后只记录事件并返回 `null`，对外统一显示
   “The model request failed after retries”，服务器返回的具体错误被丢弃。
6. **聊天路径**：旧版没有普通聊天引擎，所有消息都进入 Agent 循环；即使模型本身可用，也会因为工具请求被拒绝
   而失败。
7. **模型 ID**：2026 年 DeepSeek 文档列出的模型为 `deepseek-flash`、`deepseek-v4-pro`；旧的
   `deepseek-chat` 等名称已不在可选范围内，使用旧名会 404。

## 3. 根因（按影响排序）

| # | 根因 | 影响 |
| --- | --- | --- |
| 1 | 工具 function name 含点号，违反 OpenAI/DeepSeek 名称约束 | **每次请求 400，模型完全无法响应** |
| 2 | 普通聊天没有独立链路，强制走 Agent + 工具 + 策略 | 即使只是打招呼也失败；且聊天依赖设备能力 |
| 3 | HTTP 错误被 Harness 吞掉，只留笼统提示 | 用户无法判断是 Key、地址、模型还是协议问题 |
| 4 | 缺少按官方文档维护的服务商预设 / 旧模型名 | 404 或配置错误，普通用户难以自查 |
| 5 | 流式/空内容没有明确的失败语义 | 可能表现为“一直等待”或空回复 |

## 4. 修复内容

### 4.1 工具名合规（核心）

- 新增 `core/tools/ToolNames.kt`：把内部名转为 `[A-Za-z0-9_-]{1,64}` 的 wire name。
- `ToolRegistry.llmTools()` 生成唯一 wire name 并保存映射；`resolveWireName()` 在模型返回调用时映射回内部工具。
- 请求体再次做一次幂等清洗，杜绝任何路径把点号发到服务端。
- 回归测试：`ToolNamesTest`、`ToolRegistryTest`，并在 Mock 请求中断言 `file.read → file_read`。

### 4.2 独立普通聊天

- 新增 `ChatEngine` + `ChatStore` + `ChatRunRegistry`：
  - `tools = emptyList()`，不初始化 MCP/Shizuku/无障碍；
  - 支持流式、多轮、停止、重新生成、历史会话；
  - 错误按 `LlmErrorKind` 转成中文可操作提示；
  - 空回复立即失败，不会无限 Loading。
- 数据库新增 `tasks.mode`（chat/agent），普通对话不再出现在 Agent 任务列表。

### 4.3 错误可见

- `LlmException` 增加 `kind`、`httpStatus`、`technical`。
- 客户端把服务端错误体（脱敏、截断）带入异常；Agent 与 Chat 都会把真实原因写入任务/会话并显示在 UI。
- 401/403/404/429/400/5xx/网络/DNS/TLS/超时/流式/空内容分别映射为中文解释。

### 4.4 服务商与连接测试

- `ProviderPresets`：DeepSeek 官方地址与模型依据 https://api-docs.deepseek.com/ 核对。
- `ConnectionTester`：配置 → 网络 → 鉴权 → 模型 → 真实文本 → 流式，逐项展示；HTTP 200 但无文本不算成功。
- 首次启动向导和模型卡片都使用同一套配置与客户端，避免“测试通过、聊天失败”。

## 5. 验证

自动化验证（JVM）：

- DeepSeek 形状请求：路径 `/chat/completions`、Bearer、模型、无 `stream_options`、无工具；
- 工具名清洗：`file.read` 在请求体中为 `file_read`；
- SSE 分块、`[DONE]`、增量内容、usage；
- 401 → `AUTH` + 中文提示；空内容 → `EMPTY`；
- 普通聊天：无工具、多轮顺序、鉴权失败、空回复；
- 连接测试：成功、缺 Key、401、网络失败的分步结果。

**未验证**：没有可用 DeepSeek Key，因此没有真实端到端请求；没有设备/模拟器，未做真机 UI 对话。
这两项必须由用户在真机上用真实 Key 完成验收。

## 6. 人工验收步骤

1. 安装 `dist/mobile-agent-debug.apk`。
2. 首次启动选择 DeepSeek，填入 Key，点“测试连接”：
   - 期望：6 项检查通过，提示“收到真实文本回复”。
3. 保存后发送“你好”：
   - 期望：中文流式回复，输入框不跳动，可复制、停止、重新生成。
4. 连续追问 3 轮，确认上下文与角色顺序正确。
5. 在模型中把模型 ID 改成不存在的值，再发消息：
   - 期望：提示“接口地址或模型 ID 不存在（404）”，不是无限等待。
6. 断开网络再发消息：
   - 期望：提示网络/DNS 失败，可重试。
