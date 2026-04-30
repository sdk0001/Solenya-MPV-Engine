param(
    [string]$NativeArm64Source = "\\wsl.localhost\Ubuntu\home\sadik\solenya-mpv-build\mpv-android\app\src\main\libs\arm64-v8a",
    [string]$NativeArmv7Source = "\\wsl.localhost\Ubuntu\home\sadik\solenya-mpv-build\mpv-android\app\src\main\libs\armeabi-v7a",
    [string]$VersionName = "1.0.0"
)

$ErrorActionPreference = "Stop"
$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$ReleaseDir = Join-Path $RepoRoot "release"

function Copy-NativeLibs {
    param(
        [Parameter(Mandatory = $true)][string]$Source,
        [Parameter(Mandatory = $true)][string]$Abi
    )

    $destination = Join-Path $RepoRoot "native\$Abi"
    New-Item -ItemType Directory -Force -Path $destination | Out-Null
    Get-ChildItem -LiteralPath $Source -Filter *.so -File | ForEach-Object {
        Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $destination $_.Name) -Force
    }
}

Copy-NativeLibs -Source $NativeArm64Source -Abi "arm64-v8a"
Copy-NativeLibs -Source $NativeArmv7Source -Abi "armeabi-v7a"

Push-Location $RepoRoot
try {
    .\gradlew.bat :app:assembleArm64Release :app:assembleArmv7Release
    New-Item -ItemType Directory -Force -Path $ReleaseDir | Out-Null
    Copy-Item -LiteralPath "app\build\outputs\apk\arm64\release\app-arm64-release.apk" -Destination (Join-Path $ReleaseDir "Solenya-MPV-Engine-v$VersionName-arm64.apk") -Force
    Copy-Item -LiteralPath "app\build\outputs\apk\armv7\release\app-armv7-release.apk" -Destination (Join-Path $ReleaseDir "Solenya-MPV-Engine-v$VersionName-armeabi-v7a.apk") -Force
    Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $ReleaseDir "Solenya-MPV-Engine-v$VersionName-arm64.apk")
    Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $ReleaseDir "Solenya-MPV-Engine-v$VersionName-armeabi-v7a.apk")
}
finally {
    Pop-Location
}
