# agentM 新会话交接

更新：2026-10-09。本轮按上一份交接继续开发，四页职责、包管理、引导和存储浏览已实现。先读本文件，再阅读相关源码；不要重做已验收的 Agent 接入和 WebView 排查。

## 当前状态

- 代码与 APK：**0.10.0-dev / versionCode 13**。APK 位于 `android/app/build/outputs/apk/debug/app-debug.apk`。
- **用户随后明确要求更新模拟器，已覆盖安装到 `emulator-5554` 并启动，保留应用数据。** 没有清数据或执行会话停止操作；0.10.0 仍需用户实测，不能写成已获用户验收。
- 设备已验收基线为 0.9.1-dev：用户确认五类 Agent 可用，终端/WebUI 工具栏及 DSH 桌面模式正常。
- 0.10.0 用户卸载重装测试引导：首次 Ubuntu 下载在 0 字节时报 `connection closed`，用户重启后恢复。已读取原生记录确认完整下载 30,028,293 字节、SHA-256 校验后安装成功，Bash/apt 自检通过；模拟器随后两次访问官方源均成功。表现符合短暂网络/代理连接异常，未复现持续性下载故障；其余新版验收仍待用户反馈。
- 开始本轮时 Git 干净，最新提交 `204609e`；本轮改动未提交、未推送。下次仍应重新核对状态。
- `examples/` 保持本地参考与 Git 忽略，不恢复跟踪、不删除本地文件、不重写历史。

完整实现说明：[15-包管理与原生引导](15-包管理与原生引导.md)；验证摘要：[0.10.0](validation/0.10.0.json)。

## 已完成的下一阶段功能

| 页面/流程 | 当前行为 |
| --- | --- |
| 首页 | 仅启动/进入 Agent、Linux 与设备终端；缺依赖时前往管理页。已移除直接安装、停止和沙箱诊断。 |
| 配置 | Claude Code、Codex、OpenCode、Pi、DSH 五个可滚动页签，未安装也有入口。管理安装、在线更新检查、更新、卸载、单 Agent 本地自检、Web/沙箱诊断。 |
| 环境 | Ubuntu 与 Node.js/npm/Git/Python/CA 工具管理、设备诊断、存储占用与只读文件树。新增「设置向导」手动重入入口。 |
| 设置 | 保持偏好与权限职责。 |
| 原生引导 | 欢迎→设备检查→Ubuntu/工具→可选 Agent→结果；共用原生安装器与管理组件，实际快照驱动进度，支持重试、跳过和退出重入。 |

Claude 配置与加密模板功能保留，切换 Agent 页签保留 Claude 草稿。其余 Agent 沿用原生登录/配置，本版没有新增深度配置适配。

原生 `checkPackages` 已移除，改为 `checkTools` 和五类 Agent 各自的 `check...`。环境页不会调用所有 Agent 的实际探针。仍使用现有维护互斥与单例任务，UI 读取原生 `operationId`，软件操作不再强制跳环境页。

### 在线更新的明确边界

- 五类 Agent 均从固定 npm 官方地址查询上游版本；与本地自检、内置适配版本及已安装版本分开展示。
- Claude 2.1.x、Codex 0.161.x、OpenCode 1.18.x 稳定补丁可通过 APK 内固定原生 recipe 成为候选。平台、包名、入口布局和 Codex 组件布局由 APK 限定，不执行上游安装脚本。
- 动态包验证 npm SHA-512 后计算 SHA-256，沿用未发布槽位、实际版本/功能探针、原子发布与旧槽位清理。失败保留旧安装与用户数据。
- Pi/DSH 保持固定依赖、WASM/原生组件及 DSHA overlays；上游发现新版本不等于可安装，需发布包含新适配清单的 agentM APK。
- 候选有效期 24 小时。离线失败/中断/检查中/过期均禁用更新，显示失败及上次成功结果，不误报最新。
- 2026-10-09 开发机 Node HTTPS 实测：Claude 2.1.295、Codex 0.162.0、OpenCode 1.18.35、Pi 1.1.0、DSH 0.2.0-rc.2。Claude 2.1.295 两种架构元数据均正常；Codex 0.162.0 应显示等待适配。
- 「可适配」仅表示 recipe 接受候选，仍须安装校验与自检；不等于该补丁已在全部设备和模型/插件/WebUI 矩阵中验收。真实新补丁更新尚未在用户设备执行。

### 引导与存储

- 原生入口改为 `NativeOnboarding`，React `Onboarding.tsx` 仍仅用于浏览器预览。
- `agentm-native-ui-v1` 保留既有 `onboarded` 与偏好，新增 `onboardingStep`。既有用户升级不被强制重装；可从环境页「设置向导」重入，不清数据。
- 完成引导与基础环境就绪独立；部分 Agent 失败或未授予通知/电池权限仍可进入工作台。
- 存储分类：工作区、Linux home、rootfs、受管软件、应用缓存。只读目录元数据，显示宿主/guest 对应关系；不读文件内容，不提供编辑或删除。
- 拒绝路径越界，不进入或递归追踪符号链接；独立后台扫描，每类最多 1 秒/50,000 项，超限显示不完整统计；每页 100 项。数值为普通文件逻辑大小，不等同于分配块数。

## 主要源码入口

| 范围 | 文件 |
| --- | --- |
| 五 Agent 页签与配置 | `uiux-design/src/screens/NativeConfig.tsx`、`NativeClaudeConfig.tsx`、`NativeClaudeProfiles.tsx` |
| 首页卡片/共享包管理 | `uiux-design/src/screens/NativePackages.tsx`，导出 `NativeAgentCard/NativeTools/NativeAgentPackage/PackageProgress` |
| 环境与存储 | `NativeEnvironment.tsx`、`NativeStorage.tsx`；原生 `StorageInspector.kt/StoragePaths.kt` |
| 引导与快照轮询 | `NativeOnboarding.tsx`、`MainShell.tsx` 的 `AppRoot/useNativePolling`、`store/useApp.ts` |
| 更新与安装 | `packages/PackageManager.kt/PackageUpdates.kt/UpdatePolicy.kt/VerifiedDownload.kt/SlotTransaction.kt` |
| 桥和类型 | `MainActivity.kt/BridgePolicy.kt`；`platform/native.ts/nativeActions.ts/managedAgents.ts` |
| 浏览器回归 | `tools/verify-native-management.js`，只用隔离的原生桥测试快照 |

Kotlin 省略目录的文件均位于 `android/app/src/main/java/dev/agentm/app/`；React 屏幕文件位于 `uiux-design/src/screens/`。

## 验证结果与后续

- TypeScript strict、Vite、APK 构建通过。
- 33 项 JVM 测试通过，无跳过；包括 recipe 边界、元数据失败、摘要校验、槽位发布回滚、目录路径和符号链接。
- Lint 0 错误、38 警告，详情见验证摘要。
- Playwright 原生分支回归覆盖 320/360/480 px、浅/深色、五页签与动作、失败/重试/保留旧版本、只读链接、引导重入、已有环境和首次准备流程。安装状态来自测试快照，不作为设备执行证据。
- 构建日志 `output/android/phase-010-build.log`；界面日志 `output/playwright/native-management-regression.log`；截图 `output/playwright/native-management-*.png`。
- 本轮未运行设备 instrumentation。0.9.1 的 DSH/OpenCode WebUI 回归和原先终端验证属于已验收基线，未改动相关会话代码。

新版已按用户要求安装并启动，接下来由用户测试界面，重点检查 Claude 在线补丁更新、Codex 待适配提示、五 Agent 管理、环境工具自检、存储浏览和设置向导。根据实际反馈修正；不要无故重跑全部安装/登录测试。

## 保留的稳定边界

- Ubuntu Base 24.04.5、proot、Node.js 24.21.0，原生 PTY；两个 Web 服务可独立并行，交互终端一个。
- 返回保留会话，显式停止才终止；重启等待进程退出并核对出生身份，不按名称/端口杀进程。
- OpenCode 随机进程级 Basic Auth，DSH token 交换 HttpOnly Cookie，凭据不进入 React 快照；Agent 页面没有 `AgentMHost`。
- 保留 document-start 精确本机 origin 的 WebView API 兼容脚本与处理延迟/重复 viewport 的 `desktop-viewport.js`。
- Codex 独立沙箱仍有 `cannot establish app-server socket mount isolation`；用户使用正常不等于该探针已修复。
- arm64 真机、多厂商、16 KB 页、长时后台及全部模型/插件矩阵仍未完成。

## 本地开发

工作区 `D:\agentM`；PowerShell；SDK `E:/androidsdk`；JDK `C:/Program Files/Java/jdk-17`；Python `py -3`；设备基线 `emulator-5554`（Android 15/API 35、x86_64）。

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-17'
.\tools\android.ps1 -SdkPath E:/androidsdk -Test

# 需要交付安装且确认没有用户活动测试会话时才执行
.\tools\android.ps1 -SdkPath E:/androidsdk -Install -Serial emulator-5554
```

用户偏好自行点击界面、登录和体验真实应用。不要在用户测试中安装 APK、重启/终止其会话或清数据；无需为阅读文档反复检查设备。无明确要求不启动子代理。参考源码来源见 [参考源码与恢复](参考源码与恢复.md)。
