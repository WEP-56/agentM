# agentM

安卓端开发工作台：安装 Linux 用户态环境，在手机上安装、配置和使用 Claude Code、Codex、OpenCode、Pi、DSH。

当前 0.9.1-dev 已进入五个 Agent 接入后的体验打磨。用户已确认 Claude Code、Codex、Pi、OpenCode、DSH 均可使用；OpenCode 提供 TUI / WebUI，DSH 提供 WebUI。原生终端与 WebUI 改为跟随工作台主题色的紧凑工具栏，补齐浏览器导航、桌面模式、外部浏览器打开，以及终端清屏、字号和重启菜单。0.9.1 修复 DSH 后加载的 viewport 声明覆盖桌面模式的问题。此前 WebView 缺失 API 的兼容修复继续保留，Codex 独立沙箱自检失败记录仍保留。

本机构建、安装与验证入口见 [本地开发与当前进度](docs/06-本地开发与当前进度.md)。

```powershell
.\tools\android.ps1 -SdkPath E:/androidsdk -Install -Serial emulator-5554
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
12. [Codex 程序管理与兼容性](docs/11-Codex程序管理与兼容性.md)：0.6.0 的第二个 Agent 管理与实际沙箱限制。
13. [Pi 程序管理与本地工具验证](docs/12-Pi程序管理与本地工具验证.md)：0.7.0 的第三个 Agent、固定 bundle/WASM 依赖与本地工具探针。
14. [OpenCode 与 DSH 接入](docs/13-OpenCode与DSH接入.md)：0.8.0 的 TUI / WebUI、认证隔离、DSHA 适配和用户验收步骤。
15. [终端与 WebUI 界面打磨](docs/14-终端与WebUI界面打磨.md)：0.8.1–0.8.2 WebView 兼容修复，以及 0.9.0 工具栏、菜单、桌面模式与回归记录。

原始想法保留在 [构思与调研.md](构思与调研.md)。调研日期：2026-10-08。

## 当前建议

以 **DSHA 的 Java 运行时为首选参考，新增 Kotlin 适配层，React 负责工作台页面**。proot + Ubuntu + 真实 PTY 和首个 Agent 的程序管理已跑通；继续逐个扩展。CC Switch 用于借鉴配置文件管理和安装升级逻辑。

后续顺序已确认：五个 Agent 基础接入 → 用户完整测试与共同打磨 → 再开展 CC Switch 相关配置管理扩展。当前已进入用户验收阶段，见 [开发计划](docs/05-开发计划与验收.md)。

DSHA 不是现成 SDK，不能仅复制几个 Java 类就完成集成。其底层需要逐步封装，保留进程身份、维护互斥和事务恢复约束。Java 与 Kotlin 可以在同一个 Android 工程中共存，无须先把稳定底层全部重写。

## 参考源码

[examples/](examples/README.md) 保留 DSHA 与 CC Switch 的精简参考源码。精确提交见 [sources.lock.json](examples/sources.lock.json)，裁剪清单与逐文件摘要见 [清理记录](docs/research/reference-cleanup.json)。它们不是完整可构建工程；保留的上游源码内容未改写。

文档区分「源码已确认」「上游说明」「实际验证」「设计建议」和「用户实测」。早期调研文件中的“本次未构建”指调研阶段；实际开发与各阶段验证见最新记录。完整模型/工具调用矩阵、多设备与 arm64 真机验收尚未完成。

