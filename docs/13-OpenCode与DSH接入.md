# OpenCode 与 DSH 接入

版本：0.8.0-dev；日期：2026-10-09。已接入 OpenCode 1.18.35 的 TUI / WebUI 和 DSH 0.2.0-rc.2 的 WebUI。安装、检查、卸载和会话状态来自原生运行时，浏览器原型不充当真实运行结果。

## 当前入口

- 首页 OpenCode 卡片提供 **WebUI** 和 **TUI**。两种模式使用同一份 OpenCode 用户数据，同一时刻选择一种；切换前停止当前模式。
- 首页 DSH 卡片仅提供 **WebUI**。不再提供 DSH 终端入口。
- 两个 Web 服务可以同时运行，分别停止；原有单个交互式 PTY 仍由终端 Service 管理。
- Web 页面有返回、刷新和停止。返回只离开页面；刷新不重启服务；停止结束对应进程树。
- 环境页显示安装任务、检查结果和 Web 服务状态。终端或 Web 服务未确认停止时，软件管理保持锁定。

## 固定安装与 DSHA 适配

OpenCode 使用官方 `opencode-linux-x64-baseline` / `opencode-linux-arm64` 的 1.18.35 平台包，保留原始二进制与包元数据，直接执行 `package/bin/opencode`。不执行 npm wrapper 的 postinstall。

DSH 的原始 npm 包与依赖图来自 `examples/DSHA/tools/dsh-runtime/package-lock.json`，上游提交固定为 `70e37a7dbcae83b32fc92a8a37b33af88befc0e0`。按 Linux/glibc 和 CPU 选择依赖，x86_64 为 615 个包、约 142 MiB 压缩下载量。两个架构合计 624 个独立包已下载并按锁内 SHA-512 核验，再记录 Android 下载使用的 SHA-256 和大小。

安装器逐包安全解压到未发布槽位，重建锁文件声明的 `.bin` 链接，再应用 DSHA 的 15 个运行时覆盖文件。覆盖前核对上游原文摘要，覆盖后核对目标摘要，最后发布程序记录。没有执行任意 npm 生命周期脚本；只明确复现 `dsh-subprocess-local` 为已发布的 node-pty helper 恢复执行权限这一操作。安装失败不覆盖旧槽位。

复用的 DSHA 处理包括文件排他发布、JSON 存储目录兼容、JSONL 会话发布与历史迁移、客户端模块拼接缓存和对应客户端修正。默认工作目录的显式覆盖由 DSHA 的 `/root/Documents` 适配为 agentM 的 `/workspace`，只在 agentM 启动标记生效时启用。没有引入 DSHA 的设备控制桥、Root/Shizuku、LAN 代理或自动全权限设置。

源码来自精简参考快照；快照缺失的 recipe 支持文件从上述精确提交补齐到忽略提交的缓存，未修改 `examples/DSHA`。来源、输入摘要和产物摘要见 [适配来源记录](research/agent-packages/dsh-adaptations.json)。MIT 声明随 APK 保存，npm 原包保留各自许可证。

覆盖文件在生成后、写入及计算 SHA-256 前统一使用 LF；清单以 UTF-8 / LF 写入，Git 对整个 `dsh-overlays` 目录固定 `eol=lf`。上游 recipe 和 npm 原文字节保持不变，`beforeSha256` 仍校验未改动的 npm 原文。此规则修复 [issue #1](https://github.com/WEP-56/agentM/issues/1)：DSHA Messages recipe 插入的 CRLF 曾导致本地生成文件与 Git / Release APK 中的 `5.js` 字节不同，而清单仍记录生成时的摘要。`DshAssetsTest` 在现有 Release CI 的 `testDebugUnitTest` 阶段逐项校验原始资产摘要及 LF 换行（含安装记录使用的清单），安装器继续严格校验，并在失败时显示补丁文件名。

相关文件：

- [OpenCode 来源](research/agent-packages/opencode.json)
- [OpenCode 固定清单](../android/app/src/main/assets/agent-catalog.json)
- [DSH 固定依赖清单](../android/app/src/main/assets/dsh-catalog.json)
- [DSH 覆盖清单](../android/app/src/main/assets/dsh-overlays/manifest.json)
- [资产准备脚本](../tools/prepare-web-agents.mjs)、[适配生成脚本](../tools/prepare-dsh-overlays.py)

## Web 运行与认证

WebAgentService 持有两个独立进程。每个进程使用独立隐藏 PTY 启动，复用已验证的 JNI 出生身份握手与 proot `--kill-on-exit`；视图不拥有进程。停止前核对 PID、父进程、进程组、会话和启动时钟；不按名称或端口批量杀进程。未确认退出时继续保留维护锁。

OpenCode 使用 `web --hostname 127.0.0.1 --port 0`，从本次进程输出获取实际端口，使用随机的进程级 Basic Auth 密码。启动检查要求匿名 `/global/health` 被拒绝，认证后返回正确版本与 `healthy=true`。初次首页请求和后续 HTTP 认证由原生 WebView 处理。

DSH 使用 `web --no-open --host 127.0.0.1 --port 0`。按 DSHA 的严格格式读取本次启动 token，在原生层完成 303 跳转与 `dsh-auth-*` Cookie 交换，然后检查匿名拒绝、认证后真实 HTML 可用。Cookie 以 HttpOnly 形式交给 WebView；token 链接不进入工作台状态、页面地址或普通日志。

两个 Agent 页面均使用单独 WebView，没有 AgentMHost 或 JavaScript 管理接口。只允许对应本机 origin；外部 HTTPS 主页面链接交给系统浏览器。网络安全配置仅为 127.0.0.1 放行 HTTP。支持通过系统文件选择器选择上传文件；完整上传/下载体验仍需用户验收。当前没有专用文件下载管理器。

系统回收应用后不自动重放 Agent 命令。当前会话不跨进程重启恢复，重新进入后由用户启动。

## 验证结果与边界

- TypeScript strict、Vite 生产构建、APK 构建通过。
- 17 项 JVM 测试通过，包含精确 origin、DSH 启动 token、Cookie、终端入口隔离和 Web 桥方法的拒绝用例。
- Lint：0 错误、24 警告；没有增加忽略错误 baseline。
- Android 15 / API 35、x86_64 的 `WebAgentsIntegrationTest` 通过：真实安装、占用首选端口后的启动、匿名拒绝、认证访问、重复启动幂等、两个服务并存、选择性停止和重启、卸载保留 DSH 数据、原三 Agent 槽位不变。
- 原生 WebView 中的认证 fetch 通过，`window.AgentMHost` 不存在；两个页面 Activity 重建后保持原 PID。
- OpenCode TUI 原生启动、Activity 重建和停止通过。测试未输入模型请求。

详细结果见 [0.8.0 验证摘要](validation/0.8.0.json)。本机日志在 `output/android/web-integration.log`、`web-release-validation.log` 和 `web-delivery-build.log`。

本轮未做 OpenCode / DSH 账号登录或模型调用，也未完成 arm64 真机、长时后台、全部插件与媒体/下载功能验收。页面交互、输入、登录及实际工具调用优先交给用户测试，后续根据反馈集中打磨。CC Switch 配置管理扩展按约定在完整测试与打磨后开展。

## 用户验收步骤

1. OpenCode：分别打开 TUI 和 WebUI，检查输入、登录、模型回复及一次项目文件操作；切换模式前停止当前模式。
2. DSH：打开 WebUI，登录后创建工作区/会话，完成一次模型回复和文件操作；检查中文输入与页面布局。
3. 两个 WebUI 各自返回再进入，确认会话还在；同时运行后只停止其中一个，确认另一个继续可用。
4. 如遇问题，反馈 Agent、操作步骤、页面提示和是否能稳定复现；无需发送 API Key 或登录凭据。

## 构建入口

```powershell
node tools/prepare-web-agents.mjs
py -3 tools/prepare-dsh-overlays.py
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-17'
.\tools\android.ps1 -SdkPath E:/androidsdk -Install -Test -WebTest -Serial emulator-5554
```

补充用户验收记录：用户于 2026-10-09 明确反馈，已对现有 Claude Code、Codex、Pi 实际登录测试，无问题。这是用户实测报告；此前 Codex 独立沙箱自检失败记录仍保留，不将登录测试等同于该探针已修复。
