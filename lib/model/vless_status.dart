class VlessStatus {
  final int duration;
  final int uploadSpeed;
  final int downloadSpeed;
  final int upload;
  final int download;
  final String state;

  /// Remaining auto-disconnect time in seconds, or null if disabled
  final int? remainingTime;

  VlessStatus({
    this.duration = 0,
    this.uploadSpeed = 0,
    this.downloadSpeed = 0,
    this.upload = 0,
    this.download = 0,
    this.state = "DISCONNECTED",
    this.remainingTime,
  });
}

