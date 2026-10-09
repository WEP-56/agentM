# agentM

安卓端开发工作台：安装 Linux 用户态环境，在手机上安装、配置和使用 Claude Code、Codex、OpenCode、Pi、DSH。

当前 0.13.0-dev 在五个 Agent 页签下统一使用可折叠的「版本」和「提供商」列表。Claude Code、Pi、OpenCode、Codex 支持新增、编辑、复制、删除和二次确认切换，包含真实模型列表查询、模型能力配置及源码编辑。Codex 严格参考 CC Switch 的原生直连实现，新增 OpenAI Official 默认配置、Auth JSON/TOML 编辑、模型目录、推理档位、1M 与压缩设置。提供商及备份加密保存，原生文件写入有冲突检查和事务恢复。

0.10.0、0.11 Claude/Pi、0.12 OpenCode 已获用户测试确认；0.13 已构建并完成 JVM 与浏览器验证，未覆盖安装设备，Codex 配置真实使用待用户测试。终端、WebUI、认证隔离和桌面模式继续沿用已验收实现，Codex 独立沙箱自检失败记录仍保留。按用户明确要求，项目不制作协议转换或本地代理服务。

本机构建、安装与验证入口见 [本地开发与当前进度](docs/06-本地开发与当前进度.md)。

**新会话接续请先读 [HANDOFF](docs/HANDOFF.md)**。本阶段实现、更新边界与验证见 [包管理与原生引导](docs/15-包管理与原生引导.md)。

最新实现见 [Codex 提供商配置](docs/18-Codex提供商配置.md)；上一阶段见 [OpenCode](docs/17-OpenCode提供商配置.md) 与 [Claude/Pi](docs/16-Claude与Pi提供商配置.md)。

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
16. [包管理与原生引导](docs/15-包管理与原生引导.md)：0.10.0 的四页职责、五 Agent 包管理、更新策略、真实引导与存储浏览。
17. [Claude 与 Pi 提供商配置](docs/16-Claude与Pi提供商配置.md)：0.11.0 的统一列表、二级编辑、模型查询与原生配置事务。
18. [OpenCode 提供商配置](docs/17-OpenCode提供商配置.md)：0.12.0 的 SDK 选项、模型属性、JSONC 多文件管理与升级导入。
19. [Codex 提供商配置](docs/18-Codex提供商配置.md)：0.13.0 的 OpenAI Official、CC Switch 原生直连投影、TOML、模型目录与登录保留。

原始想法保留在 [构思与调研.md](构思与调研.md)。调研日期：2026-10-08。

## 当前建议

以 **DSHA 的 Java 运行时为首选参考，新增 Kotlin 适配层，React 负责工作台页面**。proot + Ubuntu + 真实 PTY 和五个 Agent 的程序管理已接通。CC Switch 用于借鉴配置文件管理和安装升级逻辑。

后续顺序已确认：五个 Agent 基础接入 → 用户完整测试与共同打磨 → 再开展 CC Switch 相关配置管理扩展。当前已进入用户验收阶段，见 [开发计划](docs/05-开发计划与验收.md)。

DSHA 不是现成 SDK，不能仅复制几个 Java 类就完成集成。其底层需要逐步封装，保留进程身份、维护互斥和事务恢复约束。Java 与 Kotlin 可以在同一个 Android 工程中共存，无须先把稳定底层全部重写。

## 参考源码

`examples/` 仅作本地参考，已从 Git 跟踪中移除并忽略，开源仓库不包含上游源码副本。来源、精确提交和恢复命令见 [参考源码与恢复](docs/参考源码与恢复.md) 与 [来源锁定清单](docs/research/reference-sources.lock.json)；历史裁剪记录仍保留在 [清理记录](docs/research/reference-cleanup.json)。产品实际使用的第三方组件及许可继续保留在 `android/third-party/` 和 APK 资产中。

文档区分「源码已确认」「上游说明」「实际验证」「设计建议」和「用户实测」。早期调研文件中的“本次未构建”指调研阶段；实际开发与各阶段验证见最新记录。完整模型/工具调用矩阵、多设备与 arm64 真机验收尚未完成。

