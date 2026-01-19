/// What happens when auto-disconnect time expires
enum OnExpireBehavior {
  /// Disconnect silently without showing any notification
  disconnectSilently,

  /// Disconnect and show a notification to the user
  disconnectWithNotification,
}

/// Time format for notification display
enum TimeFormat {
  /// Shows "1h 30m 10s" - updates every second
  withSeconds,

  /// Shows "1h 30m" - cleaner, updates per minute
  withoutSeconds,
}

/// Configuration for auto-disconnect feature.
///
/// This allows VPN apps with free-time models to automatically disconnect
/// after a specified duration, even if the app is killed.
///
/// Example:
/// ```dart
/// await flutterV2ray.startVless(
///   remark: "Free Server",
///   config: serverConfig,
///   autoDisconnect: AutoDisconnect(
///     duration: 30 * 60, // 30 minutes
///     onExpire: OnExpireBehavior.disconnectWithNotification,
///     expiredNotificationMessage: "Your free VPN time has expired",
///   ),
/// );
/// ```
class AutoDisconnect {
  /// Duration after which VPN will automatically disconnect (in seconds)
  final int duration;

  /// Whether to show remaining time in notification (Android only)
  /// Format: "Connected • 1h 30m 10s remaining" or "Connected • 1h 30m remaining"
  final bool showRemainingTimeInNotification;

  /// Time format in notification
  final TimeFormat timeFormat;

  /// What happens when the time expires
  final OnExpireBehavior onExpire;

  /// Custom notification message when VPN disconnects (if onExpire == disconnectWithNotification)
  /// Default: "Free time expired - VPN disconnected"
  final String? expiredNotificationMessage;

  const AutoDisconnect({
    required this.duration,
    this.showRemainingTimeInNotification = true,
    this.timeFormat = TimeFormat.withSeconds,
    this.onExpire = OnExpireBehavior.disconnectWithNotification,
    this.expiredNotificationMessage,
  });

  /// Creates a disabled auto-disconnect configuration
  const AutoDisconnect.disabled()
      : duration = 0,
        showRemainingTimeInNotification = false,
        timeFormat = TimeFormat.withSeconds,
        onExpire = OnExpireBehavior.disconnectSilently,
        expiredNotificationMessage = null;

  /// Converts to a map for method channel
  Map<String, dynamic> toMap() => {
        'duration': duration,
        'showRemainingTimeInNotification': showRemainingTimeInNotification,
        'timeFormat': timeFormat.index,
        'onExpire': onExpire.index,
        'expiredNotificationMessage': expiredNotificationMessage,
      };
}
