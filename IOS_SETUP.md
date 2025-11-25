## Podfile

Open ios/Podfile and set the platform to iOS 15

```Podfile
# Uncomment this line to define a global platform for your project
platform :ios, '15.0'
```

```bash
cd ios/
pod install
```

## Xcode Setup

- Open Runner.xcworkspace with Xcode.

### Runner target

- Set the Minimum Deployment Target to iOS 15.
- Go to the Signing & Capabilities tab.
- Add the App Group capability.
- Add the Network Extension capability and activate Packet Tunnel.

### XrayTunnel target

- Add a Network Extension Target with the name **XrayTunnel**
- Set the Minimum Deployment Target to iOS 15.
- Add the App Group capability.
- Add the Network Extension capability and activate Packet Tunnel.

#### Add XrayTunnel dependencies

- Open the Runner project and go to the Package Dependencies tab.
- Add https://github.com/EbrahimTahernejad/Tun2SocksKit to the XrayTunnel Target.
- Open the **General** tab of the **XrayTunnel** Target.
- **XRay.xcframework** will be automatically added via CocoaPods (no manual action needed).
- Add **libresolv.tbd** to Frameworks and Libraries.

> **Note**: The XRay.xcframework is automatically downloaded from GitHub Releases during `pod install`. You don't need to download it manually.

<br>

- Open ios/XrayTunnel/PacketTunnelProvider.swift.
- Paste the content of [this file](./example/ios/XrayTunnel/PacketTunnelProvider.swift).
- Open the Runner Target > Build Phases tab.
- Move **Embed Foundation Extensions** to the bottom of **Copy Bundle Resources**.

## flutter

Pass the providerBundleIdentifier and groupIdentifier to the initializeVless function:

```dart
await flutterVless.initializeVless(
    providerBundleIdentifier: "IOS Provider bundle indentifier",
    groupIdentifier: "IOS Group Identifier",
);
```
