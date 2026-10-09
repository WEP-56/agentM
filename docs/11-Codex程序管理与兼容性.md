# Codex 程序管理与兼容性

版本：0.6.0-dev；日期：2026-10-09。已接入 Codex 的安装、版本检查、原生终端启动、停止与卸载。**当前模拟器上的 Codex 命令沙箱自检未通过，因此尚不能宣称 Codex 工具执行完整可用。**

## 已完成的程序管理

Codex 固定版本为 `0.161.0`。首页显示真实安装版本及沙箱检查结果；环境页支持安装、检查和卸载。Claude 与 Codex 分别使用独立安装槽位，卸载 Codex 保留 `/root/.codex`、工作区和 Claude 安装。

程序包来自 npm 的官方 `@openai/codex` 平台版本：`0.161.0-linux-x64` / `0.161.0-linux-arm64`，按发布的 SHA-512 校验，再锁定 Android 下载时使用的 SHA-256 和完整大小。两种架构均已下载核验；仅 x86_64 进行了运行测试。

完整保留平台包中的 46 个文件，包括 `bin/codex`、code-mode host、`codex-path/rg`、bwrap、资源布局元数据和各组件声明。运行入口由锁定清单指定，检查时验证对应目录、版本标记及必要辅助文件，不接受前端任意路径。

上游 npm wrapper 的主要职责是选择平台二进制并转发信号；agentM 直接在已受管的 PTY 中运行该原生入口，保持完整目录布局。新会话的工作目录为 `/workspace`，用户目录仍是持久 `/root`。GUI 启动附加 `-c check_for_update_on_startup=false`，版本更新由工作台管理；没有修改用户的 Codex 配置、认证资料、审批设置或沙箱策略。

当前仍只有一个活动终端：Claude、Codex、Linux Shell、设备 Shell 之间切换前需关闭当前会话。返回工作台会保留会话，重新打开继续原进程。

## 官方资料与参数依据

- [OpenAI Docs：Codex CLI](https://developers.openai.com/codex/cli/)：安装和原生交互入口。
- [CLI reference](https://developers.openai.com/codex/cli/reference/)：全局配置覆盖、登录及 sandbox 命令。
- [Agent approvals and security](https://developers.openai.com/codex/agent-approvals-security/)：Linux 默认使用 bwrap 与 seccomp，对宿主环境有能力要求。
- [Config reference](https://developers.openai.com/codex/config-reference/)：`check_for_update_on_startup` 可在集中管理更新时关闭。

这些页面已实际获取；固定版本 npm wrapper 也已读取并验证完整性。元数据与来源见 [Codex 包记录](research/agent-packages/codex.json)、[清单](../android/app/src/main/assets/agent-catalog.json) 和 [来源记录](research/codex-compat/sources.json)。

## 实际沙箱结果

程序能启动和命令沙箱能建立是两个独立结果。本轮检查为：

| 检查 | 结果 |
| --- | --- |
| `codex --version` | `codex-cli 0.161.0` |
| 原生 PTY | 显示 Codex 欢迎与登录选择界面 |
| Activity 重建、停止、卸载/重装 | 通过；同会话 PID 与独立数据保留已验证 |
| 独立 bwrap 最小命令启动 | 退出 0，输出 `AGENTM_BWRAP_OK`；这不等于完整沙箱认证 |
| `codex sandbox linux` | 退出 1：`cannot establish app-server socket mount isolation` |

初次隔离自检把 `CODEX_HOME` 放在 `/tmp`，Codex 拒绝在那里创建辅助入口。正式自检已改用独立的私有临时槽位，避开用户真实 CODEX_HOME 与项目；上面的失败在修正目录后仍然出现。

进一步在可丢弃的 Ubuntu 根目录视图中尝试了保持真实路径身份的 `/tmp` 映射。该试验仍失败：文件描述符的 `fdinfo` 返回挂载 ID `3231`，但当次 `/proc/self/mountinfo` 中没有对应记录。Codex 的挂载别名核对因此无法建立所要求的套接字隔离。该 ID 只是本次设备观测值，不能作为跨设备判断条件。

试验没有执行到后续的“拒绝写入”和“隐藏套接字”断言，所以这两项隔离性质也不能宣称已验证。未采用试验性映射，未迁移已安装系统的 `/tmp`，未伪造 mountinfo 或关闭 Codex 的防护。失败试验代码与结果单独归档在 [codex-compat](research/codex-compat/result.json)，不混入已通过的安装与生命周期验收。

这是当前 Android 15 / x86_64 模拟器与该运行时组合的结果，不推断所有 Android 或 arm64 设备都相同。应用在首页和环境页明确显示沙箱未通过；仅完成程序管理，不宣称模型工具调用已可用。

## 验证范围

- TypeScript strict、Vite 生产构建与 APK 构建通过。
- JVM 测试 12 项通过，新增封闭槽位路径、执行入口与跨 Agent 路径拒绝用例。
- 设备 Shell 回归与 `CodexIntegrationTest` 各 1 项通过。Codex 回归包含真实下载、版本、沙箱结果记录、原生 PTY、Activity 重建、停止、卸载重装、数据标记保留和 Claude 槽位不变。
- 额外的沙箱路径兼容性试验未通过，结果按上述限制记录。
- 本轮没有提交账号凭据、模型提示或认证请求；未验证模型调用、语音功能、Codex 配置表单或 arm64 真机。

最新机器可读结果见 [0.6.0 验证摘要](validation/0.6.0.json)。详细输出在本机 `output/android/codex-final-validation.log`、`codex-path-trial.log`；界面截图在 `codex-home.png`、`codex-terminal.png`、`codex-environment.png`。这些本机产物被 Git 忽略。

```powershell
node tools/prepare-agent-catalog.mjs
.\tools\android.ps1 -Install -Test -DeviceTest -CodexTest -Serial emulator-5554
```

后续需要在其他设备或能提供一致挂载信息的执行环境中继续验证 Codex 沙箱。其余 Agent 可独立推进；不以关闭防护作为默认兼容方案。
