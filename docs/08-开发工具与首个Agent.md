# 开发工具与首个 Agent

版本：0.3.0-dev；日期：2026-10-08。基于已运行的 Ubuntu 环境，接入开发工具和 Claude Code 的真实安装、版本检查、终端启动、停止及卸载。

## 实现范围

环境页新增“开发工具与 Agent”：安装 Node.js、npm、Git、Python、CA 证书；展示原生任务阶段、下载字节数、错误和实际版本检查结果。首页 Claude Code 卡片读取 Android 安装记录，其他四个 Agent 显示“尚未接入原生管理”。

Claude Code 启动进入原生 PTY，当前目录为 `/workspace`。返回工作台保留会话，重新打开继续原会话；关闭按钮终止该会话。当前仍只有一个终端槽位，设备 Shell、Linux Shell、Claude 之间切换需先关闭现有会话。

配置页在 Android 中明确显示尚未接入文件管理；浏览器继续保留完整的 React 设计预览。原生登录与认证由 Claude Code 自己处理。

## 版本与来源

| 组件 | 本轮实际版本 | 安装来源与验证 |
| --- | --- | --- |
| Node.js | 24.21.0 LTS | nodejs.org 官方 Linux tar.gz，固定 SHA-256 |
| npm | 11.19.0 | 随上述 Node 发布包提供，运行版本检查 |
| Git | 2.43.0；包版本 `1:2.43.0-1ubuntu7.3` | Ubuntu 签名索引与 apt |
| Python | 3.12.3；包版本 `3.12.3-0ubuntu2.1` | Ubuntu 签名索引与 apt |
| CA 证书 | `20260601~24.04.1` | Ubuntu 签名索引与 apt |
| Claude Code | 2.1.293 | `@anthropic-ai/claude-code-linux-x64`，固定 npm SHA-512 与下载 SHA-256 |

清单见 [agent-catalog.json](../android/app/src/main/assets/agent-catalog.json)，取证元数据见 [metadata.json](research/agent-packages/metadata.json)。arm64 同版本的 Node 和 Claude 包也已下载并核验，但目前没有 arm64 设备运行证据。

阅读 Claude npm wrapper 的 `install.cjs` 后确认，它的 postinstall 根据平台查找 native optional package，再将 `claude` 文件复制或硬链接到命令入口。agentM 直接解压对应 glibc Linux 包并调用同一可执行文件，不运行 npm 生命周期脚本，也不自行实现 Claude 对话协议。原始包 LICENSE/README 保留在槽位中。

Git、Python、证书采用 Ubuntu 当前签名仓库，记录安装时的实际版本；这不是完整 apt 依赖锁。Node 与 Claude 目前只开放清单中的固定版本，没有在线 latest 更新或任意版本选择。

## 安装与数据边界

- Android `files/managed` 映射到 guest `/opt/agentm`；每次安装使用新的独立槽位。
- 下载支持断点续传，并在解压前检查完整长度和 SHA-256；已缓存文件也重新校验。
- 解压复用已有归档路径校验。Node 检查实际版本、npm、Git、Python，并验证 Node 能创建 Shell 子进程；Claude 检查真实 `--version`。
- 全部检查通过后，通过 `AtomicFile` 更新 `packages.json` 中的活动槽位。失败不选择半成品，已有记录保留。
- 显示就绪、启动、检查、卸载时核对受管槽位来源标记。记录不匹配时拒绝接管；单独运行 `--version` 不能绕过来源校验。
- 卸载先取消活动记录，再删除对应受管程序槽位；`linux-home` 和 `workspaces` 不在删除范围内。
- 安装/检查/卸载与终端会话互斥，前台 Service 执行任务。离开页面不取消安装；进程被回收后显示中断，可重试。apt 重试前执行 `dpkg --configure -a`。
- 下载与安装阶段没有暴露强制取消按钮，避免把 apt 配置中断误当成可安全回滚。未完成系统崩溃/断电故障注入认证；突发退出后可能留下未选中的孤立槽位，后续需加入回收与磁盘占用管理。
- 受管 Claude 启动环境设置 `DISABLE_AUTOUPDATER=1`，由工作台安装记录管理版本。Linux Shell 的 PATH 也包含已就绪的 Node 和 Claude 槽位。

## 实际验证

设备仍为 emulator-5554，Android 15 / API 35，x86_64。初次完整包管理测试耗时约 119 秒，包含工具准备、Claude 下载、启动、停止、卸载和缓存重装；这不是其他网络或设备的性能保证。

`PackageIntegrationTest` 覆盖：

1. 从官方源真实下载与安装工具及 Claude，运行版本检查。
2. 同一待执行操作重复提交返回同一个 operationId；维护期间拒绝打开终端。
3. Linux Shell 的 PATH 能找到 node、npm、git、python3、claude。
4. Claude Code 原生交互界面实际出现在 PTY；Activity 重建保持同一 PID。
5. 终端运行中拒绝卸载；停止后允许卸载与重装。
6. 卸载及重装后，持久 Claude 配置目录中的独立测试文件内容保持不变。
7. 篡改受管来源标记时不报告就绪，重新检查也不能接管；恢复标记后可重新检查。

设备 Shell 与 Linux Shell 的既有回归同时通过。日志见 [首次完整回归](../output/android/packages-validation.log) 与 [最终包管理回归](../output/android/packages-final-validation.log)。

最终界面核对见 [首页](../output/android/packages-home.png)、[工具与版本检查](../output/android/packages-tools.png)、[Claude 原生终端](../output/android/claude-terminal.png)。通过真实页面按钮完成了“检查版本”和“启动 → 返回 → 打开”；已安装版本与原生快照一致。JVM 测试 4 项通过，设备 Shell/Linux Shell/软件管理各 1 项通过，Lint 0 errors / 22 warnings。

机器可读结果及 APK SHA-256 见 [验证记录](../output/android/verification.json)。最终界面操作时段没有新增 crash 日志。

本轮没有提交模型请求、填写 API Key 或完成账号登录。已验证的是原生程序安装与交互启动，不宣称完成 Claude 的模型回复/工具调用验收，也不宣称另外四个 Agent 可用。

## 开发入口

```powershell
# 重新从明确固定版本生成资产清单；不会跟随 latest
node tools/prepare-agent-catalog.mjs

# 在已安装 Ubuntu 的模拟器上运行包管理回归
.\tools\android.ps1 -Install -Test -PackageTest -Serial emulator-5554

# 包括既有的设备与 Linux 终端回归
.\tools\android.ps1 -Install -Test -DeviceTest -LinuxTest -PackageTest -Serial emulator-5554
```

下一阶段优先接入 Claude 的配置文件读写与冲突保护，再逐个扩展剩余 Agent。原生登录、模型选择等行为继续交给各 Agent；工作台只管理受支持的配置字段和程序生命周期。
