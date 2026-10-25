# 安装与构建

## 1. 环境要求

| 项目 | 版本 |
| --- | --- |
| JDK | 21（Android Studio 内置 JBR） |
| Android SDK | platform 36、build-tools 36.1.0、platform-tools |
| Gradle | 8.13（wrapper 已包含） |
| AGP / Kotlin | 8.11.1 / 2.1.21 |
| 设备 | Android 12+（minSdk 31，targetSdk 36） |

## 2. 构建

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
cd mobile-agent
.\gradlew.bat :app:assembleDebug
```

产物：

- `app/build/outputs/apk/debug/app-debug.apk`
- 已复制的交付包：`dist/mobile-agent-debug.apk`

Android Studio 可直接打开 `mobile-agent`，Gradle JDK 选择内置 JDK 21。

## 3. 安装

```powershell
adb install -r dist\mobile-agent-debug.apk
```

包名 `com.mobileagent`，版本 0.1.0。

## 4. 首次配置（DeepSeek 推荐）

1. 打开应用 → 欢迎页选择 **DeepSeek**。
2. 粘贴 API Key（可在 DeepSeek 开放平台创建）。
3. 模型保持 `deepseek-flash`。
4. 点 **测试连接**，必须出现“收到真实文本回复”。
5. 点 **保存并开始聊天**。

详细的服务商地址、模型与错误对照见 [API_CONFIG.md](API_CONFIG.md)。

## 5. 单元测试

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
powershell -ExecutionPolicy Bypass -File .\scripts\run-tests-ascii.ps1
```

当前结果：**48 个测试，0 失败**。

Windows 下若项目路径包含中文，直接 `gradlew test` 可能报
`ClassNotFoundException / initializationError`。原因：Gradle 通过 `@argfile` 传递测试 classpath，
JVM 以平台编码解析非 ASCII 路径。APK 构建不受影响；请使用上面的脚本或把工程放到纯 ASCII 路径。

## 6. 可选能力

### Shizuku

1. 安装 Shizuku（`moe.shizuku.privileged.api`）。
2. 开启开发者选项与无线调试，按 Shizuku 提示配对。
3. 启动 Shizuku。
4. 应用内「我的 > Shizuku > 授权」，再在「我的 > 高级设置」开启 Shizuku Shell。
5. 终端切到 Shizuku 模式执行 `id`，应显示 `uid=2000(shell)`。

重启手机后需要重新启动 Shizuku。

### 无障碍

1. 「我的 > 无障碍 > 打开系统设置」。
2. 启用 “Mobile Agent automation”。
3. 回到应用，在设备控制页点“捕获 UI 树”验证。

### 通知

Android 13+ 首次启动会请求通知权限，用于前台任务通知与定时任务结果。

## 7. 故障排查

| 现象 | 处理 |
| --- | --- |
| 401 / API Key 无效 | 重新复制 Key，确认无空格；测试连接看鉴权阶段 |
| 404 / 模型不存在 | DeepSeek 使用 `deepseek-flash` 或 `deepseek-v4-pro` |
| 网络失败 | 检查网络/代理/VPN；测试连接会区分网络与鉴权 |
| 对话一直等待 | 可停止；关闭流式后重试；查看错误横幅 |
| Shizuku 未授权 | 在 Shizuku 应用内授权，并保持服务运行 |
| 无障碍树为空 | 前台应用可能禁止无障碍，或服务未启用 |
| 任务被杀后暂停 | 这是设计行为，进入任务页手动恢复 |
| 单元测试 initializationError | 使用 `scripts/run-tests-ascii.ps1` |
