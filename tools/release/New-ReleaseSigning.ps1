param([string]$Keytool = 'keytool')
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$secretDir = Join-Path $projectRoot '.secrets'
$secretFile = Join-Path $projectRoot 'secrtes.txt'
$keystore = Join-Path $secretDir 'android-release.jks'
if ((Test-Path -LiteralPath $secretFile) -or (Test-Path -LiteralPath $keystore)) {
    throw 'Signing material already exists. Back it up and reuse it; do not regenerate a published signing key.'
}
New-Item -ItemType Directory -Path $secretDir -Force | Out-Null
$random = [byte[]]::new(32)
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
try { $rng.GetBytes($random) } finally { $rng.Dispose() }
$password = [Convert]::ToBase64String($random)
$env:AGENTM_NEW_STORE_PASSWORD = $password
try {
    & $Keytool -genkeypair -noprompt -storetype JKS -keystore $keystore -alias agentm-release -keyalg RSA -keysize 4096 -sigalg SHA256withRSA -validity 10000 -dname 'CN=agentM, OU=Release, O=WEP-56' -storepass:env AGENTM_NEW_STORE_PASSWORD -keypass:env AGENTM_NEW_STORE_PASSWORD
    if ($LASTEXITCODE -ne 0) { throw 'keytool failed.' }
    $base64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($keystore))
    $lines = @(
        '# agentM GitHub Actions secrets. PRIVATE: do not commit or share.'
        '# Repository: https://github.com/WEP-56/agentM/settings/secrets/actions'
        '# Copy each value after = into the repository secret with the matching name.'
        '# Keep an offline backup of this file and .secrets/android-release.jks before deleting.'
        "ANDROID_KEYSTORE_BASE64=$base64"
        "ANDROID_KEYSTORE_PASSWORD=$password"
        'ANDROID_KEY_ALIAS=agentm-release'
        "ANDROID_KEY_PASSWORD=$password"
    )
    [IO.File]::WriteAllLines($secretFile, $lines, [Text.UTF8Encoding]::new($false))
} finally {
    Remove-Item Env:AGENTM_NEW_STORE_PASSWORD -ErrorAction SilentlyContinue
}
Write-Output 'Release keystore and ignored secrtes.txt created. Secret values were not printed.'
