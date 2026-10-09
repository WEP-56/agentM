# GitHub 签名打包与发布

仓库：<https://github.com/WEP-56/agentM>。工作流：`.github/workflows/android-release.yml`。
只有推送 `v*` tag 才触发；普通分支 push 不发布。首个公开版本为 `v0.14.0`，Android `versionCode = 18`。

## 一次性签名配置

仓库 Settings → Secrets and variables → Actions → Repository secrets 需要四项：

| Secret | 内容 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | 正式 JKS 文件的完整 Base64，单行 |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 密码 |
| `ANDROID_KEY_ALIAS` | 签名条目 alias，本仓库为 `agentm-release` |
| `ANDROID_KEY_PASSWORD` | 对应私钥密码 |

仓库根目录的 **`secrtes.txt`**（按约定保留此拼写）已经列出真实值，采用 `名称=值` 格式。手动粘贴时，Secret 名用等号左侧，内容只复制等号右侧的整行。也可用已登录且有仓库管理权限的 GitHub CLI 一次导入：

```powershell
gh secret set --repo WEP-56/agentM --env-file secrtes.txt
```

该文件与 `.secrets/` 已被 `.gitignore` 忽略。正式签名保存在 `.secrets/android-release.jks`。
**删除本地临时文件前，请离线备份 keystore、alias 和密码**；GitHub Secrets 无法读回原值，丢失签名会导致后续 APK 无法覆盖升级。Base64 只是编码，文件中的值须按私钥保管。不要在 Issue、日志或提交中粘贴它们。

新 fork 首次建立自己的签名时，可运行以下命令；已发布仓库应复用原签名，不要重建：

```powershell
.\tools\release\New-ReleaseSigning.ps1 -Keytool 'C:/Program Files/Java/jdk-17/bin/keytool.exe'
```

脚本生成 RSA 4096 位、有效期 10000 天的 JKS，使用随机密码，拒绝覆盖现有签名文件。发布使用 GitHub 自动提供的 `GITHUB_TOKEN`，**不需要额外 PAT**。工作流仅发布 job 获得 `contents: write`；构建 job 只有读取权限。

## 发布新版本

1. 修改 `android/app/build.gradle.kts` 的 `versionName`，递增 `versionCode`。
2. 同步 `uiux-design/src/data/agents.ts` 的 `APP_VERSION`。
3. 新增对应发行说明 `docs/releases/v版本号.md`。
4. 完成验证、提交后推送分支与 tag。例如下一个版本：

```bash
node tools/release/check-version.mjs v0.14.1
git add android/app/build.gradle.kts uiux-design/src/data/agents.ts docs/releases/v0.14.1.md
git commit -m "Release v0.14.1"
git push origin main
git tag -a v0.14.1 -m "agentM v0.14.1"
git push origin v0.14.1
```

允许正式 `vX.Y.Z` 和预发布 `vX.Y.Z-alpha.N` / `beta.N` / `rc.N`；tag 必须与两处版本号一致，且存在发行说明。预发布 tag 会生成 GitHub Pre-release。

在 [Actions](https://github.com/WEP-56/agentM/actions/workflows/android-release.yml) 查看运行。流程安装 JDK 17、Node.js 24、Android SDK 36 / Build Tools 36.0.0、NDK 28.2.13676358、CMake 3.22.1，执行依赖安装、JVM 测试、Release lint、运行时与 WebView 兼容脚本校验和签名构建。CI 使用官方 Maven 源，本地继续保留原镜像策略。

APK 必须通过 `apksigner verify` 与 `zipalign` 检查，Linux 源码包必须通过锁定的 SHA-256 验证。构建结果上传到 Actions artifacts；独立发布 job 再次核验校验和，先创建草稿并上传附件，成功后公开 Release。签名文件在构建结束时清除，不会进入 artifacts。

附件包括：

- `agentM-版本号-universal.apk`：arm64-v8a / x86_64 通用正式包。
- `SHA256SUMS.txt`：APK、源码包和签名信息的 SHA-256。
- `SIGNING-CERT.txt`：APK 签名验证结果及证书摘要（公开信息）。
- `linux-runtime-sources.tar.gz`：内置 Linux 组件的完整上游源码、Termux 构建仓库、许可与 agentM 准备脚本。

失败时不会公开不完整 Release。修复网络或 Secrets 后可重跑失败任务；已有草稿会继续上传。已公开 Release 拒绝被重跑覆盖，代码修复请递增版本发布新 tag，不移动已公开 tag。

## 本地正式构建

在仓库根目录执行；以下只构建，不安装到设备，也不停止运行中的会话：

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-17'
$env:ANDROID_HOME = 'E:/androidsdk'
foreach ($line in [IO.File]::ReadAllLines((Join-Path $PWD 'secrtes.txt'))) {
    if ($line -match '^(ANDROID_KEYSTORE_PASSWORD|ANDROID_KEY_ALIAS|ANDROID_KEY_PASSWORD)=(.*)$') {
        [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
    }
}
$env:ANDROID_KEYSTORE_PATH = (Join-Path $PWD '.secrets/android-release.jks')
npm ci --prefix uiux-design
.\android\gradlew.bat -p android :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
```

输出：`android/app/build/outputs/apk/release/app-release.apk`。缺少签名环境变量时 Release 构建明确失败；Debug 构建不需要这些变量。正式签名与历史 debug 签名不同，迁移安装前须自行备份应用私有数据；后续正式版本保留相同 `applicationId` 和签名即可升级。
