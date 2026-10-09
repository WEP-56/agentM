# 0.11.0 Claude Code 与 Pi 提供商配置

日期：2026-10-09。用户确认上一阶段可用后，要求依据本地 CC Switch 实现统一提供商管理。本版为 `0.11.0-dev / versionCode 14`。

后续验收：用户于 2026-10-09 确认 Claude Code、Pi 提供商配置测试无问题，并要求继续 OpenCode，见 [17-OpenCode提供商配置](17-OpenCode提供商配置.md)。以下保留本阶段实现与自动化验证范围。

## 固定产品边界

用户明确：**永远不制作协议转换或本地代理服务**，后续其它 Agent 也相同。这里只做 Agent 原生配置文件的可视化编辑与管理。

本版扩展 Claude Code 与 Pi。OpenCode、Codex、DSH 保留原生登录/配置，其提供商新增按钮暂不可用，不显示虚构表单。

## 页面与交互

- 五 Agent 页签保持不变，每栏上方是默认折叠的「版本」，展开后使用原有安装、检查、更新、卸载能力。
- 下方统一为「提供商」，右侧 + 新增；卡片点击后需二次确认切换，成功提示重启对应 Agent，不自动停止或重启当前会话。
- 卡片右上角三点菜单：编辑、复制、删除。复制在同一 Agent 内生成新记录，凭据由原生端复制；Pi 副本生成不冲突的新供应商标识。
- 新增、编辑共用完整二级页面，返回时对未保存草稿确认。表单与源码双向同步，无效 JSON 阻止保存。
- 保存更新提供商库，**不会悄悄切换正在使用的凭据**；点击卡片并确认后才写入 Agent 文件。当前配置与已保存内容不同时，列表明确显示不同步。
- 删除也需确认：Claude 当前提供商删除后恢复 Claude Official；Pi 删除会移除 `models.json` 中对应项，如为默认选择则清除该默认提供商/模型。其它提供商、偏好、登录与工作区保留。

## Claude Code

始终提供 `Claude Official` 默认记录，使用 Claude 原生登录，不能删除；可编辑其模型/源码设置或复制为另一记录。新 UI 迁移旧的加密提供商模板，原旧库不删除。

表单包括名称、API Key、请求地址；高级选项包括：

- 认证字段 `ANTHROPIC_AUTH_TOKEN` / `ANTHROPIC_API_KEY`，二者不能同时设置。
- 获取模型列表，以及 Sonnet、Opus、Fable、Haiku、Subagent、默认兜底模型的实际模型 ID。
- Sonnet/Opus/Fable/Haiku 的显示名称（`*_MODEL_NAME`）。
- Sonnet/Opus/Fable/Subagent/兜底模型的 1M 声明，以原生 `[1m]` 后缀写入；这只声明能力，不改变上游实际限制。
- User-Agent 与自定义请求头共用 `ANTHROPIC_CUSTOM_HEADERS`，按每行 `Header: value` 写入。没有代理专用 Header/Body 重写引擎；Claude 原生没有通用 Body 覆盖选项，因此未添加无效开关。
- 提供商 JSON 源码查看/编辑，允许保留原生扩展字段。原生登录模式的模型由 Claude 自身提供，不能拿登录状态冒充可用于 `/models` 的 API Key。

切换时更新连接与模型字段，保留当前文件中未受此提供商管理的设置及原有文本片段。不同提供商之间切换时保留共用的权限、插件等顶层设置；重新应用同一提供商的源码编辑时，显式移除的自有字段可删除。外部已改动的无关字段不会被盲目清除。

## Pi

原生位置：`~/.pi/agent/models.json` 的 `providers.<供应商标识>`，以及 `~/.pi/agent/settings.json` 的 `defaultProvider/defaultModel`。

支持五种 Pi 原生接口：OpenAI Chat Completions、OpenAI Responses、Anthropic Messages、Google Generative AI、Amazon Bedrock。字段包括供应商标识、名称、API Key、Base URL、自定义请求头、支持 JSON 类型的兼容性选项。

模型支持手动添加和从查询结果选择导入；可配置 ID、显示名称、思考、图片输入、上下文长度与最大输出 Token 数。导入不猜测上游能力，数值和能力声明可由用户修改。切换时选用列表中的第一个模型。供应商标识创建后不改名，以避免意外覆盖其它原生节点；复制时生成新标识。

Pi 支持多个原生提供商并存：切换仅合并当前记录到 `models.json`，同时修改默认引用，不覆盖其它提供商或偏好。Amazon Bedrock 没有通用 `/models` 接口，表单明确要求手动添加模型；其真实认证继续由 Pi 原生 SDK 处理。

## 模型列表与数据保护

模型查询在原生后台线程发起，使用编辑中的实际请求地址与认证信息。兼容 `data[].id`、`models[].slug`、Google `models[].name`；不开放接口或认证失败会显示真实失败，不伪造模型列表。

先查询 base URL 下的 `/models` / `/v1/models`，识别版本化地址，支持同源 `/anthropic`、消息/响应端点的路径回退。仅 404/405 才尝试下一地址；携带凭据的请求不跟随重定向。限制响应大小和超时，不执行 Pi 配置里的动态密钥命令。

提供商库、恢复日志与备份使用 Android Keystore AES-GCM 加密，存储于 `files/config-state/`。React 列表不返回密钥；进入编辑页才返回该提供商源码，保存在组件临时状态中，不进入 Zustand 持久化。带源码的读取回复不进入原生回复缓存；日志不记录源码或凭据。

操作使用库 revision 和原生文件 revision 防止覆盖外部修改。固定文件路径拒绝符号链接；源码严格校验 JSON 重复键和数据类型。Pi 的两个文件与提供商库使用可恢复事务一起写入，失败回滚；进程中断后启动时恢复。如外部修改导致无法安全恢复，会保留记录并阻止启动到不一致状态。

`providers-native-backup.json` 单独保留最近一次原生配置写入前的加密备份，不因仅新增/复制提供商记录而覆盖。原旧 Claude 备份与模板库仍保留。

## 参考与源码入口

行为和字段依据本地 CC Switch：

- `src/components/providers/forms/ClaudeFormFields.tsx`
- `src/components/providers/forms/PiProviderForm.tsx`
- `src-tauri/src/pi_config/mod.rs`
- `src-tauri/src/services/provider/pi.rs`
- `src-tauri/src/services/model_fetch.rs`

遵循用户要求，Pi 的切换额外写入默认 provider/model，区别于 CC Switch 仅维护提供商成员的部分流程。参考副本继续仅保存在被忽略的 `examples/`，未加入开源仓库。

本项目新增 `config/ProviderManager.kt`、`ProviderFiles.kt`、`ProviderDocuments.kt`、`ProviderModelDiscovery.kt`；前端为 `NativeProviders.tsx`、`ProviderEditor.tsx`、`platform/providers.ts`。

## 验证

验证记录见 [0.11.0.json](validation/0.11.0.json)。构建日志 `output/android/providers-011-build.log`；设备测试日志 `output/android/providers-011-device.log`。

- TypeScript strict、Vite、APK、43 项 JVM 测试通过；Lint 0 错误。
- `ProviderIntegrationTest` 使用临时 home/state 目录和本机 HTTP 服务，验证真实 Android Keystore 加密、旧模板迁移、复制、切换、删除、冲突保护、配置保留及模型请求；不操作用户的实际提供商文件或 Agent 会话。
- `tools/verify-provider-ui.js` 覆盖 320 px 浅色、480 px 深色，验证折叠版本、默认官方配置、表单/源码同步、模型选取、1M、Pi 类型化选项、二次确认、复制/删除与返回草稿保护。
- `tools/verify-native-management.js` 已随折叠版本布局调整，原四组页面/包管理/引导回归通过。

尚未使用用户真实密钥调用商业模型；供应商实际协议、模型 ID、额度和能力声明仍需用户在对应 Agent 中验证。本版没有新增代理服务或协议转换。
