// You have generated a new plugin project without specifying the `--platforms`
// flag. A plugin project with no platform support was generated. To add a
// platform, run `flutter create -t plugin --platforms <platforms> .` under the
// same directory. You can also find a detailed instruction on how to add
// platforms in the `pubspec.yaml` at
// https://flutter.dev/to/pubspec-plugin-platforms.

import 'dart:convert';

import 'flutter_v2ray_platform_interface.dart';
import 'model/vless_status.dart';
import 'url/shadowsocks.dart';
import 'url/socks.dart';
import 'url/trojan.dart';
import 'url/url.dart';
import 'url/vless.dart';
import 'url/vmess.dart';

class FlutterV2ray {
  FlutterV2ray();

  /// Requests VPN permission from the user (Android).
  Future<bool> requestPermission() {
    return FlutterV2rayPlatform.instance.requestPermission();
  }

  /// Initializes the VPN plugin with platform-specific configuration.
  Future<void> initializeVless({
    String notificationIconResourceType = "mipmap",
    String notificationIconResourceName = "ic_launcher",
    String providerBundleIdentifier = "",
    String groupIdentifier = "",
  }) async {
    await FlutterV2rayPlatform.instance.initializeVless(
      notificationIconResourceType: notificationIconResourceType,
      notificationIconResourceName: notificationIconResourceName,
      providerBundleIdentifier: providerBundleIdentifier,
      groupIdentifier: groupIdentifier,
    );
  }

  /// Start FlutterVless service.
  ///
  /// config:
  ///
  ///   FlutterVless Config (json)
  ///
  /// blockedApps:
  ///
  ///   Apps that won't go through the VPN tunnel.
  ///
  ///   Contains a list of package names.
  ///
  ///   specifically for Android.
  ///
  ///   For iOS, please use blockedDomains instead, in example folder.
  ///
  /// bypassSubnets:
  ///
  ///     [Default = 0.0.0.0/0]
  ///
  ///     Add at least one route if you want the system to send traffic through the VPN interface.
  ///
  ///     Routes filter by destination addresses.
  ///
  ///     To accept all traffic, set an open route such as 0.0.0.0/0 or ::/0.
  ///
  /// proxyOnly:
  ///
  ///   If it is true, only the FlutterVless proxy will be executed,
  ///
  ///   and the VPN tunnel will not be executed.
  ///
  /// dnsServers:
  ///
  ///   Custom DNS servers for the VPN tunnel.
  ///
  ///   If null, defaults to ["8.8.8.8", "114.114.114.114"].
  ///
  ///   Example: ["94.140.14.14", "94.140.15.15"] for AdGuard DNS (ad-blocking)
  Future<void> startVless({
    required String remark,
    required String config,
    List<String>? blockedApps,
    List<String>? bypassSubnets,
    List<String>? dnsServers,
    bool proxyOnly = false,
    String notificationDisconnectButtonName = "DISCONNECT",
  }) async {
    try {
      if (jsonDecode(config) == null) {
        throw ArgumentError('The provided string is not valid JSON');
      }
    } catch (_) {
      throw ArgumentError('The provided string is not valid JSON');
    }

    await FlutterV2rayPlatform.instance.startVless(
      remark: remark,
      config: config,
      blockedApps: blockedApps,
      proxyOnly: proxyOnly,
      bypassSubnets: bypassSubnets,
      dnsServers: dnsServers,
      notificationDisconnectButtonName: notificationDisconnectButtonName,
    );
  }

  /// Stop FlutterVless service.
  Future<void> stopVless() async {
    await FlutterV2rayPlatform.instance.stopVless();
  }

  /// This method returns the real server delay of the configuration.
  Future<int> getServerDelay({
    required String config,
    String url = 'https://google.com/generate_204',
  }) async {
    try {
      if (jsonDecode(config) == null) {
        throw ArgumentError('The provided string is not valid JSON');
      }
    } catch (_) {
      throw ArgumentError('The provided string is not valid JSON');
    }
    return await FlutterV2rayPlatform.instance.getServerDelay(
      config: config,
      url: url,
    );
  }

  /// This method returns the connected server delay.
  Future<int> getConnectedServerDelay({
    String url = 'https://google.com/generate_204',
  }) async {
    return await FlutterV2rayPlatform.instance.getConnectedServerDelay(url);
  }

  // This method returns the FlutterVless Core version.
  Future<String> getCoreVersion() async {
    return await FlutterV2rayPlatform.instance.getCoreVersion();
  }

  /// parse FlutterVlessURL object from Vless share link
  ///
  /// like vmess://, vless://, trojan://, ss://, socks://
  static FlutterV2RayURL parseFromURL(String url) {
    switch (url.split("://")[0].toLowerCase()) {
      case 'vmess':
        return VmessURL(url: url);
      case 'vless':
        return VlessURL(url: url);
      case 'trojan':
        return TrojanURL(url: url);
      case 'ss':
        return ShadowSocksURL(url: url);
      case 'socks':
        return SocksURL(url: url);
      default:
        throw ArgumentError('url is invalid');
    }
  }

  Stream<VlessStatus> get onStatusChanged {
    return FlutterV2rayPlatform.instance.onStatusChanged;
  }
}
