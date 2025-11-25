import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'flutter_v2ray_platform_interface.dart';
import 'model/vless_status.dart';

/// An implementation of [FlutterV2rayPlatform] that uses method channels.
class MethodChannelFlutterV2ray extends FlutterV2rayPlatform {
  /// The method channel used to interact with the native platform.
  @visibleForTesting
  final methodChannel = const MethodChannel('flutter_v2ray');

  /// The event channel for status updates.
  final eventChannel = const EventChannel('flutter_v2ray/status');

  @override
  Future<void> initializeVless({
    required String notificationIconResourceType,
    required String notificationIconResourceName,
    required String providerBundleIdentifier,
    required String groupIdentifier,
  }) async {
    await methodChannel.invokeMethod('initializeVless', {
      "notificationIconResourceType": notificationIconResourceType,
      "notificationIconResourceName": notificationIconResourceName,
      "providerBundleIdentifier": providerBundleIdentifier,
      "groupIdentifier": groupIdentifier,
    });
  }

  @override
  Future<void> startVless({
    required String remark,
    required String config,
    required String notificationDisconnectButtonName,
    List<String>? blockedApps,
    List<String>? bypassSubnets,
    List<String>? dnsServers,
    bool proxyOnly = false,
  }) async {
    await methodChannel.invokeMethod('startVless', {
      "remark": remark,
      "config": config,
      "blocked_apps": blockedApps,
      "bypass_subnets": bypassSubnets,
      "dns_servers": dnsServers,
      "proxy_only": proxyOnly,
      "notificationDisconnectButtonName": notificationDisconnectButtonName,
    });
  }

  @override
  Future<void> stopVless() async {
    await methodChannel.invokeMethod('stopVless');
  }

  @override
  Future<int> getServerDelay({required String config, required String url}) async {
    return await methodChannel.invokeMethod('getServerDelay', {"config": config, "url": url});
  }

  @override
  Future<int> getConnectedServerDelay(String url) async {
    return await methodChannel.invokeMethod('getConnectedServerDelay', {"url": url});
  }

  @override
  Future<bool> requestPermission() async {
    return (await methodChannel.invokeMethod('requestPermission')) ?? false;
  }

  @override
  Future<String> getCoreVersion() async {
    return await methodChannel.invokeMethod('getCoreVersion');
  }

  @override
  Stream<VlessStatus> get onStatusChanged {
    return eventChannel.receiveBroadcastStream().map(
      (event) => VlessStatus(
        duration: int.parse(event[0]),
        uploadSpeed: int.parse(event[1]),
        downloadSpeed: int.parse(event[2]),
        upload: int.parse(event[3]),
        download: int.parse(event[4]),
        state: event[5],
      ),
    );
  }
}
