# Agent 适配与配置管理

本文件定义五个适配器的目标范围。源码依据见[调研索引](01-源码调研与选型.md)。候选版本来自 2026-10-08 实际读取的 [npm 元数据](research/npm-snapshot.json)。后续 0.3.0 已验证 Claude Code 2.1.293 在 x86_64 Android 模拟器的安装、版本检查、原生 PTY 启停及卸载；其余 Agent、配置适配和 arm64 真机尚未验证，详见 [开发记录](08-开发工具与首个Agent.md)。

0.4.0 已进一步实现并测试 Claude 用户级 settings.json 的四个受管 env 字段、预览、冲突与备份恢复，详见 [配置开发记录](09-Claude配置文件管理.md)。下文其余适配器及完整 ProviderProfile 流程仍是目标设计。

0.5.0 已加入 Claude 多提供商模板库，支持独立密钥、编辑/删除、捕获当前配置和按模板 revision 预览应用，详见 [模板开发记录](10-Claude提供商模板.md)。其余 Agent 及模板导入/导出、同步仍未接入。

## 1. 包与执行矩阵

| Agent ID | npm 包 | 当日 latest 候选 | npm 声明 Node | 使用形态 |
| --- | --- | --- | --- | --- |
| claude | @anthropic-ai/claude-code | 2.1.293 | >=22.0.0 | PTY 中 claude |
| codex | @openai/codex | 0.161.0 | >=16 | PTY 中 codex |
| opencode | opencode-ai | 1.18.35 | 元数据未声明 | opencode web |
| pi | @earendil-works/pi-coding-agent | 1.1.0 | >=22.19.0 | PTY 中 pi |
| dsh | @deepseek-ai/dsh | 0.2.0-rc.2 | 元数据未声明 | dsh web |

Node 声明不能证明原生二进制对 Android 内核/proot 可用。前三项包含平台相关 optionalDependencies；应在 Linux arm64 guest 中安装相应产物，不能在 Windows 下载一套 node_modules 后复制过去。未声明 engines 不等于不需要 Node。

CC Switch 当前 Pi 包名已是 `@earendil-works/pi-coding-agent`，不要照过时教程使用另一历史包名。模型列表、默认模型与 npm latest 都不可在 UI 长期硬编码。

DSHA 锁定的 DSH 同为 0.2.0-rc.2，但还带运行时适配与依赖锁；单独 npm 安装同版本不代表与它具有同样行为。

## 2. 安装与升级 recipe

每个 recipe 固定 package、version、Linux 架构、来源与摘要、Node 范围、安装器版本、允许的安装脚本、probe 和受管路径。当前 npm-snapshot 只锁入口元数据，不能代替传递依赖锁、原生资产证明和完整可重现构建。

候选命令形态（仅说明，不在 Windows 执行）：

```text
<guest npm absolute path> install --global --prefix <staging-slot> <package>@<exact-version>
```

真实 executor 使用 argv 数组与可信路径，不接受前端任意命令。不同包需要自己的安装选项；Claude/OpenCode 原生包安装不能机械加 --ignore-scripts 后就宣称成功。允许的脚本需按锁定包审查与验证，不能启用全局不受限脚本。

完成安装必须检查实际 executable、--version、native payload、退出码与启动能力。npm exit 0 不代表 optional 原生包齐全。安装记录包括来源渠道和真实路径；升级锚定 agentM 托管槽位，发现用户自己安装的同名二进制时先展示来源，不覆盖未知安装。

DSH 首轮保留 DSHA 受管 runtime recipe，不用普通 npm upgrade 绕过其补丁与事务。待拆分后才支持 DSH 单包槽位。基础 Ubuntu ready 判定必须与 DSH 是否安装解耦，这是需要新增的能力。

## 3. 启动与健康检查

| Agent | 启动规格 | 必须验证 |
| --- | --- | --- |
| Claude Code | guest cwd + 环境 + claude，真实 PTY | native arm64 可执行，TUI、登录/API key、一次工具调用、Ctrl-C |
| Codex | guest cwd + 环境 + codex，真实 PTY | native arm64、认证、工作区命令、沙箱与 proot 的实际兼容 |
| OpenCode | opencode web --hostname 127.0.0.1 --port <port> | 固定版本 --help、实际端口、认证、页面和终端工具 |
| Pi | guest cwd + 环境 + pi，真实 PTY | Node/原生依赖、模型读取、认证、工具调用、中文输入 |
| DSH | dsh web --no-open --host 127.0.0.1 --port <port> | 使用 DSHA 已有 recipe，自身 token、页面、工具执行与会话写后重开 |

OpenCode 命令参数依据[官方 Web 文档](https://opencode.ai/docs/web/)；DSH 依据 DSHA 启动契约。生产发布仍按固定版本 --help 和实际启动验证。OpenCode 自动打开浏览器的副作用需在 Android recipe 中处理，不编造未验证的 --no-open 参数。

Web 进程启动后读取实际端口和认证信息。OpenCode 使用 OPENCODE_SERVER_PASSWORD/受支持用户名，凭据不回传 React 状态；DSH 使用本次启动的 token。探针必须确认 Agent 身份/协议，不能只以 HTTP 200 为准。

Codex 等工具若因 sandbox/namespace 与 proot 冲突，不得为通过测试默认启用 bypass/危险全权限；记录确切错误与可支持模式，无法保留必要边界时标记该组合不支持。

## 4. 配置目录与所有权

下表是 guest 默认路径，不是宿主 Windows 用户目录。Android 宿主将这些目录映射到自己的私有持久数据。

| Agent | 配置入口 | agentM 负责 | 保留给原生 Agent |
| --- | --- | --- | --- |
| Claude | ~/.claude/settings.json；可能存在项目层覆盖 | 托管 env 的提供商、端点、模型字段 | hooks、permissions、MCP、原生登录等未托管内容 |
| Codex | ~/.codex/config.toml；auth.json 属认证数据 | 托管模型/自定义 provider；凭据环境变量 | 原生 OAuth、项目可信配置、沙箱/审批偏好 |
| OpenCode | ~/.config/opencode/opencode.jsonc 或 .json | 所识别格式内的 provider 条目与默认模型 | 原生登录、会话数据库、插件/MCP 和项目覆盖 |
| Pi | ~/.pi/agent/models.json；settings.json 读取原生默认值 | 自定义 providers 条目 | auth.json、登录、当前会话选择；settings 写入需另有版本契约 |
| DSH | $DSH_HOME，DSHA 路径 /root/.dsh | 仅锁定版本已验证的设置与凭据投影 | profile/Cordis 插件树、会话和未验证字段 |

不能用一种 Provider JSON 原样写入五个 Agent。统一表单是视图模型，每个适配器独立序列化。不能把 API Key、OAuth、命令获取 token 当作同一种认证模式。

## 5. Claude Code

参考 CC Switch config.rs 和 services/provider/claude_direct.rs。settings.json 的已知自定义入口在 env 内：ANTHROPIC_BASE_URL、ANTHROPIC_AUTH_TOKEN 或 ANTHROPIC_API_KEY、ANTHROPIC_MODEL 等。选哪种凭据字段由固定版本和提供商协议决定，不能同时写多个互相覆盖的字段。

写入规则：只改 agentM 拥有的 env 键；保留用户 hooks、permissions、其他 env 和格式；不存在文件时创建最小对象；JSON 损坏时阻止写入并提供原文查看。切换提供商时清理上一提供商留下的已托管键，不删除其他键。

原生登录与自定义 API key 分开。模型端点需要 Claude 支持的协议，不能因为某服务写着 OpenAI-compatible 就自动用于 Claude。初版不提供协议转换代理。

## 6. Codex

本节同时核对了 OpenAI Docs：[配置基础](https://developers.openai.com/codex/config-basic/)、[配置参考](https://developers.openai.com/codex/config-reference/)，实际读取记录见 research/official-sources.json。

个人配置默认 ~/.codex/config.toml；信任项目可有项目层配置。自定义提供商使用 model_provider 与 model_providers.<id>，凭据推荐 env_key。当前官方配置参考明确 wire_api 只支持 responses；不能直接接仅支持 Chat Completions 的端点。

自定义提供商最小示意，所有值均为占位符：

```toml
model = "provider-model-id"
model_provider = "agentm_custom"

[model_providers.agentm_custom]
name = "My provider"
base_url = "https://api.example.invalid/v1"
wire_api = "responses"
env_key = "AGENTM_CODEX_KEY"
```

AGENTM_CODEX_KEY 由宿主在启动时注入，不以真实值写进 TOML。不能覆盖内置保留 provider ID。是否 requires_openai_auth 必须按认证模式验证，不能随意设 true。

CC Switch 有 auth.json + config.toml 联动写入与回滚，可借鉴事务思想，但 agentM 首版自定义 API provider 不主动篡改原生 OAuth auth.json。官方登录通过 CLI 处理。CODEX_HOME 可作为受控数据目录入口，实际优先级需随固定版本验证。

UI 区分保存的用户默认与当前项目实际生效值；检测到项目覆盖时提示来源，不宣称配置页的值必然控制所有会话。

## 7. OpenCode

CC Switch 先查 opencode.jsonc，再查 opencode.json；JSONC 保留注释并在提交前比较磁盘内容。当前兼容代码支持两代格式：

| 格式 | 配置结构 | 策略 |
| --- | --- | --- |
| v1 | provider.<id>，npm/options/models | 当前获取的官方 config 文档展示此结构；首轮围绕所选 1.x 包验证 |
| v2 | providers.<id>，package/settings/models | CC Switch 有 v2 适配，源码注释指向 OpenCode 2.0.12；不能据此把 npm 1.x 配置写成 v2 |

以安装版本能力探测和实际文件共同判定；不自动批量迁移原文件。同名 provider 在两代结构同时出现时遵循对应版本契约，不能递归合并。

v1 示意：

```json
{
  "$schema": "https://opencode.ai/config.json",
  "provider": {
    "agentm_custom": {
      "npm": "@ai-sdk/openai-compatible",
      "name": "My provider",
      "options": {
        "baseURL": "https://api.example.invalid/v1",
        "apiKey": "{env:AGENTM_OPENCODE_KEY}"
      },
      "models": { "provider-model-id": { "name": "Example model" } }
    }
  },
  "model": "agentm_custom/provider-model-id"
}
```

此例是字段形态说明，不是兼容性认证。实际模型能力、provider 包和 OpenCode 版本必须匹配。认证资料与配置分开处理，不为切换 provider 删除整个 OpenCode data 目录。

## 8. Pi

当前 CC Switch Pi adapter 管理 ~/.pi/agent/models.json 的 providers；PI_CODING_AGENT_DIR 可以覆盖 agent 目录。它读取 settings.json 的 defaultProvider/defaultModel，但 provider enable 只是把条目写到 models.json。

因此配置页显示「已添加到 Pi」与「当前原生默认」两个信息，不给新增条目打上「当前会话已切换」。要实现真正切换，先验证所选 Pi 版本的 CLI 参数或 settings 约定，再给下次启动应用；MVP 可引导用户在 Pi 的 /model 中选择。

官方[models 文档](https://raw.githubusercontent.com/earendil-works/pi/main/packages/coding-agent/docs/models.md)的兼容端点结构：

```json
{
  "providers": {
    "agentm_custom": {
      "baseUrl": "https://api.example.invalid/v1",
      "api": "openai-completions",
      "apiKey": "$AGENTM_PI_KEY",
      "models": [{ "id": "provider-model-id" }]
    }
  }
}
```

当前文档也说明原生 auth.json 凭据可能优先于 models.json apiKey；不能看到文件写入成功就认定认证来源已切换。Pi 配置还可含命令形式的凭据引用，宿主只按 schema 处理和展示，不自行执行导入配置中的命令。

## 9. DSH

先复用 DSHA 的固定 DSH recipe 与原生 Web 配置入口。DSHA 的配置层与 DSH 受管运行时是依据，不能用一个通用 provider/model/key YAML 模板代替真实 schema。

首版适配任务：定位所锁定 DSH 的 settings/credentials schema → 阅读 DSHA ConfigStore 的投影与同步 → 用固定测试样本验证保存、读回与原生 UI 生效 → 才开放对应字段。剩余高级配置保留「在 DSH 中配置」入口。

数据保留完整 $DSH_HOME，区分受管插件与用户文件。不要用整个目录覆盖来切换模型，也不要把旧版本 DeepSeek 环境变量说明视为所有当前 profile 的唯一配置入口。

## 10. 通用配置事务

ProviderProfile 是 agentM 的保存模板；Agent 原生文件仍可能被 CLI、Web UI 或用户修改。模型必须记录 nativeRevision、模板 revision 和 apply 状态。

1. 按 agentId 定位受控路径；解析语法，不跟随越界链接。
2. 读取 revision 和当前配置，只计算托管字段变更。
3. 验证 URL、协议、模型与认证模式；生成脱敏 diff。
4. 活动 session 使用旧配置时，默认暂存待应用；明确选择停止并应用才写入原生文件，避免原生热重载行为不一致。
5. 加文件写锁并重新读 revision；不匹配返回 CONFIG_CONFLICT。
6. 写事务日志与旧内容、临时文件；权限 0600；fsync/rename；多文件按可恢复步骤提交。
7. 读回并验证，记录新 revision。失败回滚需核对期间无外部更改，否则保留现场进入修复。
8. 新 session 绑定配置 revision；老 session 不冒充使用新配置。

只做配置文件管理也涉及明文凭据风险：密钥可以保存在 Keystore 支持的宿主存储，但启动注入/原生文件使用时对 Agent 可见。日志、diff、错误、备份均需按此处理。

## 11. 适配完成判定

每个 Agent 都要有版本锁、安装/升级/卸载记录、Linux arm64 探测、真实启动、一次配置应用和工具调用、停止与重入、数据保留验证。DSH 用户反馈稳定或 CC Switch 桌面安装成功不能代替其他四个 Agent 的 Android 验收。

