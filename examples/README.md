# 精简参考源码

这里只保留 **DSHA 与 CC Switch**，用于源码阅读、机制提取和后续移植。当前目录已经裁剪，无 Git 元数据，**不是可直接构建的完整上游工程**。

## 来源与体积

| 项目 | 来源/提交 | 清理前 | 保留上游文件 | 清理后 |
| --- | --- | ---: | ---: | ---: |
| DSHA | https://github.com/DSH-APP/DSHA · `70e37a7dbcae83b32fc92a8a37b33af88befc0e0` | 55.29 MiB / 2464 文件 | 810 | 5.45 MiB |
| CC Switch | https://github.com/farion1231/cc-switch · `5ae6ad3888ba4543f6fad343c87656a97bd69da4` | 48.18 MiB / 1471 文件 | 275 | 4.85 MiB |

大小为清理时的上游文件统计，不含后来增加的本地索引。合计减少约 90%。精确字节数、保留/删除路径和清理前 SHA-256 见[清理记录](../docs/research/reference-cleanup.json)，提交与当前形态见[sources.lock.json](sources.lock.json)。

## 从哪里读

- [DSHA 参考索引](DSHA/REFERENCE.md)：运行时、PTY、进程身份、安装、数据与恢复。
- [CC Switch 参考索引](cc-switch/REFERENCE.md)：配置解析与安全写入、CLI 安装升级、相关 React 表单。
- [调研结论](../docs/01-源码调研与选型.md)：已经核对的行为与 agentM 选型。

保留文件沿用上游路径，源码内容未改写。各项目原 README/AGENTS 是上游背景，可能包含旧版本与已经裁掉的路径，不能据此认定本地功能完整。新增 REFERENCE.md 才是本地阅读入口。

## 清理范围

DSHA 删除官网、截图、历史审计产物、离线归档/预编译二进制、Android UI 与设备专项入口等；保留运行时/core/backup/util/data/recovery、PTY 和必要服务入口、相关测试、关键脚本、依赖锁、许可与来源说明。util/core 中仍保留部分交叉依赖，不以全文关键词机械删代码。

CC Switch 删除宣传图片、图标、完整桌面入口/打包资源、用户手册、多语言资源，以及代理、会话统计等非目标模块；保留目标 Agent 配置、live patch、数据库结构、CLI 管理、相关表单与测试。混合职责文件如 commands/misc.rs 保持整文件，避免截断有用实现。

清理采用文件粒度，不伪造一个可以独立运行的缩减工程。跨模块 import、部分上游文档链接和构建任务可能依赖已删除内容；正式提取模块时须重新建立依赖闭包。

## 需要构建时取回完整代码

另选空目录，例如在工作区创建 upstream-build，避免覆盖这里的精简快照：

```powershell
git clone https://github.com/DSH-APP/DSHA.git upstream-build/DSHA
git -C upstream-build/DSHA checkout --detach 70e37a7dbcae83b32fc92a8a37b33af88befc0e0
git clone https://github.com/farion1231/cc-switch.git upstream-build/cc-switch
git -C upstream-build/cc-switch checkout --detach 5ae6ad3888ba4543f6fad343c87656a97bd69da4
```

不要在 reference 快照内尝试 git pull、npm install、Cargo/Gradle 全量构建。此次没有下载完整 rootfs 或构建 APK。

## 许可证

DSHA 应用代码与 CC Switch 为 MIT；保留根 LICENSE。DSHA 的 proot、proroot、终端组件与 Ubuntu 软件各有许可，见[第三方声明](DSHA/THIRD_PARTY_NOTICES.md)和保留的 assets/licenses。源码精简不改变许可证义务；proroot 仍为专有许可，不能按应用 MIT 自由修改分发。
