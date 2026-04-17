param(
    [string]$SdkRoot = "$env:LOCALAPPDATA\Android\Sdk",
    [string]$NdkVersion = "26.1.10909125",
    [string]$BuildType = "Release"
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$opensslRoot = Join-Path $repoRoot "third_party\FreeRDP\build-phone-rdp\openssl"
$installRoot = Join-Path $repoRoot "app\src\main\jniLibs\arm64-v8a"
$sourceRoot = Join-Path $repoRoot "third_party\FreeRDP"
$buildRoot = Join-Path $sourceRoot "build-manual-arm64"

$cmakeExe = Join-Path $SdkRoot "cmake\3.22.1\bin\cmake.exe"
$ninjaExe = Join-Path $SdkRoot "cmake\3.22.1\bin\ninja.exe"
$ndkRoot = Join-Path $SdkRoot "ndk\$NdkVersion"

if (-not (Test-Path $cmakeExe)) { throw "CMake not found: $cmakeExe" }
if (-not (Test-Path $ninjaExe)) { throw "Ninja not found: $ninjaExe" }
if (-not (Test-Path $ndkRoot)) { throw "NDK not found: $ndkRoot" }
if (-not (Test-Path (Join-Path $opensslRoot "libssl.a"))) {
    throw "Missing OpenSSL static libs at $opensslRoot. Build OpenSSL for Android arm64 first."
}

New-Item -ItemType Directory -Force -Path (Join-Path $installRoot "include") | Out-Null
Copy-Item -Force (Join-Path $opensslRoot "libssl.a") $installRoot
Copy-Item -Force (Join-Path $opensslRoot "libcrypto.a") $installRoot
Copy-Item -Force (Join-Path $opensslRoot "OpenSSLConfig.cmake") $installRoot
Copy-Item -Force (Join-Path $opensslRoot "OpenSSLConfigVersion.cmake") $installRoot
Copy-Item -Recurse -Force (Join-Path $opensslRoot "include\openssl") (Join-Path $installRoot "include")

if (Test-Path $buildRoot) {
    Remove-Item -Recurse -Force $buildRoot
}

$ndkPosix = $ndkRoot -replace "\\", "/"
$ninjaPosix = $ninjaExe -replace "\\", "/"
$sourcePosix = $sourceRoot -replace "\\", "/"
$buildPosix = $buildRoot -replace "\\", "/"
$installPosix = $installRoot -replace "\\", "/"

& $cmakeExe -S $sourcePosix -B $buildPosix -G Ninja `
    "-DCMAKE_MAKE_PROGRAM=$ninjaPosix" `
    "-DANDROID_ABI=arm64-v8a" `
    "-DANDROID_PLATFORM=android-26" `
    "-DANDROID_NDK=$ndkPosix" `
    "-DCMAKE_TOOLCHAIN_FILE=$ndkPosix/build/cmake/android.toolchain.cmake" `
    "-DCMAKE_BUILD_TYPE=$BuildType" `
    "-DCMAKE_INSTALL_PREFIX=$installPosix" `
    "-DCMAKE_INSTALL_LIBDIR=." `
    "-DCMAKE_PREFIX_PATH=$installPosix" `
    "-DFREERDP_EXTERNAL_PATH=$($repoRoot -replace '\\','/')/app/src/main/jniLibs" `
    "-DWITH_CLIENT_SDL=OFF" `
    "-DWITH_SERVER=OFF" `
    "-DWITH_MANPAGES=OFF" `
    "-DWITH_SAMPLE=OFF" `
    "-DWITH_OPENH264=OFF" `
    "-DWITH_FFMPEG=OFF" `
    "-DWITH_SWSCALE=OFF" `
    "-DCHANNEL_URBDRC=OFF" `
    "-DCHANNEL_TSMF=OFF" `
    "-DCHANNEL_RDPECAM=OFF" `
    "-DCHANNEL_AUDIN=OFF" `
    "-DCHANNEL_RDPSND=OFF" `
    "-DCHANNEL_PRINTER=OFF" `
    "-DCHANNEL_PARALLEL=OFF" `
    "-DCHANNEL_SERIAL=OFF" `
    "-DCHANNEL_DRIVE=OFF" `
    "-DCHANNEL_SMARTCARD=OFF" `
    "-DBUILD_TESTING=OFF" `
    "-DFREERDP_UNIFIED_BUILD=ON"

& $cmakeExe --build $buildPosix --target install -j 8

Write-Host "FreeRDP arm64 build complete: $installRoot"
