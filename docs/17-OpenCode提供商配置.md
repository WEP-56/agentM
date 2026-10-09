# 0.12.0 OpenCode 提供商配置

日期：2026-10-09。用户确认 Claude Code、Pi 提供商配置测试无问题后，继续适配 OpenCode。本版为 `0.12.0-dev / versionCode 15`。

后续验收：用户于 2026-10-09 确认「可用」，并要求最后制作 Codex 配置，见 [18-Codex提供商配置](18-Codex提供商配置.md)。以下保留本阶段验证范围。

## 页面与原生字段

OpenCode 沿用 Pi 的提供商列表和二级编辑流程：新增、编辑、复制、删除、模型查询与导入、JSON 源码编辑、未保存返回确认。保存只更新加密提供商库；点击卡片并二次确认才应用。应用后提示重启，不自动停止或重启会话。

| 编辑项 | OpenCode 1.18.35 原生位置 |
| --- | --- |
| 供应商标识 | `provider.<key>` |
| 接口格式 / SDK npm 包 | `provider.<key>.npm` |
| API Key、Base URL、请求头 | `options.apiKey`、`options.baseURL`、`options.headers` |
| 额外 SDK 选项 | `options` 下的其他成员，如 `timeout`、`setCacheKey` |
| 模型 ID / 显示名称 | `models.<id>` / `models.<id>.name` |
| 思考、图片输入 | `reasoning`、`modalities.input` |
| 上下文、最大输出 | `limit.context`、`limit.output` |
| 模型属性定义 | 模型对象的其他成员，如 `variants`、`cost`、`options`、`tool_call`、`provider`、实际请求模型 `id` |
| 默认模型 | 根级 `model: "<key>/<model-id>"` |

提供五种常用 SDK：OpenAI Compatible、OpenAI Responses、Anthropic、Google、Amazon Bedrock，并允许填写自定义 npm 包。SDK 留空时省略 `npm`，供内置提供商使用原生默认值；不会写入空包名。内置提供商可使用 OpenCode 原有认证，不读取或复制 `auth.json`。

SDK 选项与模型属性支持布尔值、数字、对象、数组、字符串和 null，具体字段按 OpenCode 原生类型校验。输入复杂 JSON 时保留当前输入文本，避免每次键入后格式化破坏输入。已有专用表单字段不允许在额外属性里重复定义。源码与表单双向同步，修改图片能力保留 audio/pdf/video 和输出模态，修改长度保留 `limit.input`。

切换默认使用第一个模型。模型可手动添加或从真实列表中选择，不从模型名猜测能力。内置配置若仅覆盖提供商选项、没有模型定义，可以导入查看；应用前需补充所选模型。自定义 SDK 的具体行为仍由 OpenCode 和对应包处理。

## 文件、升级与保留规则

按上游顺序合并以下全局文件，后者优先：

1. `~/.config/opencode/config.json`
2. `~/.config/opencode/opencode.json`
3. `~/.config/opencode/opencode.jsonc`

三个文件均按 JSONC 读取，支持注释、尾逗号、BOM，拒绝重复键、无效 UTF-8、畸形 JSON 和符号链接。编辑使用源文本位置，保留未受管理的原文、注释、其他提供商、权限、插件和 MCP。

应用把选中提供商写入优先级最高的现有文件；全无时创建 `opencode.json`。同标识的低优先级片段一并移除，避免源码明确删除的字段在深层合并后重新出现。默认模型写入同一目标文件。原生 enabled/disabled 提供商规则以及模型 whitelist/blacklist 排除默认选择时，拒绝应用并说明原因。

删除移除三个全局文件中的同标识节点，并清除指向它的 `model` / `small_model`；其余提供商与默认引用保持不变。项目级、环境变量、远程/托管配置仍由 OpenCode 按原生优先级处理，本页仅管理全局用户文件，项目配置可能覆盖全局选择。

0.11 的 `providers-v1.json` 不更换密钥或格式。首次进入 OpenCode 时按一次性标记导入现有全局提供商；已有 Claude/Pi 库不会导致漏掉导入，也不重复生成记录。复制生成唯一供应商标识，检查提供商库及全局原生配置；已有记录标识不可直接改名。

三个全局文件均计入 revision，新增或修改高优先级文件会使旧确认失效。原生文件变更和加密库仍通过同一恢复事务提交；失败可回滚，加密备份保留。列表和普通快照不含密钥。

## 模型查询与边界

依据所选 SDK 使用 Bearer、Anthropic `x-api-key` 或 Google `x-goog-api-key`，携带 `options.headers`。继续使用真实 `/models` 查询、同源兼容路径回退、超时/大小限制及禁止凭据重定向规则。Bedrock 与无法确认查询协议的自定义 SDK 要求手动添加。查询不会执行命令、展开 `{env:...}` 或读取 `{file:...}`；这些原生引用可保存供 OpenCode 自己解析。

遵守永久约束：不制作协议转换或本地代理服务。本版未开始 Codex / DSH 提供商适配。

## 依据与实现入口

以当前适配的上游 `v1.18.35` 为准，而非直接套用参考项目的较新 V2 配置格式：

- [OpenCode provider schema](https://github.com/anomalyco/opencode/blob/v1.18.35/packages/core/src/v1/config/provider.ts)
- [全局配置加载顺序](https://github.com/anomalyco/opencode/blob/v1.18.35/packages/opencode/src/config/config.ts)
- [SDK 和模型加载](https://github.com/anomalyco/opencode/blob/v1.18.35/packages/opencode/src/provider/provider.ts)
- 本地参考：`examples/cc-switch/src/components/providers/forms/OpenCodeFormFields.tsx` 及 `opencodeProviderPresets.ts`，继续仅本地保留。

主要新增 `config/OpenCodeDocuments.kt` 与 `OpenCodeDocumentsTest.kt`；扩展 `ProviderManager`、`ProviderFiles`、`ProviderModelDiscovery`、`JsonDocument`、`ProviderEditor.tsx` 和 `platform/providers.ts`。

## 验证

详见 [0.12.0.json](validation/0.12.0.json)。

- TypeScript strict、Vite、APK、AndroidTest APK 构建通过；50 项 JVM 测试通过，无失败/跳过；Lint 0 错误、39 警告。
- 提供商页面 320 px 浅色、480 px 深色通过，包含 Claude/Pi 回归、OpenCode SDK 映射与留空、嵌套 JSON 逐字输入、模型属性、改名、源码扩展字段保留、保存不切换、确认切换、复制/删除和草稿返回。
- 原包管理与引导四组浏览器回归通过；截图位于 `output/playwright/providers-opencode-*.png`。
- `ProviderIntegrationTest` 已扩展旧库升级导入、真实 Keystore、OpenCode 保存/切换/复制/删除、外部新增配置冲突和登录保留场景，**本轮只编译，未安装/运行设备测试**。
- 本轮未覆盖安装用户设备，未停止/重启会话，未使用真实商业 API 凭据。0.11 已由用户确认；0.12 OpenCode 的真实使用待用户测试。
