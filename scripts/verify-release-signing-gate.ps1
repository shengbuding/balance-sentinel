[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$gradle = Join-Path $repoRoot "gradlew.bat"
$missingConfig = Join-Path ([System.IO.Path]::GetTempPath()) ("wallet-sentinel-missing-{0}.properties" -f [guid]::NewGuid())
$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("wallet-sentinel-signing-{0}" -f [guid]::NewGuid())

function Invoke-GradleCapture {
    param([string[]]$Arguments)
    $previousErrorAction = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $gradle @Arguments 2>&1 | Out-String
        return @{ ExitCode = $LASTEXITCODE; Output = $output }
    } finally {
        $ErrorActionPreference = $previousErrorAction
    }
}

function Get-Sha256Hex {
    param([string]$Path)
    $sha256 = [Security.Cryptography.SHA256]::Create()
    try {
        $stream = [IO.File]::OpenRead($Path)
        try {
            return ([BitConverter]::ToString($sha256.ComputeHash($stream))).Replace("-", "")
        } finally {
            $stream.Dispose()
        }
    } finally {
        $sha256.Dispose()
    }
}

try {
    $missing = Invoke-GradleCapture @(
        "assembleRelease",
        "--project-prop",
        "walletSentinel.signingConfigFile=$missingConfig",
        "--no-parallel",
        "--no-daemon"
    )
    if ($missing.ExitCode -eq 0 -or $missing.Output -notmatch "RELEASE_SIGNING_CONFIG_REQUIRED") {
        throw "Missing signing config did not fail with RELEASE_SIGNING_CONFIG_REQUIRED.`n$($missing.Output)"
    }

    New-Item -ItemType Directory -Path $tempRoot | Out-Null
    $keystore = Join-Path $tempRoot "release-test.jks"
    $certificate = Join-Path $tempRoot "release-test.der"
    $properties = Join-Path $tempRoot "keystore.properties"
    $password = "wallet-sentinel-test"
    $alias = "wallet-sentinel-release-test"

    & keytool.exe -genkeypair -noprompt -keystore $keystore -storepass $password `
        -keypass $password -alias $alias -keyalg RSA -keysize 2048 -validity 30 `
        -dname "CN=Wallet Sentinel Test,OU=CI,O=Balance Sentinel,L=Test,ST=Test,C=CN" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "keytool failed to create the test keystore" }

    @(
        "storeFile=release-test.jks",
        "storePassword=$password",
        "keyAlias=$alias",
        "keyPassword=$password"
    ) | Set-Content -LiteralPath $properties -Encoding ascii

    $signed = Invoke-GradleCapture @(
        "assembleRelease",
        "--project-prop",
        "walletSentinel.signingConfigFile=$properties",
        "--no-parallel",
        "--no-daemon"
    )
    if ($signed.ExitCode -ne 0) {
        throw "Release build with a complete signing config failed.`n$($signed.Output)"
    }

    $apk = Join-Path $repoRoot "app/build/outputs/apk/release/app-release.apk"
    if (-not (Test-Path -LiteralPath $apk -PathType Leaf)) { throw "Release APK was not produced: $apk" }

    $sdkRoot = $env:ANDROID_HOME
    if ([string]::IsNullOrWhiteSpace($sdkRoot)) { $sdkRoot = $env:ANDROID_SDK_ROOT }
    if ([string]::IsNullOrWhiteSpace($sdkRoot)) {
        $localProperties = Join-Path $repoRoot "local.properties"
        $sdkLine = Get-Content -LiteralPath $localProperties | Where-Object { $_ -like "sdk.dir=*" } | Select-Object -First 1
        $sdkRoot = ($sdkLine -replace '^sdk.dir=', '').Replace('\:', ':').Replace('\\', '\')
    }
    $apksigner = Get-ChildItem (Join-Path $sdkRoot "build-tools") -Recurse -Filter apksigner.bat `
        | Sort-Object FullName -Descending | Select-Object -First 1 -ExpandProperty FullName
    if (-not $apksigner) { throw "apksigner.bat was not found in the Android SDK" }

    $verifyOutput = & $apksigner verify --verbose --print-certs $apk 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) { throw "apksigner rejected the release APK.`n$verifyOutput" }
    $digestMatch = [regex]::Match($verifyOutput, "certificate SHA-256 digest:\s*([0-9a-fA-F]+)")
    if (-not $digestMatch.Success) { throw "Unable to read the APK signer SHA-256 digest.`n$verifyOutput" }
    $apkDigest = $digestMatch.Groups[1].Value.ToUpperInvariant()

    & keytool.exe -exportcert -keystore $keystore -storepass $password -alias $alias -file $certificate | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "keytool failed to export the test certificate" }
    $expectedDigest = Get-Sha256Hex $certificate
    if ($apkDigest -ne $expectedDigest) {
        throw "Release APK signer mismatch. Expected $expectedDigest, got $apkDigest"
    }

    $debugKeystore = Join-Path $env:USERPROFILE ".android/debug.keystore"
    if (Test-Path -LiteralPath $debugKeystore -PathType Leaf) {
        $debugCertificate = Join-Path $tempRoot "debug.der"
        $previousErrorAction = $ErrorActionPreference
        try {
            $ErrorActionPreference = "Continue"
            & keytool.exe -exportcert -keystore $debugKeystore -storepass android `
                -alias androiddebugkey -file $debugCertificate 2>$null | Out-Null
            $debugExportExit = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $previousErrorAction
        }
        if ($debugExportExit -eq 0 -and $apkDigest -eq (Get-Sha256Hex $debugCertificate)) {
            throw "Release APK is signed with the Android Debug certificate"
        }
    }

    Write-Output "Release signing gate passed. APK signer SHA-256: $apkDigest"
} finally {
    $resolvedTemp = [IO.Path]::GetFullPath($tempRoot)
    $resolvedSystemTemp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    if ($resolvedTemp.StartsWith($resolvedSystemTemp, [StringComparison]::OrdinalIgnoreCase) -and
        (Split-Path -Leaf $resolvedTemp).StartsWith("wallet-sentinel-signing-")) {
        Remove-Item -LiteralPath $resolvedTemp -Recurse -Force -ErrorAction SilentlyContinue
    }
}
