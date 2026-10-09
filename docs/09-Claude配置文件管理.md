# Claude 配置文件管理

版本：0.4.0-dev；日期：2026-10-08。本阶段先完成 Git 初始化与 0.3.0 基线首次提交 `0c9d3ca`，再开发当前 Claude 配置文件的读写、变更预览与恢复。

## 用户操作

Android 配置页现在读取持久主目录下的 `~/.claude/settings.json`，可以选择原生登录、API Key 或 Auth Token，填写兼容 Anthropic API 的端点和默认模型，预览后应用。文件不存在时按需创建；已有同类密钥可留空保留，不能将 API Key 自动当作 Auth Token 复用。

选择原生登录会明确预览移除文件中自定义端点、API Key 与 Auth Token 的变更，不修改 Claude 自身的 OAuth/登录资料。保存前需要关闭当前终端；页面返回不等于关闭会话。

当前只管理正在使用的这一份配置，尚未实现多个提供商模板的保存库、跨 Agent 配置迁移、模型列表查询或连通性测试。浏览器预览继续保留原设计，Android 的操作走真实桥接口。

## 管理范围与实现依据

参考 CC Switch [claude_direct.rs](https://github.com/farion1231/cc-switch/blob/5ae6ad3888ba4543f6fad343c87656a97bd69da4/src-tauri/src/services/provider/claude_direct.rs) 的受管字段补丁、私有文件权限和写入事务语义。Rust 模块不是 Android SDK，本阶段在 Kotlin 中实现对应的最小文件适配层。

| 字段 | 行为 |
| --- | --- |
| `env.ANTHROPIC_BASE_URL` | 可选完整 HTTP(S) 地址，不接受 URL 内嵌凭据、查询参数或片段 |
| `env.ANTHROPIC_MODEL` | 用户填写实际模型 ID；留空移除该 env 默认值 |
| `env.ANTHROPIC_API_KEY` | 选择 API Key 时写入或保留；移除相互冲突的 Auth Token |
| `env.ANTHROPIC_AUTH_TOKEN` | 选择 Auth Token 时写入或保留；移除相互冲突的 API Key |

其余 env 字段、hooks、permissions、MCP、自定义字段及原生登录资料均不由该适配器管理。`JsonDocument` 严格解析 JSON 并记录原文位置，只修改对应值或对象成员；未改变的字符串转义、数字写法、换行和其他字段不会被整文件重新序列化。

重复键、非对象根节点、非对象 env、受管字段类型错误、非法 JSON、超过 1 MiB 的文件和符号链接路径都会阻止编辑。不能把这些情况当成空配置覆盖。

## 保存、冲突与恢复

1. 读取文件并计算包含完整原始字节的 SHA-256 revision；不存在文件使用独立的 `missing` 状态。
2. 预览时再次检查 revision，产生只展示四个受管字段的脱敏差异。候选内容留在原生内存中，最多保留 4 份，token 有效期 2 分钟。
3. 应用时要求 Linux 就绪且没有活动终端/软件维护任务，取得应用维护锁和配置写锁。
4. 将上一份完整文件加密备份；候选文件使用同目录临时文件、0600 权限、fsync。
5. rename 前再次读 revision；不匹配则报冲突，不覆盖后来内容。rename 后同步父目录并读回验证。
6. 冲突提示保留当前表单草稿，用户可重新读取 revision 后再次检查差异；不会自动覆盖。
7. “恢复上次保存前”也先预览，仅当当前文件仍匹配上次保存的结果时允许恢复。若原文件不存在，则恢复为不存在。

锁只协调 agentM 自身的写入，不能让任意外部编辑器参加同一锁协议。revision 在提交前后检查，能检测常见外部修改；这不是操作系统对任意写者提供的 compare-and-swap。未执行断电/强制进程回收故障注入认证；突然终止时可能留下私有临时文件。该阶段不宣称实现通用多文件事务或多版本配置历史。

## 密钥与配置来源

- 原生读取接口仅返回 `hasApiKey` / `hasAuthToken`，不会返回原密钥。
- 新输入只保存在当前表单临时状态并经专用配置方法提交，不进入 Zustand 持久状态、localStorage 或日志。
- WebView 请求去重缓存改存请求摘要，避免长期保留包含密钥的原始参数。
- 备份使用 Android Keystore 的 AES-GCM 密钥加密；恢复在原生侧解密。
- Claude 运行时需要读取原生配置，所以最终 `settings.json` 内仍包含其所需的明文凭据，保存权限为 0600。加密备份不意味着运行中的 Agent 无法读取自己的凭据。
- 页面检查 `/workspace/.claude/settings.json`、`settings.local.json` 和 `/etc/claude-code/managed-settings.json` 是否存在，提醒可能存在其他配置来源。当前页不计算完整的有效配置，也不执行 `apiKeyHelper` 或修改其他认证机制；组织策略、项目设置、其他 env 和启动参数仍可能影响实际行为。

## 实际验证

本轮在 emulator-5554 / Android 15 / x86_64 完成构建、安装与测试：

- JVM 测试共 10 项通过，其中新增 6 项覆盖源码片段保留、转义键与值、认证方式切换、增删位置、非法/重复 JSON、端点与凭据校验。
- `ClaudeConfigIntegrationTest` 通过：独立 fixture 主目录中的真实文件读写、0600 权限、密钥不回显、Keystore 加密备份、管理器重建后恢复、外部修改冲突、终端运行互斥、符号链接拒绝，以及新建文件恢复为不存在。
- TypeScript strict + Vite 构建通过；Android Lint 为 0 errors / 23 warnings。
- 实际 WebView 配置页和预览页已检查。输入 `.invalid` 示例端点和测试凭据后仅预览并取消，确认凭据不出现在预览文字或 localStorage，真实用户配置文件的 SHA-256 保持不变。

测试没有填写真实凭据或发送模型请求；未宣称特定提供商认证成功、模型回复成功或 arm64 真机通过。

机器可读记录见 [0.4.0 验证摘要](validation/0.4.0.json)。本地详细日志与截图位于 `output/android/config-validation.log`、`claude-config.png`、`claude-config-preview.png`；`output/` 是被 Git 忽略的本机验证产物目录。源码、锁文件、测试和验证摘要纳入版本控制，缓存、SDK 本机路径、密钥文件和构建 APK 不纳入。

```powershell
.\tools\android.ps1 -Install -Test -ConfigTest -Serial emulator-5554
```

下一阶段可以扩展提供商模板库，并继续接入其余 Agent 的程序管理与各自的文件适配；四个 Agent 不会共用一套未经验证的配置序列化格式。
