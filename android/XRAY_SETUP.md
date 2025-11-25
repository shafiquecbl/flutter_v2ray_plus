# Xray & Tun2socks Android Build Guide

This guide explains how to build the native libraries (`libxray.so` and `libtun2socks.so`) for the Flutter Vless plugin.

**Key Features:**
- ✅ **Android 15+ Support**: Builds with 16KB page size alignment.
- ✅ **Socket FD Passing**: `tun2socks` is patched to receive the TUN file descriptor via a Unix socket (bypassing Android process restrictions).

## Prerequisites

1. **Go (Golang)**: Version 1.21+ installed.
2. **Android NDK**: Version r27+ (recommended).
3. **macOS/Linux**: Build scripts are designed for Unix-like environments.

## 1. Environment Setup

Export the path to your Android NDK.

```bash
# Example (Adjust path to your NDK installation)
export ANDROID_NDK_HOME="$HOME/Library/Android/sdk/ndk/27.0.12077973"
```

## 2. Build Xray (`libxray.so`)

This script builds the Xray core for all Android architectures (arm64, armv7, x86, x86_64).

```bash
cd android
chmod +x build_xray.sh
./build_xray.sh
```

## 3. Build Tun2socks (`libtun2socks.so`)

We use a custom Go-based build script (`build_tun2socks.sh`) instead of the old C-based one. This ensures 16KB page alignment and includes the socket-based FD passing logic.

```bash
cd android
chmod +x build_tun2socks.sh
./build_tun2socks.sh
```

> **Note:** If `build_tun2socks.sh` is missing, create it with the following content:

<details>
<summary>Click to show build_tun2socks.sh content</summary>

```bash
#!/bin/bash
# Build script for tun2socks (Go version) with 16KB page size support

TUN2SOCKS_REPO="https://github.com/xjasonlyu/tun2socks"
TARGET_DIR="src/main/jniLibs"
NDK_PATH="${ANDROID_NDK_HOME:-/Users/vladislav/Library/Android/sdk/ndk/27.0.12077973}"
TOOLCHAIN="${NDK_PATH}/toolchains/llvm/prebuilt/darwin-x86_64"

if [ ! -d "tun2socks-go" ]; then
    git clone "$TUN2SOCKS_REPO" tun2socks-go
fi

build_tun2socks() {
    local ARCH_NAME=$1
    local GO_ARCH=$2
    local GO_ARM=$3
    local ANDROID_TARGET=$4
    local OUTPUT_DIR="${TARGET_DIR}/${ARCH_NAME}"
    
    mkdir -p "$OUTPUT_DIR"
    export CGO_ENABLED=1
    export GOOS=android
    export GOARCH=$GO_ARCH
    export GOARM=$GO_ARM
    export CC="${TOOLCHAIN}/bin/${ANDROID_TARGET}-clang"
    
    cd tun2socks-go
    # Build with 16KB page alignment
    go build -v -trimpath -ldflags "-s -w -buildid= -linkmode=external -extldflags '-Wl,-z,max-page-size=16384'" -buildmode=pie -o "../${OUTPUT_DIR}/libtun2socks.so" .
    cd ..
}

build_tun2socks "arm64-v8a" "arm64" "" "aarch64-linux-android21"
build_tun2socks "armeabi-v7a" "arm" "7" "armv7a-linux-androideabi21"
build_tun2socks "x86" "386" "" "i686-linux-android21"
build_tun2socks "x86_64" "amd64" "" "x86_64-linux-android21"
```
</details>

## 4. Verification (16KB Page Size)

To confirm that the libraries are compatible with Android 15+ (16KB page size), check the `LOAD` segment alignment using `llvm-readelf`.

```bash
# Check alignment (should be 0x4000)
$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-readelf -l src/main/jniLibs/arm64-v8a/libtun2socks.so | grep LOAD | head -1
```

**Expected Output:**
```
LOAD           ... 0x4000
```
If you see `0x1000`, it is **NOT** compatible with 16KB devices.

## 5. Troubleshooting

- **"bad file descriptor"**: This means the socket FD passing failed. Ensure `XrayVPNService.kt` is correctly sending the FD to the socket path specified in `-sock-path`.
- **"permission denied"**: Ensure the app has permissions to write to the socket file in its private directory.
