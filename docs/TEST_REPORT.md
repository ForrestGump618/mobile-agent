# 测试报告（第二轮）

日期：2026-10-25。以下结果均来自本机实际执行，未把编译成功等同于运行成功。

## 1. 构建

| 项目 | 结果 |
| --- | --- |
| 命令 | `.\gradlew.bat :app:assembleDebug` |
| 结果 | **BUILD SUCCESSFUL** |
| APK | `app/build/outputs/apk/debug/app-debug.apk`，交付副本 `dist/mobile-agent-debug.apk` |
| 大小 | 63.1 MB |
| 工具链 | Gradle 8.13、AGP 8.11.1、Kotlin 2.1.21、JDK 21、compileSdk/targetSdk 36、minSdk 31 |
| 数据库 | Room v2，带 1→2 迁移（tasks.mode、providers 预设/测试字段） |

## 2. 单元测试

命令：`scripts/run-tests-ascii.ps1`（ASCII 副本中执行）。

| 模块 | 测试类 | 数量 | 失败 |
| --- | --- | --- | --- |
| core | TaskStateMachineTest | 5 | 0 |
| core | ToolArgValidatorTest | 5 | 0 |
| core | RedactorTest | 2 | 0 |
| core | ApprovalBusTest | 2 | 0 |
| core | ToolNamesTest | 3 | 0 |
| core | ToolRegistryTest | 3 | 0 |
| core | OpenAiCompatibleClientTest | 6 | 0 |
| core | ConnectionTesterTest | 4 | 0 |
| core | ChatEngineTest | 4 | 0 |
| execution | ShellCommandPolicyTest | 3 | 0 |
| execution | UrlPolicyTest | 4 | 0 |
| integrations | MiniYamlTest | 4 | 0 |
| integrations | StreamableHttpMcpClientTest | 3 | 0 |
| **合计** | | **48** | **0** |

### 本轮新增/加强的覆盖

- **DeepSeek 请求形状**：`/chat/completions`、Bearer、模型、无 `stream_options`、普通聊天无 `tools`。
- **工具名合规**：`file.read` 在 wire 上为 `file_read`，并能在响应中映射回内部工具；冲突时保持唯一。
- **SSE**：分块传输、`data:` 事件、`[DONE]`、增量回调、usage、`finish_reason`。
- **错误**：401 → AUTH + 中文提示；空内容 → EMPTY；网络/超时分类。
- **普通聊天**：无工具调用、多轮历史顺序、鉴权失败、空回复不挂起。
- **连接测试**：六阶段、缺 Key、401、网络失败的分步结果。
- **既有能力**：状态机、参数校验、脱敏、审批超时、命令拦截、URL 策略、YAML、MCP 客户端。

## 3. APK 静态检查

- 包名 `com.mobileagent`，versionCode 1，versionName 0.1.0。
- minSdk 31 / targetSdk 36。
- 前台服务类型 `dataSync`；Shizuku provider `com.mobileagent.shizuku`；无障碍服务与 Boot 接收器存在。
- 无 root、无 `MANAGE_EXTERNAL_STORAGE`、无外部存储读写权限。
- 内置 6 个技能资源；多 dex。

## 4. 未验证项（必须明确）

| 项目 | 原因 |
| --- | --- |
| 真机/模拟器启动、UI 渲染 | 无连接设备；本机系统镜像目录不完整，模拟器无法启动 |
| 真实 DeepSeek Key 端到端对话 | 无可用凭据；未伪称通过 |
| 多轮真实对话、停止/重生成的真实网络行为 | 同上 |
| Shizuku 全链路 | 需真机 + Shizuku + 无线调试配对 |
| 无障碍树/点击/截图 | 需真机启用服务 |
| Compose UI 自动化测试 | 无设备/模拟器，未执行 |
| Room v1→v2 迁移的真机升级 | 未在真实旧数据库上执行（迁移代码已写） |
| 屏幕旋转、字体放大、横屏 | 未执行 |

## 5. 验收门槛判定

目标：普通用户在中文界面选择 DeepSeek、输入 Key、测试通过并稳定多轮聊天。

- 代码与自动化测试层面：**已实现并通过协议级测试**。
- 运行时层面：**未验证**，因为无设备与真实 Key。

因此本轮**不能判定硬性验收已通过**，只能报告为“实现完成、构建与单测通过、真机验收待执行”。
请按 `DEEPSEEK_FIX.md` 第 6 节与 `INSTALL.md` 的人工步骤在真机上完成最终确认。

## 6. 复现命令

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :app:assembleDebug
powershell -ExecutionPolicy Bypass -File .\scripts\run-tests-ascii.ps1
adb install -r dist\mobile-agent-debug.apk
```
