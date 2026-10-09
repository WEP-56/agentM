# 终端与 WebUI 界面打磨

更新：2026-10-09。当前版本 **0.9.1-dev / versionCode 12**；下文保留 0.9.0 的实现与验收记录。

用户已明确确认五类 Agent 均可使用。本阶段按约定集中修复和打磨体验，CC Switch 配置管理扩展继续留在完整测试之后。UI 点击、外部浏览器登录、输入手感等由用户验收；自动化侧负责已有兼容性、进程生命周期及实际页面/RPC 回归。

## WebView 兼容修复回顾

0.8.1 修复 OpenCode 首页 `groups().length` 崩溃：旧 WebView 缺少上游首页所用的 `Map.groupBy`，同时缺少 `Promise.withResolvers`。0.8.2 补上 DSH 所需的 `AbortSignal.any`，解决目录选择与 runtime 网关初始化路径被中断的问题。用户分别确认 OpenCode、DSH 已恢复可用。

脚本在文档启动前注入，仅对当前本机服务 origin 生效，保留符合规范的原生实现。两个 Agent WebView 均不包含 `AgentMHost`。外部浏览器不会获得 agentM 的兼容脚本，需要浏览器自身支持这些 API；密码设置与这些 JavaScript API 缺失无关。

此前仅检查 HTTP 健康和文档加载不足以发现组件崩溃。现在 OpenCode 回归检查实际首页搜索组件持续存在，DSH 回归以组合 AbortSignal 发起真实 `/api/directoryPicker/list` 请求并检查关联成功响应。

## 0.9.0 界面与行为

共用原生 `SessionChrome` 与 `SessionAppearance`，使用工作台 Material tonal scheme 的颜色。受信任工作台通过既有 `setAppearance` 传递颜色，原生校验后保存；Agent 页面不参与主题或管理桥。顶部为 56 dp 单行，图标点击区至少 48 dp；状态不再常驻第二行。适配状态栏、导航栏、显示缺口和软键盘。

| 页面 | 常驻工具栏 | 更多菜单 |
| --- | --- | --- |
| 终端 / TUI | 返回、路径、⋮ | 粘贴、清空屏幕、终端字号、重启、终止 |
| WebUI | 后退、前进、地址栏、刷新、⋮ | 返回工作台、桌面版网站、默认浏览器打开、重启、停止 |

终端使用随主题色调整的深色背景和圆角辅助键。字号按设备保存；Ctrl 选择态在消费后清除。Linux Shell 用 OSC 7 报告目录，Android Shell 使用真实 cwd；没有报告目录的 Agent 显示 `/workspace` 启动目录。点击路径可查看会话信息。清空屏幕只清除显示与回滚缓冲，不向 PTY 写 `clear` 或 Ctrl-L，不清会话/项目文件。

Web 地址栏可编辑当前服务 URL 或绝对路径；外部 HTTPS 在系统浏览器打开。不能借地址栏跳到其他本机端口、file、javascript 或带 URL 用户凭据的地址。地址显示隐藏 query/fragment，避免显示认证参数。后退在有网页历史时回退，否则返回工作台；前进无历史时禁用。Activity 重建保存同一服务的浏览历史，服务更换后清除旧历史。

桌面模式按 Agent 保存：保留实际 Chromium 版本，切换桌面 User-Agent，并将 viewport 设为 1280 CSS px，启用缩放与页面概览；关闭后重新加载原移动模式。只调整外层浏览器行为，不改上游页面配色和业务布局。

浏览器认证与内嵌 WebView 分离：DSH 用本次服务 token 完成外部浏览器的登录交换；OpenCode 显示用户名 `opencode`，用户点击「复制密码并打开」后才将本次服务密码放入标记为敏感的剪贴板。密码不进入 URL、工作台状态或普通日志。服务重启后需重新认证。

重启会先结束本次受管进程，确认退出后再创建新会话。保留已有进程身份核对、维护互斥和前台服务所有权；退出未确认时不会并行启动替代进程。终止后的 Activity 重建不会重新启动会话。返回页面仍保留当前会话。

## 验证与待验收

TypeScript/Vite、APK、JVM、Lint 已通过，精确结果见 [0.9.0 验证摘要](validation/0.9.0.json)。Android 15 / API 35、x86_64 上三项定向回归通过：

- `OpenCodeWebUiTest`：实际首页、兼容 API、无管理桥、Activity 重建保持 PID、桌面 viewport/UA、重启后旧进程退出且页面连接新服务。
- `DshWebUiTest`：认证页面中的真实目录查询与组合 AbortSignal。
- `SessionChromeTest`：新建 Linux PTY、`cd /tmp` 后路径更新、清屏后 shell 变量保留、Activity 重建、重启后回到启动目录、终止后重建不自启。

已检查模拟器终端与 WebUI 的实际截图，保存在 `output/android/terminal-chrome.png` 与 `output/android/web-chrome.png`。构建日志为 `session-chrome-build.log`，设备回归日志为 `session-chrome-regression.log`。没有进行账号登录或发送模型请求。

请由用户重点验收菜单触控、地址编辑与前进后退、移动/桌面模式切换、外部默认浏览器认证、横屏/键盘、不同主题色。arm64 真机、多设备、大字号和长时后台验收仍未完成。原有 Codex 独立沙箱探针失败不因本次 UI 改动视为修复。

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-17'
.\tools\android.ps1 -SdkPath E:/androidsdk -Test
# 下方会安装 APK 并运行真实临时会话；先结束手动测试中的会话。
.\tools\android.ps1 -SdkPath E:/androidsdk -SessionUiTest -WebUiTest -Serial emulator-5554
```

## 0.9.1：DSH 桌面模式未生效

用户实测 0.9.0 后反馈：仅 DSH 的「桌面版网站」没有变化，其他操作无问题。

DSH `dsh-web-frontend@0.2.0-rc.2` 自带 `width=device-width, initial-scale=1`。旧脚本在解析器尚未读到这个 meta 时先创建了 `width=1280`，之后只修改第一个 viewport；稍后出现的移动声明仍能让 Chromium 的有效宽度回到手机宽度。独立浏览器测试使用 DSH 原始 viewport 并在它前面设置解析检查点，复现了两个声明并存但 `innerWidth=360` 的情况。

修复将脚本单独保存为 `android/app/src/main/assets/desktop-viewport.js`：页面解析中不抢先创建 meta，统一更新所有 viewport，监听后续新增、替换、改名和 content 修改；对于无 viewport 的页面，在 DOMContentLoaded 后补齐。关闭桌面模式时重新加载原始页面，恢复其移动 viewport。认证、DSH runtime、模型配置和工作区均不需要变更。

`tools/verify-desktop-viewport.js` 是可由 Playwright CLI 执行的真实浏览器回归，覆盖正常移动、延迟 meta、重复 meta、无 meta 和恢复移动五类页面，并检查桌面 CSS 断点确实触发。浏览器实测桌面宽度为 1280、移动为 360，不以单独读取第一个 meta 的值当作成功。

DSH 设备回归改为调用与菜单相同的切换方法，检查「桌面 → 移动 → 桌面」、Activity 重建后保留模式、PID 不变，以及桌面模式下的真实目录 RPC。共享脚本同时保留 OpenCode 回归。精确结果见 [0.9.1 验证摘要](validation/0.9.1.json)。

用户随后明确反馈「我已测试，无问题」。本轮问题已关闭；下一会话按 [HANDOFF](HANDOFF.md) 进入页面职责、包管理和引导流程开发。
