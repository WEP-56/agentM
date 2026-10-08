# CC Switch 精简参考索引

本地为参考源码子集，来源提交 `5ae6ad3888ba4543f6fad343c87656a97bd69da4`，不再是可启动的 Tauri 应用。裁剪与重取方式见[上级说明](../README.md)。

| 阅读顺序 | 路径 | 用途 |
| --- | --- | --- |
| 1 | [CLI 安装升级](src-tauri/src/commands/misc.rs) | 包映射、路径探测、来源锚定、原生载荷修复 |
| 2 | [配置基础](src-tauri/src/config.rs) | 路径解析、私有文件与原子写入 |
| 3 | [Claude 直接写入](src-tauri/src/services/provider/claude_direct.rs) | 保留非托管内容 |
| 4 | [Codex 配置](src-tauri/src/codex_config.rs) | TOML、认证文件与回滚 |
| 5 | [OpenCode 配置](src-tauri/src/opencode_config.rs) | JSONC、格式代际、并发修改 |
| 6 | [Pi 配置](src-tauri/src/pi_config/mod.rs)；[Pi 服务](src-tauri/src/services/provider/pi.rs) | models.json 管理与原生选择边界 |
| 7 | [live](src-tauri/src/live)；[数据库](src-tauri/src/database) | 原位补丁和保存模板的结构 |
| 8 | [Provider 表单](src/components/providers/forms)；[ToolInstallRow](src/components/settings/ToolInstallRow.tsx) | React 字段和安装状态参考 |
| 9 | [Rust 测试](src-tauri/tests)；[前端测试](tests) | 可迁移的配置与安装验收样本 |

已删除代理、会话统计、无关 Agent 专项模块、完整桌面入口、图标和多语言/手册材料。保留文件可能引用裁掉的模块，这是来源完整的代码片段集合，不是已经修复依赖的产品。不得直接把 Tauri invoke 或桌面 shell 路径复制为 Android 接口。

根 LICENSE、Cargo/package 清单与锁文件保留。原 README 的功能列表描述上游完整项目。
