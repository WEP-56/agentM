# agentM 新会话交接

更新：2026-10-09。请先读本文件，不要重做已验收的 Agent 接入、WebView 和上一阶段包管理/引导工作。

## 当前状态

- **0.14.0-dev / versionCode 17** 已构建；本轮未安装到设备、未停止/重启会话或修改设备目录偏好。用户自行安排设备测试。
- 本轮按用户截图，将工作目录选择器加入首页原有紫色 Ubuntu 组件；保存后新启动的终端类 Agent / Linux 终端使用该目录，既有会话和 WebUI 行为保留。
- Claude Code、Pi、OpenCode、**Codex** 提供商管理已完成。Codex 严格参考 CC Switch 原生直连路径，加入 **OpenAI Official** 默认配置；DSH 仍使用原生界面配置。
- 0.10.0、0.11 Claude/Pi 已验收。用户于 2026-10-09 确认 **0.12 OpenCode「可用」**，要求最后制作 Codex 配置能力，必须严格参考 CC Switch，并有与 Claude Official 相同的 OpenAI Official。0.13 Codex 真实使用待测试，不冒充已验收。
- 本轮起点为 `b97c368`（完成四类 Agent 配置能力），当时仅用户 `image.png` 未跟踪。本轮未提交/推送；用户在工作期间删除了 `构思与调研.md` 及其 README 链接，已保留该用户改动，截图也保留。
- `examples/cc-switch` 仅本地参考，继续被 Git 忽略，不恢复跟踪。

本轮说明：[19-首页工作目录选择](19-首页工作目录选择.md)；验证：[0.14.0](validation/0.14.0.json)。上一阶段：[18-Codex](18-Codex提供商配置.md)、[17-OpenCode](17-OpenCode提供商配置.md)、[16-Claude与Pi](16-Claude与Pi提供商配置.md)。

## 用户永久约束

**永远不制作协议转换或本地代理服务。** 后续 OpenCode、Codex 等也仅做原生配置文件的可视化编辑和管理。UA、Header 等仅采用 Agent 实际支持的字段，不显示无效代理开关。Claude 通用 Body 覆盖不是原生配置能力，本版没有添加。

用户已要求并完成最后的 Codex 提供商配置。本轮不自动开展 DSH 或其它扩展。Codex 必须参考 CC Switch；本地参考基线为 `5ae6ad3888ba4543f6fad343c87656a97bd69da4`，不可加入其代理/协议转换功能。

## 本版实现

### 新增首页工作目录选择

- 入口在原紫色 Ubuntu 组件内部，不加新首页卡片。目录弹层浏览 `/workspace`、`/root`，支持逐层进入、上级、刷新、新建、分页；点击「使用此目录」才保存，浏览和新建不自动改变偏好。
- 原生 `WorkingDirectories` + SharedPreferences 持久化默认目录；默认 `/workspace`，只选择现有挂载的私有 Linux 目录，不新增共享存储权限/挂载。拒绝越界、文件、符号链接和不可访问路径。
- Claude/Codex/Pi/OpenCode TUI 与 Linux Shell 新建 PTY 时，所选 guest 路径作为 proot 独立 `-w` 参数，host cwd 同步到真实项目目录。路径不经过 shell 拼接，支持中文/空格等字面目录名。
- 已有同类型 Session 直接重进，不修改当前目录；显式重启使用当前选择，确认框显示路径，且结束旧会话前先校验目标。设备 Shell、WebUI、环境检查/安装探针保持原默认目录逻辑。
- 目录失效后提示重新选择并拒绝新启动，不自动落到其他项目。Service 因打开/重启失败时保留正在运行的会话。
- 终端无 OSC 上报时显示 `launchDirectory`；Linux Shell 的 OSC 7 转义空格/百分号/查询片段符，避免目录显示错误。
- 入口：`WorkingDirectories.kt`、`TerminalDirectoryPrompt.kt`、`TerminalManager.kt`、`LinuxRuntime.kt`、`WorkingDirectoryPicker.tsx`、`Home.tsx`；快照新增 `workingDirectory` 和 `terminal.directory`；桥新增 list/set/createWorkingDirectory。

### 沿用 Codex

- OpenAI Official 默认存在，不能改名/删除，可编辑和复制；使用 Codex 原生登录，不将原生登录 token 返回编辑页。
- CC Switch `{auth, config, modelCatalog}` 结构：Auth JSON、TOML、目录 JSON 双向同步。支持名称、Key、请求地址、原生 Headers、默认/审查模型、推理档位、计划推理、1M/压缩阈值、远程压缩、禁用响应存储。
- **auth.JSON 只是库内 Key 容器，不替换原生 auth.json**。第三方 Key 投影到 `[model_providers.custom].experimental_bearer_token`，`requires_openai_auth` 按 CC Switch 保留官方登录分支判定。拒绝第三方缺独立凭据却回退官方认证；原生仅 Responses。
- 原生管理 `~/.codex/config.toml` 与 `agentm-model-catalog.json`，读取 `auth.json` 参与 revision，但不写。目录指针为 `/root/.codex/agentm-model-catalog.json`；显式外部目录路径保留且不读取/覆盖。
- 模型目录采用 CC Switch 干净原生模板、精确纯文本列表及 web_search 兼容名单；GPT 命中 Codex 0.161.0 官方目录时保留原生提示词/工具等定义，DeepSeek 官方域名使用其官方模板。可配显示名、上下文、图片、并行工具、支持/默认推理档位；声明不改变上游能力。
- TOML 严格解析和源位置编辑，切换只处理 CC Switch 提供商相关字段，保留 MCP/projects/插件和其他设置；自有独占字段只在原生值仍相同时删除。active profile 覆盖选路则拒绝切换。旧保留表名迁到合法名字，不丢内容。
- 已有加密库首次进入 Codex 也会补默认官方卡及导入当前原生提供商。保存不切换；删当前记录恢复 OpenAI Official；删未启用的相同配置副本不会误恢复官方。登录和会话保留。
- 模型查询只用第三方直接 Key 和原生静态 Header；不会读取官方 token、执行命令或展开环境认证。官方模型由 Codex 原生提供。
- 前端新增 `CodexProviderEditor.tsx`，共用控件提取到 `ProviderControls.tsx`；使用 CC Switch 同款 `smol-toml`。原生新增 `CodexDocuments.kt`、`TomlDocument.kt`（tomlj），模板和上游模型数据及许可证位于 assets。

### 已验收 OpenCode

- 沿用 Pi 的提供商增删改、复制、确认切换、模型查询、表单/源码同步。支持五种常用 SDK 和自定义 npm 包；留空 SDK 省略 `npm`，供内置提供商采用原生默认值。
- 连接与额外 SDK 参数写入 `options.apiKey/baseURL/headers` 及其他 `options`；模型为 `models.<id>` 对象，长度为 `limit.context/output`，能力为 `reasoning/modalities`；属性编辑支持 `variants`、`cost`、模型 `options` 等。复杂 JSON 逐字输入保留输入文本，避免中途自动格式化。
- 切换设置根级 `model: provider/model`，默认选首个模型。按顺序合并 `~/.config/opencode/config.json`、`opencode.json`、`opencode.jsonc`，写入最高优先级的现有文件；全无时创建 `opencode.json`。同标识低层片段移除，防止已删除字段被深合并复活；其他提供商/插件/权限/注释保留。
- 删除同标识的全局节点及指向它的 `model/small_model`；保留 `auth.json`、会话、工作区和其它原生配置。项目/环境/远程配置仍遵守 OpenCode 的原生优先级，本页只管理全局用户文件。
- 三个文件全部计入 revision 与可恢复事务，支持 JSONC 注释/尾逗号/BOM，拒绝重复键、非法 UTF-8、符号链接。禁用提供商或被白/黑名单排除的默认模型不能应用。
- 首次进入 OpenCode 通过一次性标记导入原生提供商，**已有 0.11 加密库也会导入**。复制检查库与原生全局键冲突并生成新标识。
- 模型查询依据 npm SDK 选择认证；Bedrock/未知 SDK 手动添加。不会展开 `{env:...}`、读取 `{file:...}` 或执行密钥命令。

### 沿用已验收 Claude / Pi

- 每个 Agent 栏统一为可折叠「版本」和「提供商」；默认折叠版本。
- 提供商标题右侧 +；卡片点击二次确认切换，成功提示重启对应 Agent；不自动停止/重启会话。
- 三点菜单编辑、复制、删除。编辑/新增进入共用二级页面，支持未保存草稿返回确认。
- 保存仅更新提供商库；点击卡片并确认才写入原生配置。已保存内容与当前文件不同时显示提示。
- Claude Official 默认存在，使用原生登录且不能删除。旧加密 Claude 模板首次迁入新库，旧文件保留。
- Claude：名称、API Key、请求地址；Auth Token/API Key 认证字段；Sonnet/Opus/Fable/Haiku/Subagent/兜底映射、显示名称和 1M；User-Agent/自定义请求头写入 `ANTHROPIC_CUSTOM_HEADERS`；JSON 源码与表单双向同步。
- Pi：供应商标识、名称、五种原生接口格式、API Key、Base URL、请求头、JSON 类型兼容性选项；模型 ID/名称/思考/图片输入/上下文/最大输出；源码编辑。切换合并 `models.json` 对应节点并写 `settings.json` 默认 provider/model，使用模型列表首项，不覆盖其它提供商和偏好。
- 复制在当前 Agent 下新增独立记录，密钥由原生端复制；Pi 自动生成唯一标识。已有 Pi 标识不改名，避免错误覆盖。
- 删除当前 Claude 提供商恢复官方；Pi 删除移除该原生节点，如为默认还清除默认引用。登录文件、其他配置和工作区保留。

### 模型发现

后台真实 GET `/models` 或 `/v1/models`，识别版本化地址及同源 `/anthropic` 等兼容路径回退。适配 OpenAI/Anthropic/Google 列表形状，认证失败/接口不存在如实报错，不提供假模型。携带凭据的请求不跟随重定向；仅 404/405 换候选路径；有大小和超时限制。

Claude Official 登录状态不能当作 API Key 查询模型；原生登录模型由 Claude 提供。Bedrock 没有通用 `/models`，手动添加模型。不会执行 Pi 密钥字段中的命令。

### 数据与事务

- 提供商库 `config-state/providers-v1.json`、事务日志及备份均用 Android Keystore AES-GCM 加密。
- 列表/普通快照不含密钥，进入编辑才返回源码；组件临时状态保存，不进入 Zustand 持久化。`readProvider` 响应不进入原生回复缓存，日志不记录源码。
- 固定路径拒绝符号链接，严格 JSON 拒绝重复键；库和原生文件 revision 防止覆盖外部修改。
- Pi 两个文件与库一起使用可恢复事务，写入失败回滚，启动时恢复中断事务；无法安全恢复则保留记录并阻止进入不一致状态。
- `providers-native-backup.json` 保留最近一次原生修改前备份，不被仅编辑/复制列表覆盖。
- Claude 切换保留无关配置及原始文本片段；跨提供商保留共用权限/插件等顶层设置，同一提供商的明确源码删除可移除其自有字段。

## 主要入口

| 范围 | 文件 |
| --- | --- |
| 页签、版本、列表 | `uiux-design/src/screens/NativeConfig.tsx`、`NativePackages.tsx`、`NativeProviders.tsx` |
| 首页目录、终端启动 | `screens/Home.tsx`、`WorkingDirectoryPicker.tsx`；`WorkingDirectories.kt`、`TerminalManager.kt`、`linux/LinuxRuntime.kt` |
| 二级编辑 | `screens/ProviderEditor.tsx`、`CodexProviderEditor.tsx`、`ProviderControls.tsx`、`platform/providers.ts`、`store/useApp.ts` 的 `providerEdit` 路由 |
| 原生提供商 | `config/ProviderManager.kt`、`ProviderDocuments.kt`、`CodexDocuments.kt`、`TomlDocument.kt`、`OpenCodeDocuments.kt`、`ProviderFiles.kt`、`ProviderModelDiscovery.kt`；`JsonDocument.kt` 支持等长规范化文本的源位置编辑 |
| 迁移 | `config/ClaudeProfileStore.kt` 的原生导出方法；原旧配置管理接口保留，新 UI 不再使用旧的 Claude 两块表单 |
| 桥 | `MainActivity.kt`、`BridgePolicy.kt`、`platform/native.ts` |
| 测试 | `CodexDocumentsTest/TomlDocumentTest/OpenCodeDocumentsTest/ProviderDocumentsTest/ProviderFilesTest/ProviderModelDiscoveryTest`，设备 `ProviderIntegrationTest` |
| 界面回归 | `tools/verify-provider-ui.js`、已更新的 `tools/verify-native-management.js` |

Kotlin 省略前缀为 `android/app/src/main/java/dev/agentm/app/`，前端省略前缀为 `uiux-design/src/`。

## 验证

- TypeScript strict、Vite、APK、AndroidTest APK 构建通过；**69 项 JVM 测试通过**，无跳过；Lint 0 错误、42 警告。
- 新增 `WorkingDirectoriesTest` 覆盖映射、偏好、特殊字符、校验、目录失效、排序分页和失败保存；桥策略测试覆盖新接口。
- 目录选择器 320 px 浅色、480 px 深色两组浏览器测试通过；原四组包管理/引导回归通过。使用隔离桥快照，无真实商业 API 调用。
- 新增 `WorkingDirectoryIntegrationTest`（临时目录、独立 proot pwd/OSC 7）并更新 `SessionChromeTest` 的预期路径，**仅编译，未运行设备测试**。先前版本的设备结果不计入本版验证。
- 日志：`output/android/workdir-014-build.log`；`output/playwright/workdir-014-regression.log`、`workdir-014-management-regression.log`。
- 截图：`output/playwright/workdir-home-320.png`、`workdir-home-480.png`、`workdir-picker-320.png`、`workdir-picker-480.png`。
- APK：`android/app/build/outputs/apk/debug/app-debug.apk`；SHA-256 见验证 JSON。**未覆盖安装设备**，未操作真实提供商、停止或重启 Agent 会话。

## 稳定基线与保留边界

- Ubuntu Base 24.04.5、proot、Node.js 24.21.0；五 Agent 已接通。两个 Web 服务可并行，交互终端一个。
- 0.10 首装 Ubuntu 曾在 0 字节报 `connection closed`，用户重启后成功；镜像完整下载、Bash/apt 自检通过。后续 Android 官方源探针正常，没有因此改换镜像或修改下载代码。
- 首页只启动/进入会话；配置页管理 Agent；环境页管理 Ubuntu/工具、存储与只读文件树；设置页管理偏好与权限。
- 原生引导支持已有环境、跳过可选 Agent、失败重试和重入；环境页有「设置向导」。
- 在线更新区分上游/内置/可适配版本：Claude 2.1.x、Codex 0.161.x、OpenCode 1.18.x 稳定补丁；Pi/DSH 固定完整适配清单。失败/过期不误报最新。
- 会话返回保留，显式停止才终止；重启核对进程出生身份，不能按进程名/端口杀进程。
- OpenCode Basic Auth、DSH token/Cookie 不进入 React 快照，Agent 页面没有工作台桥。WebView document-start 兼容与桌面 viewport 修复保留。
- Codex 独立沙箱仍有 `cannot establish app-server socket mount isolation`，不能把用户使用成功写成沙箱已修复。
- 未完成 arm64 真机、16 KB 页、多厂商、长时后台与全模型/插件矩阵。

工作区 `D:\agentM`；SDK `E:/androidsdk`；JDK `C:/Program Files/Java/jdk-17`；Python `py -3`；设备 `emulator-5554`（API 35、x86_64）。常规构建：设置 `JAVA_HOME` 后执行 `tools/android.ps1 -SdkPath E:/androidsdk -Test`。

用户偏好自行点击界面、登录和验证真实应用。不要在用户测试中安装 APK、停止/重启会话或清数据。无明确要求不启动子代理。
