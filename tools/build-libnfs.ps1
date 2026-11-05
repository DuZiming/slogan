# Windows 原生交叉编译 libnfs（LGPL，动态链接）为 Android 双 ABI。
# 依赖：Android SDK cmake;3.22.1（含 ninja）+ NDK r29。用法：powershell -ExecutionPolicy Bypass -File tools/build-libnfs.ps1
$ErrorActionPreference = "Stop"

$Sdk = "$env:LOCALAPPDATA\Android\Sdk"
$Ndk = "$Sdk\ndk\29.0.14206865"
$Cmake = "$Sdk\cmake\3.22.1\bin\cmake.exe"
# ninja.exe 与 cmake 同目录，进 PATH 才能被 -G Ninja 找到
$env:PATH = "$Sdk\cmake\3.22.1\bin;$env:PATH"
$RepoRoot = Split-Path $PSScriptRoot -Parent
$OutDir = "$RepoRoot\player\src\main\jniLibs"
$Work = Join-Path $env:TEMP "libnfs-build-fixed"

git clone --depth 1 https://github.com/sahlberg/libnfs "$Work\libnfs"
if ($LASTEXITCODE -ne 0) { throw "clone 失败" }
$Version = git -C "$Work\libnfs" rev-parse --short HEAD

foreach ($Abi in @("arm64-v8a", "armeabi-v7a")) {
    Write-Host "== build $Abi =="
    $BuildDir = "$Work\build-$Abi"
    $configureArgs = @(
        "-S", "$Work\libnfs", "-B", $BuildDir, "-G", "Ninja",
        "-DCMAKE_TOOLCHAIN_FILE=$Ndk\build\cmake\android.toolchain.cmake",
        "-DANDROID_ABI=$Abi",
        "-DANDROID_PLATFORM=android-21",
        "-DCMAKE_BUILD_TYPE=Release",
        "-DBUILD_SHARED_LIBS=ON",
        "-DENABLE_TESTS=OFF"
    )
    & $Cmake @configureArgs
    if ($LASTEXITCODE -ne 0) { throw "cmake configure 失败 ($Abi)" }
    & $Cmake --build $BuildDir --target nfs
    if ($LASTEXITCODE -ne 0) { throw "cmake build 失败 ($Abi)" }

    New-Item -ItemType Directory -Force "$OutDir\$Abi" | Out-Null
    Copy-Item "$BuildDir\lib\libnfs.so" "$OutDir\$Abi\libnfs.so" -Force
}

"$Version" | Set-Content "$OutDir\libnfs-version.txt" -Encoding ascii
Write-Host "== done: libnfs@$Version -> jniLibs 双 ABI =="
