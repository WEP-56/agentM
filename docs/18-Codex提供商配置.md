# 0.13.0 Codex 提供商配置

日期：2026-10-09。用户确认 0.12 OpenCode 可用后，要求最后补齐 Codex 配置，**必须严格参考 CC Switch，并提供与 Claude Official 相同的 OpenAI Official 默认记录**。版本：`0.13.0-dev / versionCode 16`。

## 参考依据

实现依据本地 CC Switch 对应的固定提交 `5ae6ad3888ba4543f6fad343c87656a97bd69da4`：

| 规则 | 参考源码 |
| --- | --- |
| OpenAI Official 空 auth/config 原生登录预设 | `src/config/codexProviderPresets.ts` |
| Auth JSON、TOML 双向同步，模型目录编辑 | `src/components/providers/forms/CodexFormFields.tsx`、`CodexConfigSections.tsx`、`hooks/useCodexConfigState.ts` |
| 原生直连投影、custom 路由、Key 优先级、防官方凭据回退、profile 冲突 | `src-tauri/src/live/project/codex.rs` |
| 提供商字段与共用设置边界 | `src-tauri/src/live/floor.rs` |
| 登录保留与 requires_openai_auth 判定 | `src-tauri/src/services/provider/codex_direct.rs`、`src-tauri/src/codex_config.rs` |
| 模型目录、原生 Responses 模板、GPT 原生条目、DeepSeek 官方定义 | `src-tauri/src/codex_config.rs`、`src-tauri/src/resources/` |
| 已确认纯文本模型精确列表 | `src-tauri/src/model_capabilities.rs` |

缺失的参考资源从上述固定提交补充读取，未将 `examples/cc-switch` 纳入跟踪。原生字段另外核对 [OpenAI 配置参考](https://developers.openai.com/codex/config-reference/)、[认证文档](https://developers.openai.com/codex/auth/) 和当前适配的 Codex `rust-v0.161.0` 配置 schema。

保留用户永久约束：**无协议转换、无本地代理**。因此只使用 CC Switch 的原生直连路径，不显示依赖代理的 Chat Completions、Anthropic 转换、Body 覆盖、托管 OAuth 或聚合路由。模型目录、远程压缩、Header 均为 Codex 实际支持的原生配置。

## 交互与 OpenAI Official

- Codex 页签启用提供商列表，支持新增、编辑、复制、删除、确认切换及草稿返回确认。
- **OpenAI Official** 默认存在，不能删除或改名；可编辑模型/推理等设置、复制。官方卡片不收集 API Key，也不把原生登录 token 导出到编辑页。
- 切换官方移除第三方选路、默认模型映射和生成目录指针，使用 Codex 内置 `openai`；不生成休眠代理地址。用户可以在 Codex 原生界面登录。
- 删除当前提供商恢复 OpenAI Official；删除未启用副本不影响当前配置。编辑当前卡片后再删除，使用最后实际应用的内容判断是否需要恢复官方。
- 沿用 agentM 已验收的规则：**保存只更新加密提供商库，点击卡片并二次确认才写入原生文件**；提示重启 Codex，不自动重启会话。

## 原生数据格式

采用 CC Switch 的提供商结构：

```json
{
  "auth": { "OPENAI_API_KEY": "用户填写的 Key" },
  "config": "model_provider = \"custom\"\nmodel = \"your-model\"\n[model_providers.custom]\nname = \"Custom\"\nbase_url = \"https://example.com/v1\"\nwire_api = \"responses\"\n",
  "modelCatalog": {
    "models": [{ "model": "your-model", "displayName": "Your Model", "contextWindow": 128000 }]
  }
}
```

Auth JSON 是提供商库中的 Key 容器，**不是直接替换 `~/.codex/auth.json`**。Codex 0.149 起自定义 provider 不再从该文件读取第三方 Key；应用时优先取 `auth.OPENAI_API_KEY`，再取所选表的 `experimental_bearer_token`、旧顶层 token，写入原生 `[model_providers.custom].experimental_bearer_token`。

采用 CC Switch「保留官方登录」的直连分支：`auth.json` 保持原样，只有自有 Bearer / env_key 认证时，`requires_openai_auth` 才根据登录材料和 `cli_auth_credentials_store` 判定。Header、命令、AWS 或无认证路径不回退使用官方登录。缺 Key 却要求官方认证/旧顶层改道的第三方配置会被拒绝。模型查询不读取该官方登录文件。

`model_providers.openai/ollama/lmstudio` 是保留表名，旧声明保留内容后迁到合法名称；自定义选路统一为 `custom`。未选中的其他提供商声明保留。原生 env_key、命令认证、AWS 等可通过 TOML 保存，由 Codex 执行，本项目不执行它们。

## 表单与源码

- 名称、API Key、请求地址、原生静态请求头（含 User-Agent）。接口固定为 Responses。
- 默认模型、推理档位、计划模式推理档位、审查模型、禁用响应存储。
- 按 CC Switch 开启远程压缩时将自定义提供商 `name` 设为 `OpenAI`，需上游实际支持。
- 1M 开关设置 `model_context_window = 1000000`；没有压缩阈值时补 `model_auto_compact_token_limit = 900000`，关闭时移除这两个覆盖值；也可以手动编辑数值。
- 三份源码区：Auth JSON、`config.toml`、模型目录 JSON。使用与 CC Switch 相同的 `smol-toml` 解析前端 TOML；原生用 TOML 解析器校验重复键、类型和语法，报错只返回位置，不带源文或密钥。
- 原生投影只管理 CC Switch 列出的模型、路由、兼容性和子 Agent 模型字段。MCP、projects、插件等共用设置不随提供商源码切换；界面对此有说明。切走时，上一提供商的上下文等自有字段仅在原生值仍相同时移除。
- 原生 TOML 采用语句位置编辑，保留无关语句、注释、多行字符串、数组表和 Unicode；编辑内联父表时可能重排该语句，其他成员的值保留。当前 profile 覆盖提供商选路时拒绝写入。

## 模型目录

支持真实 `/models` 查询、选择导入、手动模型 ID、显示名、上下文、图片输入、并行工具、支持的推理档位和默认档位。源码中的额外模型定义在表单编辑时保留。设置了目录时默认模型必须属于该目录；目录为空时交给 Codex 原生发现。

应用时生成 `~/.codex/agentm-model-catalog.json`，并将原生 `model_catalog_json` 指向 `/root/.codex/agentm-model-catalog.json`（当前 Ubuntu HOME）。原生目录规则按 CC Switch：

- 普通 Responses 模型使用其干净模板，保留必需的 `base_instructions`，不误带 GPT 专用 freeform 工具；支持模型行的能力和推理档位声明。
- GPT 命中当前适配的 Codex 0.161.0 官方目录时，保留官方指令、工具、窗口和推理定义，只调整模型 ID、排序、账户专属字段及 Responses Lite；不套用普通模板覆盖 GPT 定义。
- DeepSeek 官方域名使用 CC Switch 的官方目录模板，保留厂商工具和指令，再应用显式行覆盖。
- 纯文本模型按 CC Switch 精确列表识别，不把新 `-vision`/`v` 后缀误判为纯文本；显式模态声明优先。
- 按 CC Switch 的域名/模型前缀名单关闭不兼容的 `web_search`，切回时仅清理此前管理的同值字段。
- 用户明确写的外部目录路径优先，保持原样；不打开或覆盖该文件。切回官方只解除本项目生成目录的指针，保留目录文件。

OpenAI Official 使用原生模型列表，不冒用登录 token 调用通用 `/models`。第三方模型查询使用自己的直接 Key 和 `http_headers`；env_key、命令、AWS、环境 Header、查询参数认证要求手动添加模型，不执行或读取这些认证来源。

## 数据与验证

首次进入 Codex 时，即使已有 0.11/0.12 加密库，也会一次性添加 OpenAI Official，并提取已有原生提供商的相关字段。列表不含 Key；编辑源码只在临时组件状态中保存，不进入持久化 UI 状态或原生回复缓存。

`config.toml`、生成目录与加密库使用现有可恢复事务；`auth.json` 加入 revision 以检测外部登录变更，但不写入。失败回滚、符号链接拒绝、UTF-8 校验、加密备份及 source 大小限制沿用现有实现。

- TypeScript strict、Vite、APK、AndroidTest APK 构建通过；**63 项 JVM 测试通过**，无跳过；Lint 0 错误、40 警告（新增 1 条为 TOML 依赖可升级提示）。
- 提供商界面 320px 浅色、480px 深色通过，覆盖全部四类编辑器；原包管理/引导四组回归通过。
- `ProviderIntegrationTest` 扩展旧库升级、官方保护、保存不应用、Key 投影、原生登录保留、目录写入、删除副本、外部 auth 变化及编辑后删除当前配置场景，**本轮仅编译，未安装运行设备测试**。
- 本轮不安装 APK，不停止/重启用户现有应用与 Agent；未使用真实商业 API 凭据。用户已验收 0.12；0.13 Codex 真实使用待用户测试。

记录：[0.13.0.json](validation/0.13.0.json)。实现入口：`CodexDocuments.kt`、`TomlDocument.kt`、`CodexProviderEditor.tsx`、`ProviderControls.tsx`。模板及模型目录许可证随 APK assets 保留。参考项目仍在 Git 忽略目录中。
