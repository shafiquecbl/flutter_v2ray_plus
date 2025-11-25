## 1.0.5

- **HOTFIX:** Fixed iOS framework download using prepare_command in podspec
- Framework now downloads correctly during pod install from GitHub Releases

## 1.0.4

- **HOTFIX:** Fixed Android plugin class name mismatch (fixes "cannot find symbol" error)

## 1.0.2

- **HOTFIX:** Renamed podspec file to match package name (fixes "No podspec found" error)
- Fix readme

## 1.0.1

- **iOS:** XRay.xcframework now downloads automatically via CocoaPods from GitHub Releases
- **iOS:** No manual framework download required - streamlined installation process
- **iOS:** Added SHA256 verification for framework integrity
- Updated iOS setup documentation

## 1.0.0

- Initial release
- Support for Android and iOS platforms
- VLESS/VMESS protocol support
- Shadowsocks, Trojan, and Socks 5 support
- VPN mode and local proxy mode
- URL parser for share links
- Server delay testing
- Fine-grained routing configuration
