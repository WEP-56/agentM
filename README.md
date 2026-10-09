# agentM

安卓端开发工作台：安装 Linux 用户态环境，在手机上安装、配置和使用 Claude Code、Codex、OpenCode、Pi、DSH。

当前 0.5.0-dev 已将 `uiux-design/` 的 Vite + React 19 + Tailwind v4 界面接入 Kotlin Android 壳，支持 Ubuntu 下载与安装、原生 Linux/设备 PTY、开发工具安装，以及 Claude Code 的程序管理、配置文件编辑和多提供商模板库。配置应用包含脱敏预览、文件与模板冲突检查及加密备份恢复。其他四个 Agent 仍在开发中。

本机构建、安装与验证入口见 [本地开发与当前进度](docs/06-本地开发与当前进度.md)。

```powershell
.\tools\android.ps1 -Install -Serial emulator-5554
```

## 开发文档

按下面顺序阅读：

1. [源码调研与选型](docs/01-源码调研与选型.md)：两个参考项目的真实能力、差异和依据。
2. [产品与 React 预览规范](docs/02-产品与React预览规范.md)：引导、四个主页面、交互状态与模拟场景。
3. [架构与运行时设计](docs/03-架构与运行时设计.md)：Java/Kotlin/React 分工、进程、存储、安装和恢复。
4. [Agent 适配与配置管理](docs/04-Agent适配与配置管理.md)：五个 Agent 的安装入口、配置差异和切换约束。
5. [开发计划与验收](docs/05-开发计划与验收.md)：实现顺序、技术验证门槛和验收用例。
6. [前后端契约](docs/contracts/workbench.ts)：可供 React 预览引用的 TypeScript 类型；是设计契约，不是后端实现。
7. [本地开发与当前进度](docs/06-本地开发与当前进度.md)：实际实现范围、Android 构建与模拟器回归。
8. [Linux 环境开发记录](docs/07-Linux环境开发记录.md)：0.2.0 的真实安装流程、运行验证与下一阶段。
9. [开发工具与首个 Agent](docs/08-开发工具与首个Agent.md)：0.3.0 的软件管理、固定版本、真实回归与边界。
10. [Claude 配置文件管理](docs/09-Claude配置文件管理.md)：0.4.0 的受管字段、保留原文、冲突、密钥与恢复验证。
11. [Claude 提供商模板](docs/10-Claude提供商模板.md)：0.5.0 的加密模板库、切换预览、密钥隔离和双 revision 验证。

原始想法保留在 [构思与调研.md](构思与调研.md)。调研日期：2026-10-08。

## 当前建议

以 **DSHA 的 Java 运行时为首选参考，新增 Kotlin 适配层，React 负责工作台页面**。proot + Ubuntu + 真实 PTY 和首个 Agent 的程序管理已跑通；继续逐个扩展。CC Switch 用于借鉴配置文件管理和安装升级逻辑。

DSHA 不是现成 SDK，不能仅复制几个 Java 类就完成集成。其底层需要逐步封装，保留进程身份、维护互斥和事务恢复约束。Java 与 Kotlin 可以在同一个 Android 工程中共存，无须先把稳定底层全部重写。

## 参考源码

[examples/](examples/README.md) 保留 DSHA 与 CC Switch 的精简参考源码。精确提交见 [sources.lock.json](examples/sources.lock.json)，裁剪清单与逐文件摘要见 [清理记录](docs/research/reference-cleanup.json)。它们不是完整可构建工程；保留的上游源码内容未改写。

文档区分「源码已确认」「上游说明」「实际验证」「设计建议」。早期调研文件中的“本次未构建”指调研阶段；目前已在 x86_64 模拟器实际运行 Ubuntu、开发工具和 Claude Code 交互终端。尚未完成模型调用、其他四个 Agent 及 arm64 真机验收。

