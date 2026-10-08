# 本轮交付检查

日期：2026-10-08。范围为开发文档、精简参考源码与 TypeScript 设计契约。

| 检查 | 结果 |
| --- | --- |
| 保留的上游文件 | 1085 个文件逐一核对清理前 SHA-256，全部一致 |
| 删除结果 | 3120 个计划删除文件均不存在，已移除的参考项目目录也不存在 |
| 新增/更新文档链接 | 83 个初次检查的本地链接均可解析；最终计数见 JSON 结果 |
| 项目来源 | 两个 40 位提交号及 snapshot/buildable 标识正确 |
| 官方资料 | 7 个页面读取成功，记录时间、摘录和摘要 |
| npm 元数据 | 5 个 Agent 候选包已记录版本和分发信息 |
| TypeScript 契约 | TypeScript 5.9.3，strict + noEmit，通过 |
| 清理脚本 | PowerShell 语法解析通过；先预览再执行，删除前检查绝对路径、链接和文件摘要 |

类型检查命令：

```powershell
npm.cmd exec --yes --package typescript@5.9.3 -- tsc --noEmit --strict --target ES2022 --lib ES2022,DOM docs/contracts/workbench.ts
```

机器可读结果：[verification-results.json](verification-results.json)。裁剪清单：[reference-cleanup.json](reference-cleanup.json)。体积统计只计算参考目录，不混入本次文档和审计记录。

未执行：上游裁剪版的 Gradle/Cargo/React 全量构建或测试、Android 安装、rootfs 构建、真实模型请求、五个 Agent 真机兼容性测试。参考集有意不是完整构建工程，保留测试是后续移植的用例依据。
