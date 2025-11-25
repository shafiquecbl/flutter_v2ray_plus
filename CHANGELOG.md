
## 1.0.8

- **iOS:** Fixed duplicate VPN entries when using with vpn_permission package
- **iOS:** Now reuses existing VPN manager created by vpn_permission instead of creating new one
- **iOS:** Results in single VPN entry in iOS Settings
- **iOS:** Custom DNS servers can be passed to enable ad-blocking (e.g., AdGuard DNS)
- **iOS:** Falls back to default DNS [\"8.8.8.8\", \"114.114.114.114\"] if null
- **Feature:** Added configurable DNS servers support
- **Example:** Support for ad-blocking, tracker blocking, and family protection via DNS

## 1.0.7

- **iOS:** Fixed "Manager not found" error on first connection
- **iOS:** Fixed multiple VPN entries in iOS settings (now shows single entry with app name)
- **iOS:** Use app name instead of server config for VPN configuration display name
- **iOS:** Reload manager after save to ensure it's ready before starting connection

## 1.0.6

- **HOTFIX:** Fixed iOS plugin class name mismatch (fixes "Unknown receiver 'FlutterV2rayPlugin'" error)

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
