param(
    [switch]$Install,
    [switch]$Test,
    [switch]$DeviceTest,
    [switch]$LinuxTest,
    [switch]$PackageTest,
    [switch]$ConfigTest,
    [string]$Serial = 'emulator-5554',
    [switch]$UseNpmMirror
)
$ErrorActionPreference = 'Stop'
$project = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$android = Join-Path $project 'android'
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
if (-not (Test-Path -LiteralPath (Join-Path $sdk 'platform-tools/adb.exe'))) { throw 'Android SDK not found. Set ANDROID_HOME to the SDK used by Flutter.' }
$sdkProperty = $sdk.Replace('\', '/').Replace(':', '\:')
[IO.File]::WriteAllText((Join-Path $android 'local.properties'), "sdk.dir=$sdkProperty`n", [Text.UTF8Encoding]::new($false))
$adb = Join-Path $sdk 'platform-tools/adb.exe'

Push-Location (Join-Path $project 'uiux-design')
try {
    if (-not (Test-Path node_modules)) {
        $installArgs = @('ci', '--no-audit', '--no-fund')
        if ($UseNpmMirror) { $installArgs += '--registry=https://registry.npmmirror.com' }
        & npm.cmd @installArgs
        if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency installation failed.' }
    }
} finally { Pop-Location }

Push-Location $android
try {
    $tasks = @(':app:assembleDebug')
    if ($Test) { $tasks += @(':app:testDebugUnitTest', ':app:lintDebug') }
    if ($DeviceTest -or $LinuxTest -or $PackageTest -or $ConfigTest) { $tasks += ':app:assembleDebugAndroidTest' }
    & .\gradlew.bat @tasks --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Android build/check failed.' }
} finally { Pop-Location }

$apk = Join-Path $android 'app/build/outputs/apk/debug/app-debug.apk'
if ($Install -or $DeviceTest -or $LinuxTest -or $PackageTest -or $ConfigTest) {
    & $adb -s $Serial install -r $apk
    if ($LASTEXITCODE -ne 0) { throw 'APK installation failed.' }
}
if ($DeviceTest -or $LinuxTest -or $PackageTest -or $ConfigTest) {
    & $adb -s $Serial install -r (Join-Path $android 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
    if ($LASTEXITCODE -ne 0) { throw 'Test APK installation failed.' }
    $classes = @()
    if ($DeviceTest) { $classes += 'dev.agentm.app.TerminalLifecycleTest' }
    if ($LinuxTest) { $classes += 'dev.agentm.app.LinuxIntegrationTest' }
    if ($PackageTest) { $classes += 'dev.agentm.app.PackageIntegrationTest' }
    if ($ConfigTest) { $classes += 'dev.agentm.app.ClaudeConfigIntegrationTest' }
    foreach ($class in $classes) {
        $result = & $adb -s $Serial shell am instrument -w -r -e class $class dev.agentm.app.test/androidx.test.runner.AndroidJUnitRunner
        $result | Write-Output
        if ($LASTEXITCODE -ne 0 -or ($result -join "`n") -notmatch 'OK \(1 test\)') { throw "Device regression failed: $class" }
    }
}
if ($Install) {
    & $adb -s $Serial shell am start -n dev.agentm.app/.MainActivity
    if ($LASTEXITCODE -ne 0) { throw 'Application launch failed.' }
}
Write-Output "APK: $apk"
