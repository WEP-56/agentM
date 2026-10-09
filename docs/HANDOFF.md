# agentM 新会话交接

更新：2026-10-09。请先读本文件，再按任务读取源码；无需重走前面的 Agent 接入和 WebView 问题排查。

## 1. 当前结论与本次交接范围

- 当前应用 **0.9.1-dev / versionCode 12**，已安装至 `emulator-5554`。用户最新明确反馈：**「我已测试，无问题」**。
- Claude Code、Codex、Pi、OpenCode、DSH 已全部接通并经用户使用确认；OpenCode 为 TUI + WebUI，DSH 仅 WebUI。
- 原生终端与 WebUI 已完成工具栏和菜单打磨；DSH 桌面模式问题已修复、通过回归并获用户确认。不要继续把它当作待排查问题。
- 用户因上下文过长要求本会话仅整理交接，**下一会话再开发下述页面、包管理、引导任务**。这些下一阶段功能尚未实现。
- 原计划是先接完五个 Agent，再由用户完整测试与共同打磨，再开展配置管理扩展。前两步已有本轮用户确认；接下来遵循下面的新任务，不再停在等待验收。

## 2. 用户指定的下一阶段任务

用户原意：

1. 「配置」页调整，分五栏，接入五类 Agent 的包管理：**安装、更新检查、更新、卸载**。当前环境页已有 Agent 卸载等操作，需要转移。
2. 完成引导流程。
3. 与第 1 项联动，明确四个页面职责；环境页推荐增加存储占用查看和文件树查看。

### 四页职责（用户已明确，优先于旧设计文档）

| 页面 | 职责 | 需要迁移或清理 |
| --- | --- | --- |
| 首页 | **仅启动、进入终端 / WebUI** | 移出 Agent 安装、检查、更新、卸载及配置编辑；缺依赖时可引导至对应管理页。终止、重启已有终端/WebUI 菜单入口，避免继续把首页做成综合管理页。 |
| 配置 | **Agent 包管理 + Agent 配置**，五个 Agent 各有一栏 | 接收环境页中的五类 Agent 安装、检查、卸载、诊断状态；新增真实在线更新检查与更新能力；保留已有 Claude 配置功能。 |
| 环境 | **Ubuntu 及 Ubuntu 内开发工具**，包括 Ubuntu 本身与 Node.js/npm/Git/Python 等安装、检查 | 移出五类 Agent 的管理和检查；保留 Linux/设备环境相关信息与诊断。用户推荐增加真实存储占用和文件树查看。 |
| 设置 | **偏好、权限等** | 用户说目前无问题，保持已有职责与体验。 |

「五栏」是五个 Agent 的独立管理入口。**实现建议**：手机使用可切换、必要时横向滚动的五个页签，不要把五栏内容硬挤在一个屏幕。顺序与首页 `AGENTS` 统一：Claude Code、Codex、OpenCode、Pi、DSH。未安装的 Agent 也必须有入口，否则无法从配置页安装。

「Agent 配置」是配置页职责；当前已实现的是 Claude 的真实配置与模板。用户本次第 1 项重点明确为包管理，不要把仅搬动 UI 或四种虚构配置表单当作完成；其余 Agent 的深度配置适配按新会话具体任务逐项开展，保留原生登录/配置方式。

### 包管理必须分清的现状与缺口

- 已有：固定版本安装、受管槽位与校验、实际版本/功能探针、卸载并保留用户配置/登录/会话/工作区。
- **没有完整实现在线更新检查与动态版本更新。** 当前按钮「检查版本」实际调用 `checkPackages`，只是对本地已安装工具和 Agent 跑探针，不能改名后冒充在线更新。
- 当前 `PackageManager` 的候选来自 APK 内固定 catalog；应补齐已安装版本、上游可用版本、当前适配支持版本、检查时间和失败状态的区别。离线失败不显示「已是最新」。
- 更新必须沿用受管槽位、下载校验、验证后发布、失败保留旧版本和用户数据的语义。不要直接运行无限制的 `npm update -g` 覆盖当前安装。
- DSH 是固定依赖图和 DSHA 适配 overlays；Pi 也有固定 bundle/WASM/原生依赖。在线发现新版本不等于该版本已兼容，要设计可信更新 recipe/版本支持边界，不可绕过适配。
- 当前 `checkPackages` 同时检查工具与 Agent。建议拆为独立工具检查和按 Agent 检查，让环境与配置页职责在原生操作层也分开，而不只是藏按钮。
- 单例包管理任务/互斥已经存在；新 UI 仍读取原生实际进度和 `operationId`，不要在页面本地模拟成功。程序在运行时的更新、卸载需遵守已有维护互斥。

### 引导流程的现状与建议

- Android 当前走 `NativeWelcome`，只是开发版欢迎页和「进入工作台」，文案还只提 Claude；不是完整引导。
- `Onboarding.tsx` 是 React 预览流程，含模拟安装/等待逻辑，不能直接作为原生安装流程交付。
- 原生 `completeOnboarding` 目前只设置 `onboarded=true`；持久化键 `agentm-native-ui-v1` 只保存偏好与该布尔值。
- 建议顺序：欢迎 → 设备/依赖检查 → Ubuntu 与开发工具准备 → 可选 Agent 安装 → 真实结果/进入工作台；复用配置页包管理能力，不建立第二套安装器。
- 支持已有环境、跳过可选 Agent、部分安装失败、重试、退出重入及拒绝非必要权限。基础环境就绪与某个 Agent 安装成功分开判定；不要为了测试引导清空用户现有数据。
- 现有已使用用户不应升级后强制重新安装 Ubuntu；引导完成标识与实际环境状态需要各自处理。

### 环境页的推荐扩展

存储占用与文件树是用户推荐项，可在页面职责和真实引导打通后做。应来自实际目录统计/文件查询，先提供只读浏览；不自动扩大为编辑器、文件删除器或共享整个 Android 文件系统。明确工作区、Linux home、rootfs、受管软件、缓存等分类及宿主/guest 路径关系；避免递归追踪符号链接和在 UI 线程扫描大目录。

## 3. 直接从哪些文件开始

| 范围 | 入口与当前问题 |
| --- | --- |
| 首页 | `uiux-design/src/screens/Home.tsx`；原生卡片在 `NativePackages.tsx` 的 `NativeAgentCard`，目前混有安装、停止、跳转环境等操作。 |
| 配置 | `screens/Config.tsx`：`isNative` 时直接返回 `NativeClaudeConfig`，所以当前实际只有 Claude；`NativeClaudeConfig.tsx` / `NativeClaudeProfiles.tsx` 是应保留的真实配置功能。 |
| 工具与 Agent 混合管理 | `screens/NativePackages.tsx` 的 `NativePackages`，由 `NativeEnvironment.tsx` 嵌入；混合工具安装、五 Agent 安装/卸载、`checkPackages`、Web 状态、各项 probe。 |
| 页面导航/引导 | `screens/MainShell.tsx`：native/preview 分支与 `NativeWelcome`；`screens/Onboarding.tsx`：预览；`store/useApp.ts`：状态与持久化。 |
| 原生操作 | `platform/nativeActions.ts`：`manage()` 成功后强制跳 `env`，需随迁移调整；`platform/native.ts`：快照类型；`platform/managedAgents.ts`：动作映射。注意映射表顺序与 `AGENTS` 当前不同。 |
| 包管理后端 | `android/app/src/main/java/dev/agentm/app/packages/PackageManager.kt`、`ManagedPackagePaths.kt`、`PackageService.kt`、`DshPackage.kt`、`PiPackage.kt`、`VerifiedDownload.kt`。 |
| 原生桥/快照 | `MainActivity.kt`、`BridgePolicy.kt`、`EnvironmentInspector.kt`。受信任工作台桥与 Agent WebView 的权限边界不可混淆。 |
| Ubuntu | `linux/LinuxManager.kt`、`LinuxRuntime.kt`、`LinuxInstallService.kt`。 |
| 目录与更新来源 | `assets/agent-catalog.json`、`assets/dsh-catalog.json`、`assets/dsh-overlays/`、`tools/prepare-*-catalog.mjs`、`prepare-web-agents.mjs`、`prepare-dsh-overlays.py`。 |
| 原生会话界面 | `TerminalActivity.kt`、`web/AgentWebActivity.kt`、`ui/SessionChrome.kt`、`ui/SessionAppearance.kt`，本轮已验收，应保持稳定。 |

以上省略目录的 Kotlin 文件均在 `android/app/src/main/java/dev/agentm/app/`，React 文件在 `uiux-design/src/`。

## 4. 已实现基线与不能破坏的边界

| 组件 | 当前版本/形态 |
| --- | --- |
| Ubuntu / 运行时 | Ubuntu Base 24.04.5 + proot；真实原生 PTY；Node.js 24.21.0 |
| Claude Code | 2.1.293，终端；已有配置文件和加密提供商模板 |
| Codex | 0.161.0，终端 |
| Pi | 1.1.0，终端；quickjs-wasi 3.6.2、photon-node 0.3.4 |
| OpenCode | 1.18.35，TUI + WebUI，同一 Agent 两种模式互斥 |
| DSH | 0.2.0-rc.2，仅 WebUI；DSHA 固定依赖图及 15 个 overlays |

- 两个 Web 服务可独立并行；交互终端当前只有一个。返回页面保留会话，显式停止才终止；重启等待旧进程退出，并核对进程出生身份，不能按名称/端口杀进程。
- OpenCode 使用随机进程级 Basic Auth；DSH 使用启动 token 交换 HttpOnly Cookie。凭据不进入 React 快照，Agent 页面没有 `AgentMHost`。
- OpenCode/DSH WebView 兼容脚本在 document start、当前精确本机 origin 注入；保留 `Map.groupBy`、`Promise.withResolvers`、`AbortSignal.any` 修复。
- 桌面模式使用 `desktop-viewport.js`，处理延迟和重复 viewport；不要退回仅修改第一个 meta 的实现。外部浏览器不获得 agentM 的兼容脚本。
- 配置管理已有预览、冲突检查、原子写入、备份与加密模板；页面重组不能丢弃这些语义。
- Codex 用户实际登录/使用通过；**独立沙箱探针仍失败**，原错误 `cannot establish app-server socket mount isolation`。不能把用户使用确认写成沙箱探针已修好。
- 五 Agent 用户确认可用不等于已完成 arm64 真机、多厂商、16 KB 页、长时后台和全部模型/插件矩阵。

## 5. Git 与本地参考资料（请重新核对，不沿用旧上下文）

- 工作区 `D:\agentM`，PowerShell，分支 `main`。
- 交接前最新功能提交：**`f6c2222f6a3b36d5c81063a4ebb70af1968a096a`**，已经包含五 Agent 接入、会话 UI 和 0.9.1 修复。更早摘要中「大量功能未提交」已过时。
- 本次交接文档、来源整理和 `examples/` 取消跟踪为新增未提交改动；**没有代用户提交或推送**。
- 用户最新要求开源仓库不包含 `examples/`。已添加根忽略规则，并执行仅移除索引的 `git rm --cached -r -- examples`；本地文件保留。状态中会有 1089 个已暂存删除，这是预期结果，不要恢复跟踪或删除本地参考。
- 原先未跟踪的 `examples/cc-switch/images/` 一并被忽略，仍留在磁盘。
- 来源与恢复说明已迁至 [参考源码与恢复](参考源码与恢复.md)，来源锁在 `docs/research/reference-sources.lock.json`。`prepare-web-agents.mjs` 已改为从精确提交获取/校验 DSH 依赖锁并缓存，避免新克隆依赖 examples。
- 仅取消当前跟踪，没有重写 Git 历史。旧提交仍含参考快照；公开前若需清理历史，另行明确处理，不能自动 force-push。
- `.cache/`、`output/`、构建目录与 `upstream-build/` 均为本地资产；新克隆不应假定它们存在。

## 6. 最近验证与工具链

- 当前 0.9.1 APK 已安装，用户确认测试无问题。可交付 APK：`android/app/build/outputs/apk/debug/app-debug.apk`。
- TypeScript/Vite、APK、19 项 JVM 测试通过；Lint 0 错误，警告见 [0.9.1 验证摘要](validation/0.9.1.json)。
- 最近 Android 回归：`DshWebUiTest` + `OpenCodeWebUiTest`，**OK (2 tests)**，35.052 秒。DSH 覆盖桌面/移动往返、重建、同 PID、真实目录 RPC；OpenCode 覆盖实际首页、兼容 API、桌面 viewport、重启重连。
- 0.9.0 `SessionChromeTest` 还验证了 Linux cwd、显示清屏不清 shell 变量、重启与停止后重建不自启。终端未在 0.9.1 改动，不必无故重跑全部安装测试。
- Playwright 独立页面回归 5 类 viewport 场景通过；日志 `output/playwright/dsh-desktop/regression.log`。脚本 `tools/verify-desktop-viewport.js` 可由 CLI `run-code --filename` 执行。
- 日志：`output/android/desktop-viewport-091-build.log`、`desktop-viewport-091-test-build.log`、`desktop-viewport-091-regression.log`。截图：`output/android/dsh-desktop-091.png`；之前终端/Web 截图也在 `output/android/`。

本机 Android SDK `E:/androidsdk`；JDK `C:/Program Files/Java/jdk-17`；设备 `emulator-5554`（Android 15/API 35、x86_64）；Python 用 `py -3`；Node/npm 已安装。工具调用经 `functions.exec` → `tools.exec_command`，不设置 `sandbox_permissions`。

```powershell
# 常规构建与本地检查，不打断设备会话
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-17'
.\tools\android.ps1 -SdkPath E:/androidsdk -Test

# 仅在需要交付新版且用户没有活动测试会话时安装
.\tools\android.ps1 -SdkPath E:/androidsdk -Install -Serial emulator-5554

# 可选：与改动相关时才运行，会安装测试 APK 并创建真实临时会话
.\tools\android.ps1 -SdkPath E:/androidsdk -WebUiTest -SessionUiTest -Serial emulator-5554
```

不要在本次交接中启动新功能、重装或清数据。用户偏好自行进行界面点击、账号登录、真实应用体验测试；开发侧做实现、构建与有意义的自动化。不要在用户测试中安装 APK、重启或终止其会话；无需为了读文档反复跑设备检查。无明确要求不启动子代理。

## 7. 新会话建议执行顺序与验收

1. 先看 Git 当前状态和本文件，保留 examples 的预期移除；阅读配置/环境/首页及原生包管理代码。
2. 按四页职责拆分组件、原生动作和状态展示，完成五 Agent 配置页入口及迁移，删除旧入口和强制跳环境页的逻辑。
3. 实现真实在线更新检查与受管更新流程，区分本地自检、上游发现和可适配版本；优先覆盖失败后旧版本/用户数据仍可用。
4. 复用上述能力接通真实引导，处理既有环境、跳过、部分成功、重试与重入，不使用预览的虚构进度。
5. 按余下范围安排环境页存储与只读文件树；设置和已验收会话 UI 保持稳定。将新界面交给用户实测。

验收重点：五类 Agent 未安装时仍可在配置页安装；全部包管理与检查归属正确；首页不再执行安装/更新/卸载；环境检查不暗中运行所有 Agent 的探针；失败/离线更新不误报成功；更新/卸载保留用户数据；引导不重装已就绪环境，部分 Agent 失败仍可进入工作台；原先五类 Agent 启动与会话行为不回退。

相关阅读：[界面与 WebView 修复](14-终端与WebUI界面打磨.md)、[OpenCode/DSH 接入](13-OpenCode与DSH接入.md)、[配置适配目标](04-Agent适配与配置管理.md)、[Claude 配置](09-Claude配置文件管理.md)、[Claude 模板](10-Claude提供商模板.md)。早期文档中的「尚未接入」多为历史阶段描述；本文件的当前状态与用户最新职责定义优先。
