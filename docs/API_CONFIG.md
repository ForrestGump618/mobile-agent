# API 配置说明

## 1. 预设服务商

| 服务商 | Base URL | 默认接口路径 | 推荐模型（可自定义） |
| --- | --- | --- | --- |
| DeepSeek | `https://api.deepseek.com` | `/chat/completions` | `deepseek-flash`、`deepseek-v4-pro` |
| OpenAI | `https://api.openai.com/v1` | `/chat/completions` | `gpt-4o-mini`、`gpt-4o` |
| 通义千问 / 阿里云百炼 | `https://dashscope.aliyuncs.com/compatible-mode/v1` | `/chat/completions` | `qwen-plus`、`qwen-turbo`、`qwen-max`、`qwen-vl-plus` |
| 火山方舟 | `https://ark.cn-beijing.volces.com/api/v3` | `/chat/completions` | 推理接入点 ID（`ep-...`）或控制台模型 ID |
| 智谱 GLM | `https://open.bigmodel.cn/api/paas/v4` | `/chat/completions` | `glm-4-plus`、`glm-4-flash`、`glm-4-air` |
| Moonshot / Kimi | `https://api.moonshot.cn/v1` | `/chat/completions` | `moonshot-v1-8k` 等 |
| 自定义 | 自填 | `/chat/completions` | 自填 |

说明：

- DeepSeek 的地址与模型依据 **2026-10 官方文档**：base_url `https://api.deepseek.com`，
  model `deepseek-flash` / `deepseek-v4-pro`。DeepSeek 也接受 `/v1` 前缀，但预设使用官方最短形式。
- 其他服务商的模型列表会随版本变化，预设只作为起点，模型 ID 可在界面或高级设置中修改。
- 火山方舟通常需要填写推理接入点 ID，而不是模型展示名。

## 2. 请求如何构造

- URL = `Base URL` + `接口路径`，会自动去除重复的 `/v1`，也支持直接填写完整 `/chat/completions` 地址。
- Header：`Authorization: Bearer <API Key>`、`Content-Type: application/json`、`Accept` 按流式开关选择。
- 普通对话：`tools` 字段完全不发送，避免任何工具 Schema 影响聊天。
- Agent 模式：发送工具，但工具名会转换为 `[A-Za-z0-9_-]`（例如 `file.read` → `file_read`），
  模型返回后再映射回内部工具。
- 不会发送 `stream_options` 等可能不被兼容服务支持的字段。

## 3. 连接测试的六个阶段

| 阶段 | 判定 |
| --- | --- |
| 配置 | Base URL、模型、Key 是否完整 |
| 网络 | DNS/TCP/TLS 是否可达 |
| 鉴权 | 是否 401/403 |
| 模型 | 404/429/400/5xx 等 |
| 文本 | 是否收到真实非空回复（HTTP 200 不算成功） |
| 流式 | 是否收到增量文本（可选） |

任何阶段失败都会显示技术细节（已脱敏、截断），后续阶段标记为“未执行”。

## 4. 高级参数

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| 接口路径 | `/chat/completions` | 支持代理或私有网关 |
| Temperature | 0.7 | 0–2；部分推理模型会忽略 |
| 最大输出 Token | 2048 | 与服务端上限取较小值 |
| 上下文窗口 | 32768 | 用于历史裁剪 |
| 超时 | 60 秒 | 连接/读取超时 |
| 流式 | 开 | 关闭后一次性返回，仍可聊天 |
| 工具调用 | 开 | 关闭后 Agent 无法使用工具，普通聊天不受影响 |
| 视觉 | 关 | 仅在确认模型支持图片时开启 |

## 5. 错误对照

| 状态/类型 | 含义 | 建议 |
| --- | --- | --- |
| 401 | Key 无效/过期 | 重新填写 Key |
| 403 | 无模型权限 | 检查账号权限/模型开通 |
| 404 | 地址或模型不存在 | 检查 Base URL、模型 ID |
| 429 | 限流/额度不足 | 等待或检查余额 |
| 400 | 参数/协议不兼容 | 查看技术细节；普通聊天已禁用工具，若仍 400 多为模型/地址错误 |
| 5xx | 服务端故障 | 稍后重试 |
| 网络/DNS/TLS | 网络层失败 | 检查网络/代理/VPN |
| 超时 | 服务端未响应 | 增大超时或稍后重试 |
| 流式解析失败 | SSE 不完整/非标准 | 关闭流式再试 |
| 空内容 | 模型返回空 | 重试或更换模型 |

## 6. 安全

- API Key 使用 Android Keystore 的 AES-256/GCM 加密，仅密文落盘。
- 不写入数据库、日志、检查点或导出文件；错误信息与工具参数在记录前脱敏。
- 删除模型会同时删除对应密钥。
