# DSHA 精简参考索引

本地为参考源码子集，来源提交 `70e37a7dbcae83b32fc92a8a37b33af88befc0e0`，不具备完整构建资产。裁剪规则与重取方式见[上级说明](../README.md)。上游 README/AGENTS 中的旧版本号不是本地选型依据。

| 阅读顺序 | 路径 | 用途 |
| --- | --- | --- |
| 1 | [ContainerRuntime](app/src/main/java/com/deepseekharness/app/runtime/ContainerRuntime.java) | proot/proroot 接口、挂载、环境 |
| 2 | [ProotBootstrap](app/src/main/java/com/deepseekharness/app/runtime/ProotBootstrap.java)；[RuntimeHostPorts](app/src/main/java/com/deepseekharness/app/runtime/RuntimeHostPorts.java) | Kotlin 门面与宿主依赖接缝 |
| 3 | [RuntimeLauncher](app/src/main/java/com/deepseekharness/app/runtime/RuntimeLauncher.java) | 命令与环境变量构造 |
| 4 | [PtySession](app/src/main/java/com/deepseekharness/app/PtySession.java)；[终端 JNI](tools/termux-jni/README.md) | PTY 与进程出生身份 |
| 5 | [WebProcessManager](app/src/main/java/com/deepseekharness/app/runtime/WebProcessManager.java) | 停止、归属、退出确认 |
| 6 | [MaintenanceCoordinator](app/src/main/java/com/deepseekharness/app/core/MaintenanceCoordinator.java)；[RuntimeTrial](app/src/main/java/com/deepseekharness/app/runtime/RuntimeTrial.java) | 维护屏障和真实试运行 |
| 7 | [UserDataLayout](app/src/main/java/com/deepseekharness/app/backup/UserDataLayout.java) | 用户数据与可替换 rootfs 分离 |
| 8 | [测试](app/src/test/java/com/deepseekharness/app/runtime)；[DSH 锁](tools/dsh-runtime/package-lock.json) | 不变式和版本依据 |
| 9 | [第三方声明](THIRD_PARTY_NOTICES.md)；[源码来源](docs/source-provenance.md) | 分发和来源边界 |

保留 core/backup/util 依赖较多，是为了阅读运行时与恢复约束，不能假定已提取为独立 Java library。原生二进制、UI、设备专项入口与部分脚本依赖已裁掉，测试用于参考用例，不代表当前可完整运行。
