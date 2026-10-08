# Linux 环境开发记录

版本：0.2.0-dev；日期：2026-10-08。本阶段完成真实 proot + Ubuntu 基础环境，延续现有 React 19 / Tailwind v4 界面。

## 已实现

- APK 内置 x86_64/arm64 两套 proot 与依赖，按固定包摘要准备，构建前再次核验。
- 按实际设备架构下载 Ubuntu Base 24.04.5；两种镜像约 28.6 MiB。使用官方 HTTPS URL、固定大小和 SHA-256，支持 Range 续传。
- 后台安装服务依次执行下载、校验、暂存解压、DNS/apt 配置、真实 Bash/apt/dpkg 自检、同文件系统目录发布。页面退出不会取消安装。
- 安装进度和错误由原生任务产生，前端轮询快照；取消后保留部分下载，解压暂存目录按不跟随符号链接的方式清理。
- 安装状态使用 AtomicFile 持久化。应用被系统终止后显示中断状态；若已发布 rootfs、尚未写入最终状态，可通过“重新检查”恢复，前提是已有对应本机安装来源记录。
- 新增 Linux 终端；原 Android 设备终端保留用于诊断。当前一次只能运行一种终端，切换前需关闭现有会话。
- `/root` 对应 `files/linux-home`；`/workspace` 对应 `files/workspaces`，与可替换的 rootfs 分开。
- 引入 DSHA 的 PTY 出生身份握手：子进程在 exec 前提供 stat，宿主登记后才放行。停止时核对同一身份，不按裸 PID 或端口杀进程。
- 根据实际硬链接能力决定是否启用 `--link2symlink`；启用时同时配置 L2S 目录和一致的宿主路径绑定。

## 实际验证

模拟器：emulator-5554，Android 15 / API 35，主 ABI x86_64。

安装后实际输出：

```text
AGENTM_OS=ubuntu:24.04
GNU bash, version 5.2.21(1)-release (x86_64-pc-linux-gnu)
apt 2.8.3 (amd64)
amd64
AGENTM_LINUX_OK
```

`LinuxIntegrationTest` 覆盖实际下载/安装（未安装时）、Linux PTY、dpkg 架构输出、Activity 重建保持 PID，以及停止后后台子进程不再写入延迟标记。首次测试发现 Debian multiarch 文件名中的合法冒号被过度拒绝，已改为只拒绝 Windows 驱动器根路径；保留路径越界防护，随后安装通过。

`LinuxSafetyTest` 检查归档路径与 PID 出生身份解析；原设备终端回归继续保留。完整结果与已执行测试范围见 [0.2.0 验证记录](../output/android/verification-0.2.0.json)、[构建与回归日志](../output/android/linux-validation.log) 和 [联网验证日志](../output/android/linux-network.log)。arm64 资产已打包、核验，当前没有宣称在 arm64 真机上完成运行验证。

本轮还单独执行了 `LinuxNetworkTest`：在 Ubuntu 内运行 `apt-get update`，启用 `APT::Update::Error-Mode=any`，完成软件包索引下载与签名验证，测试通过。JVM 测试 4 项、设备终端/Linux 终端/联网测试各 1 项通过；Lint 为 0 errors（保留 22 条提示）。

最终 APK 已安装到模拟器，通过实际页面按钮打开 Linux 终端，并检查 [环境页](../output/android/linux-environment.png) 与 [Linux 终端](../output/android/linux-terminal.png) 截图。终端 Activity 正常处于前台，最终操作时段的 crash 日志没有新增记录。

## 构建与测试

```powershell
# 校验原生资产（Gradle 也自动执行）
node tools/prepare-linux-runtime.mjs

# 根据锁文件重新准备资产，不改变版本
node tools/prepare-linux-runtime.mjs --prepare

# 构建、检查、安装与两套终端回归
.\tools\android.ps1 -Install -Test -DeviceTest -LinuxTest -Serial emulator-5554
```

`-LinuxTest` 未安装时会实际下载 Ubuntu。单独的 `LinuxNetworkTest` 用于联网刷新 Ubuntu 签名包索引，不属于离线回归的默认步骤。

## 当前边界

这是完整 Ubuntu 基础用户态和可用的交互终端。尚未自动安装 Node.js、Git、Python，也未完成五个 Agent 的图形化安装、升级与配置文件管理。首页的 Agent 安装入口目前会说明下一阶段范围。

当前没有开放系统卸载、重装、发行版切换和运行时在线更新，避免在这些数据维护功能尚未完成时删除已安装系统。现有 rootfs 不会被“安装”按钮直接覆盖；来源不明的现有目录会拒绝接管。

proot 共用 Android 内核与应用 UID，提供用户态路径/权限兼容，不是 Docker 或安全隔离沙箱。兼容性配置暂显式关闭 proot 的 seccomp 加速以建立稳定基线；这不是关闭 Android 系统 seccomp。

网络当前使用基础镜像自带的 Ubuntu 软件源，DNS 初始化为 1.1.1.1 / 223.5.5.5。后续需要增加用户可选软件源与设备 DNS 同步，不能假定这一默认值覆盖所有网络。

## 下一阶段

在已验证的 Linux 环境中安装固定版本 Node.js 和基础工具，再接入首个 Agent 的包版本探测、安装/卸载与启停；随后迁入 CC Switch 的配置表单和文件读写语义。
