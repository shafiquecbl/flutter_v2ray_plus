import 'package:plugin_platform_interface/plugin_platform_interface.dart';

import 'flutter_v2ray_method_channel.dart';
import 'model/auto_disconnect.dart';
import 'model/vless_status.dart';

abstract class FlutterV2rayPlatform extends PlatformInterface {
  /// Constructs a FlutterV2rayPlatform.
  FlutterV2rayPlatform() : super(token: _token);

  static final Object _token = Object();

  static FlutterV2rayPlatform _instance = MethodChannelFlutterV2ray();

  /// The default instance of [FlutterV2rayPlatform] to use.
  ///
  /// Defaults to [MethodChannelFlutterV2ray].
  static FlutterV2rayPlatform get instance => _instance;

  /// Platform-specific implementations should set this with their own
  /// platform-specific class that extends [FlutterV2rayPlatform] when
  /// they register themselves.
  static set instance(FlutterV2rayPlatform instance) {
    PlatformInterface.verifyToken(instance, _token);
    _instance = instance;
  }

  /// Requests VPN permission from the user (Android).
  Future<bool> requestPermission() {
    throw UnimplementedError('requestPermission() has not been implemented.');
  }

  /// Initializes the VPN plugin with platform-specific configuration.
  Future<void> initializeVless({
    required String notificationIconResourceType,
    required String notificationIconResourceName,
    required String providerBundleIdentifier,
    required String groupIdentifier,
  }) {
    throw UnimplementedError('initializeVless() has not been implemented.');
  }

  /// Starts the VPN connection with the given configuration.
  Future<void> startVless({
    required String remark,
    required String config,
    required String notificationDisconnectButtonName,
    List<String>? blockedApps,
    List<String>? bypassSubnets,
    List<String>? dnsServers,
    bool proxyOnly = false,
    bool showNotificationDisconnectButton = true,
    AutoDisconnect? autoDisconnect,
  }) {
    throw UnimplementedError('startVless() has not been implemented.');
  }

  /// Stops the VPN connection.
  Future<void> stopVless() {
    throw UnimplementedError('stopVless() has not been implemented.');
  }

  /// Measures delay/ping for a server configuration (when not connected).
  Future<int> getServerDelay({required String config, required String url}) {
    throw UnimplementedError('getServerDelay() has not been implemented.');
  }

  /// Measures delay/ping for the currently connected server.
  Future<int> getConnectedServerDelay(String url) {
    throw UnimplementedError(
      'getConnectedServerDelay() has not been implemented.',
    );
  }

  /// Gets the version of the underlying core (Xray).
  Future<String> getCoreVersion() {
    throw UnimplementedError('getCoreVersion() has not been implemented.');
  }

  /// Adds or subtracts time from auto-disconnect.
  /// Returns the new remaining time in seconds, or -1 if auto-disconnect not active.
  Future<int> updateAutoDisconnectTime(int additionalSeconds) {
    throw UnimplementedError(
      'updateAutoDisconnectTime() has not been implemented.',
    );
  }

  /// Gets current remaining time in seconds, or -1 if auto-disconnect not active.
  Future<int> getRemainingAutoDisconnectTime() {
    throw UnimplementedError(
      'getRemainingAutoDisconnectTime() has not been implemented.',
    );
  }

  /// Cancels auto-disconnect - VPN stays connected indefinitely.
  Future<void> cancelAutoDisconnect() {
    throw UnimplementedError(
      'cancelAutoDisconnect() has not been implemented.',
    );
  }

  /// Checks if VPN was auto-disconnected while app was killed.
  /// Returns true if auto-disconnect expired, false otherwise.
  Future<bool> wasAutoDisconnected() {
    throw UnimplementedError(
      'wasAutoDisconnected() has not been implemented.',
    );
  }

  /// Clears the auto-disconnect expired flag.
  /// Should be called after app has handled the expired state.
  Future<void> clearAutoDisconnectFlag() {
    throw UnimplementedError(
      'clearAutoDisconnectFlag() has not been implemented.',
    );
  }

  Stream<VlessStatus> get onStatusChanged {
    throw UnimplementedError('onStatusChanged() has not been implemented.');
  }
}

