param([switch]$Apply)
$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$examplesRoot = [IO.Path]::GetFullPath((Join-Path $workspace 'examples'))
if ($workspace -ne 'D:\agentM' -or $examplesRoot -ne 'D:\agentM\examples') { throw 'Unexpected workspace; review fixed cleanup boundary.' }
$utf8 = [Text.UTF8Encoding]::new($false)

function Keep-Dsha([string]$p) {
  if ($p -match '^(LICENSE|THIRD_PARTY_NOTICES\.md|AGENTS\.md|BUILD\.md|CONTRIBUTING\.md|README\.md|REFERENCE\.md|build\.gradle|settings\.gradle|gradle\.properties|build\.sh|gradlew|gradlew\.bat|\.gitignore|\.gitattributes)$') { return $true }
  if ($p -match '^(gradle/|ci/|app/build\.gradle$|app/proguard-rules\.pro$|app/src/main/AndroidManifest\.xml$)') { return $true }
  if ($p -match '^app/src/main/java/com/deepseekharness/app/(runtime|core|backup|util|data|recovery)/') { return $true }
  if ($p -match '^app/src/main/java/com/deepseekharness/app/(PtySession|HarnessService|DshaApp|DshaDocumentsProvider)\.java$') { return $true }
  if ($p -match '^app/src/test/(java/com/deepseekharness/app/(runtime|core|backup|util|data|recovery)/|resources/)') {
    return $p -notmatch '/(Adb|Accessibility|Device|VirtualScreen|Overlay|Shizuku|Lan|Microphone|Sms)[^/]*Test\.java$'
  }
  if ($p -match '^app/src/main/assets/licenses/') { return $true }
  if ($p -match '^app/src/main/assets/(runtime-fs|runtime-python|session-compat)/') { return $p -notmatch '\.(bin|gz|zip|so|png|jpg|jar)$' }
  if ($p -match '^app/src/main/assets/[^/]+$') {
    return $p -match '/(backup-|bridge-token-|credential-|dns-|dsha-runtime-|install-|managed-|offline-rootfs\.|plugin-|profile-settings-|rc1-|recovery-|runtime-|startup-|ubuntu-tools\.)' -and $p -notmatch '\.(bin|gz|zip|so|png|jpg)$'
  }
  if ($p -match '^tools/(dsh-runtime|termux-jni|native-session|recovery-runtime|ubuntu-tools|fixtures)/') { return $p -notmatch '\.(bin|gz|zip|so|png|jpg|jar)$' }
  if ($p -match '^tools/[^/]+$') { return $p -match '/((build|prepare|verify|test|smoke)-(dsh|runtime|proot|low-proot|standard|backup|recovery|ubuntu|credential|plugin|startup|storage|workspace|native)|runtime[_.-]|recovery_|source_text\.|generated_asset_directory\.|asset_deployment\.|asset-deployment\.|backup-dependencies\.|host-tests\.manifest\.|run-host-tests\.|run-unit-tests\.|verification_require\.|dns-compat-fixture\.|architecture-backup-core-baseline\.)' }
  if ($p -match '^docs/(source-provenance|security-model|backup-format-v5|backup-upgrade-baseline|backup-upgrade-acceptance|terminal-process-reaping-handoff|stability-acceptance|接手指南)\.md$') { return $true }
  return $false
}
function Keep-Cc([string]$p) {
  if ($p -match '^(LICENSE|README\.md|README_ZH\.md|REFERENCE\.md|package\.json|pnpm-lock\.yaml|pnpm-workspace\.yaml|rust-toolchain\.toml|tsconfig\.json|tsconfig\.node\.json|vitest\.config\.ts|\.gitignore|\.gitattributes|\.node-version)$') { return $true }
  if ($p -match '^src-tauri/(Cargo\.(toml|lock)|build\.rs)$') { return $true }
  if ($p -match '^src-tauri/src/(pi_config|live|database)/') { return $true }
  if ($p -match '^src-tauri/src/[^/]+\.rs$') { return $p -match '/(app_config|config|codex_config|opencode_config|provider|settings|store|error|jsonc_document|env|path|process|shell|terminal|test_support|claude_plugin)[^/]*\.rs$' }
  if ($p -match '^src-tauri/src/services/provider/') { return $p -notmatch '/(gemini|grok|usage|codex_login|codex_official_models)' }
  if ($p -match '^src-tauri/src/services/(config|env_checker|env_manager|pi_state|model_fetch)\.rs$') { return $true }
  if ($p -match '^src-tauri/src/commands/(misc|config|provider|pi|env|settings|model_fetch)\.rs$') { return $true }
  if ($p -match '^src-tauri/tests/') { return $p -match '(provider|config|codex|claude|opencode|pi|env|helpers|common|fixtures)' }
  if ($p -match '^src/(types/|types\.ts$|lib/schemas/|utils/)') { return $true }
  if ($p -match '^src/config/') { return $p -match '/(claudeProvider|codexProvider|opencodeProvider|piProvider)' }
  if ($p -match '^src/components/providers/') { return $p -notmatch '(Gemini|Grok|Hermes|OpenClaw|Openclaw|Mcode|Omo|Copilot|Xai|ManagedAccount|LocalProxy|CodexOAuth|CodexOauth|AuthSettings|/mode/)' }
  if ($p -match '^src/components/settings/(ToolInstallRow|ToolUpgradeConfirmDialog|ToolErrorMessage|DirectorySettings|TerminalSettings)\.tsx$') { return $true }
  if ($p -match '^src/hooks/useProviderActions\.ts$') { return $true }
  if ($p -match '^src/lib/api/(providers|pi|env|settings|model-fetch|types)\.ts$') { return $true }
  if ($p -match '^tests/') { return $p -match '(provider|Provider|Codex|codex|Claude|claude|OpenCode|opencode|PiProvider|piProvider|config|Config|ToolInstall|ToolUpgrade|setup|fixtures|msw)' -and $p -notmatch '(gemini|Gemini|grok|Grok|Hermes|hermes|OpenClaw|openclaw|Mcode|mcode|proxy|Proxy|session|Session)' }
  return $false
}

$entries = [Collections.Generic.List[object]]::new()
$summaries = [Collections.Generic.List[object]]::new()
foreach ($repo in @('DSHM','DSHA','cc-switch')) {
  $root = Join-Path $examplesRoot $repo
  if (-not (Test-Path -LiteralPath $root)) { continue }
  $resolved = (Resolve-Path -LiteralPath $root).Path
  if ($resolved -ne $root -or -not $resolved.StartsWith($examplesRoot + '\', [StringComparison]::OrdinalIgnoreCase)) { throw "Unsafe root: $root" }
  $items = @(Get-ChildItem -LiteralPath $root -Recurse -Force)
  if ($items | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }) { throw "Reparse point under $root; inspect before cleanup." }
  $files = @($items | Where-Object { -not $_.PSIsContainer })
  $before = 0L; $kept = 0L; $removed = 0; $retained = 0
  foreach ($file in $files) {
    $relative = $file.FullName.Substring($root.Length + 1).Replace('\','/')
    $keep = if ($repo -eq 'DSHM') { $false } elseif ($repo -eq 'DSHA') { Keep-Dsha $relative } else { Keep-Cc $relative }
    $before += $file.Length
    if ($keep) { $kept += $file.Length; $retained++ } else { $removed++ }
    $entries.Add([pscustomobject]@{repository=$repo;path=$relative;bytes=$file.Length;action=$(if($keep){'keep'}else{'remove'});sha256=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()})
  }
  $summaries.Add([pscustomobject]@{repository=$repo;beforeBytes=$before;retainedBytes=$kept;beforeFiles=$files.Count;retainedFiles=$retained;removedFiles=$removed})
}
$report = [ordered]@{schemaVersion=1;createdAt=[DateTime]::UtcNow.ToString('o');mode=$(if($Apply){'applied'}else{'planned'});purpose='Focused reference snapshots, not complete buildable checkouts';summary=@($summaries);files=@($entries)}
$reportPath = Join-Path $workspace 'docs/research/reference-cleanup.json'
if ($Apply -and (Test-Path -LiteralPath $reportPath)) { throw 'Cleanup record already exists; preserve the original audit instead of overwriting it.' }
if (-not $Apply) {
  $summaries | Format-Table -AutoSize
  Write-Output 'Plan only. No files removed.'
  exit
}
# Persist provenance before deleting anything. Every path is checked again below.
[IO.File]::WriteAllText($reportPath, ($report | ConvertTo-Json -Depth 6), $utf8)
foreach ($entry in $entries) {
  if ($entry.action -ne 'remove') { continue }
  $root = Join-Path $examplesRoot $entry.repository
  $target = [IO.Path]::GetFullPath((Join-Path $root $entry.path))
  if (-not $target.StartsWith($root + '\', [StringComparison]::OrdinalIgnoreCase)) { throw "Unsafe target: $target" }
  $resolved = (Resolve-Path -LiteralPath $target).Path
  if ($resolved -ne $target) { throw "Unexpected target resolution: $target" }
  if ((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant() -ne $entry.sha256) { throw "Changed after planning: $target" }
  Remove-Item -LiteralPath $target -Force
}
foreach ($repo in @('DSHM','DSHA','cc-switch')) {
  $root = Join-Path $examplesRoot $repo
  if (-not (Test-Path -LiteralPath $root)) { continue }
  foreach ($dir in (Get-ChildItem -LiteralPath $root -Directory -Recurse -Force | Sort-Object { $_.FullName.Length } -Descending)) {
    $resolved = (Resolve-Path -LiteralPath $dir.FullName).Path
    if (-not $resolved.StartsWith($root + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe empty directory' }
    if (-not (Get-ChildItem -LiteralPath $resolved -Force | Select-Object -First 1)) { Remove-Item -LiteralPath $resolved -Force }
  }
  if ($repo -eq 'DSHM') {
    if ((Resolve-Path -LiteralPath $root).Path -ne 'D:\agentM\examples\DSHM') { throw 'Unsafe DSHM target' }
    if (Get-ChildItem -LiteralPath $root -Force | Select-Object -First 1) { throw 'DSHM not empty' }
    Remove-Item -LiteralPath $root -Force
  }
}
$summaries | Format-Table -AutoSize

