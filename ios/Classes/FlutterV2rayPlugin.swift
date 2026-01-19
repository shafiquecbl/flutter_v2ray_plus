import Flutter
import UIKit
import NetworkExtension
import Combine
import XRay

public class FlutterV2rayPlugin: NSObject, FlutterPlugin, FlutterStreamHandler {
    
    private var packetTunnelManager: PacketTunnelManager? = nil
    private var appName: String = "VPN"
    
    private var timer: Timer?
    private var eventSink: FlutterEventSink?
    private var totalUpload: Int = 0
    private var totalDownload: Int = 0
    private var uploadSpeed: Int = 0
    private var downloadSpeed: Int = 0
    private var isStarting: Bool = false
    private var statusCancellable: AnyCancellable?
    private var lastStatus: String = "DISCONNECTED"
    
    public static func register(with registrar: FlutterPluginRegistrar) {
        let channel = FlutterMethodChannel(name: "flutter_v2ray", binaryMessenger: registrar.messenger())
        let instance = FlutterV2rayPlugin()
        registrar.addMethodCallDelegate(instance, channel: channel)
        let eventChannel = FlutterEventChannel(name: "flutter_v2ray/status", binaryMessenger: registrar.messenger())
        eventChannel.setStreamHandler(instance)
    }
    
    
    public func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
        self.eventSink = events
        return nil
    }
    
    public func onCancel(withArguments arguments: Any?) -> FlutterError? {
        self.eventSink = nil
        return nil
    }
    
    private func startTimer() {
        self.timer?.invalidate()
        self.timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true, block: { [weak self] _ in
            guard let self = self else { return }
            
            let status = self.lastStatus
            var seconds = 0
            
            if status == "CONNECTED" {
                let elapsed = Date().timeIntervalSince(self.packetTunnelManager?.connectedDate ?? Date())
                seconds = Int(elapsed)
                
                Task {
                    do {
                        // Get traffic stats
                        let trafficResponse = try await self.packetTunnelManager?.sendProviderMessage(data: "xray_traffic".data(using: .utf8)!)
                        if let trafficResponse = trafficResponse {
                            let traffic = String(decoding: trafficResponse, as: UTF8.self)
                            let parts = traffic.split(separator: ",")
                            if parts.count >= 2, let up = Int(parts[0]), let down = Int(parts[1]) {
                                await MainActor.run {
                                    self.uploadSpeed = up - self.totalUpload
                                    self.downloadSpeed = down - self.totalDownload
                                    self.totalUpload = up
                                    self.totalDownload = down
                                }
                            }
                        }
                        
                        // Get remaining auto-disconnect time from extension
                        let remainingResponse = try await self.packetTunnelManager?.sendProviderMessage(data: "auto_disconnect_remaining".data(using: .utf8)!)
                        var remainingTimeStr: String? = nil
                        if let remainingResponse = remainingResponse {
                            let remaining = String(decoding: remainingResponse, as: UTF8.self)
                            if let remainingInt = Int(remaining), remainingInt >= 0 {
                                remainingTimeStr = remaining
                            }
                        }
                        
                        await MainActor.run {
                            self.eventSink?(["\(seconds)", "\(self.uploadSpeed)", "\(self.downloadSpeed)", "\(self.totalUpload)", "\(self.totalDownload)", status, remainingTimeStr as Any])
                        }
                    } catch {
                        print("Error in timer: \(error.localizedDescription)")
                        await MainActor.run {
                            self.eventSink?(["\(seconds)", "\(self.uploadSpeed)", "\(self.downloadSpeed)", "\(self.totalUpload)", "\(self.totalDownload)", status, NSNull()])
                        }
                    }
                }
            } else {
                self.eventSink?(["\(seconds)", "\(self.uploadSpeed)", "\(self.downloadSpeed)", "\(self.totalUpload)", "\(self.totalDownload)", status, NSNull()])
            }
        })
    }
    
    private func stopTimer() {
        self.timer?.invalidate()
        self.timer = nil
        self.uploadSpeed = 0
        self.downloadSpeed = 0
        self.totalUpload = 0
        self.totalDownload = 0
        // Don't send event here, let the status observer handle it
    }
    
    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        switch call.method {
        case "requestPermission":
            requestPermission(result: result)
        case "initializeVless":
            initializeVless(call: call, result: result)
        case "startVless":
            startVless(call: call, result: result)
        case "stopVless":
            stopVless(result: result)
        case "getCoreVersion":
            getCoreVersion(result: result)
        case "getConnectedServerDelay":
            getConnectedServerDelay(call: call, result: result)
        case "getServerDelay":
            getServerDelay(call: call, result: result)
        case "updateAutoDisconnectTime":
            updateAutoDisconnectTime(call: call, result: result)
        case "getRemainingAutoDisconnectTime":
            getRemainingAutoDisconnectTime(result: result)
        case "cancelAutoDisconnect":
            cancelAutoDisconnect(result: result)
        case "wasAutoDisconnected":
            wasAutoDisconnected(result: result)
        case "clearAutoDisconnectFlag":
            clearAutoDisconnectFlag(result: result)
        default:
            result(FlutterMethodNotImplemented)
        }
    }
    
    private func stopVless(result: FlutterResult) {
        packetTunnelManager?.stop()
        stopTimer()
        result(nil)
    }
    
    private func getConnectedServerDelay(call: FlutterMethodCall, result: @escaping FlutterResult){
        guard let arguments = call.arguments as? [String: Any],
              let url = arguments["url"] as? String else{
            result(FlutterError(code: "INVALID_ARGUMENTS", message: "Invalid arguments for getConnectedServerDelay.", details: nil))
            return
        }
        Task {
            do {
                let delay = try await packetTunnelManager?.sendProviderMessage(data: "xray_delay\(url)".data(using: .utf8)!) ?? "-1".data(using: .utf8)!
                result(Int(String(decoding: delay, as: UTF8.self)))
            }catch{
                result(-1)
            }
        }
    }
    
    private func getServerDelay(call: FlutterMethodCall, result: @escaping FlutterResult) {
        guard let arguments = call.arguments as? [String: Any],
              let url = arguments["url"] as? String,
              let config = arguments["config"] as? String else{
            result(FlutterError(code: "INVALID_ARGUMENTS", message: "Invalid arguments for getServerDelay.", details: nil))
            return
        }
        Task {
            var error: NSError?
            var delay: Int64 = -1
            XRayMeasureOutboundDelay(config, url, &delay, &error)
            result(delay)
        }
    }
    
    private func startVless(call: FlutterMethodCall, result: @escaping FlutterResult) {
        guard !isStarting else {
            result(FlutterError(code: "BUSY", message: "VPN is already starting.", details: nil))
            return
        }
        
        guard let arguments = call.arguments as? [String: Any],
              let remark = arguments["remark"] as? String,
              let config = arguments["config"] as? String,
              let configData = config.data(using: .utf8) else {
            result(FlutterError(code: "INVALID_ARGUMENTS", message: "Invalid arguments for startVless.", details: nil))
            return
        }
        let dnsServers = arguments["dns_servers"] as? [String]
        
        // Parse auto-disconnect configuration for extension
        let autoDisconnect = arguments["auto_disconnect"] as? [String: Any]
        
        // Configure AutoDisconnectHelper with developer's settings
        AutoDisconnectHelper.shared.configure(
            groupIdentifier: packetTunnelManager?.groupIdentifier,
            appName: appName,
            expiredMessage: autoDisconnect?["expiredNotificationMessage"] as? String
        )
        
        packetTunnelManager?.remark = remark
        packetTunnelManager?.xrayConfig = configData
        packetTunnelManager?.dnsServers = dnsServers
        packetTunnelManager?.autoDisconnect = autoDisconnect
        
        isStarting = true
        Task {
            do {
                try await packetTunnelManager?.saveToPreferences()
                try await packetTunnelManager?.start()
                await MainActor.run {
                    self.isStarting = false
                    result(nil)
                }
            } catch {
                await MainActor.run {
                    self.isStarting = false
                    result(FlutterError(code: "VPN_ERROR",
                                        message: "Failed to start VPN: \(error.localizedDescription)",
                                        details: nil))
                }
            }
        }
    }
    
    private func requestPermission(result: @escaping FlutterResult) {
        Task {
            let isGranted = await packetTunnelManager?.testSaveAndLoadProfile() ?? false
            result(isGranted)
        }
    }
    
    private func getCoreVersion(result: @escaping FlutterResult) {
        Task {
            result(XRayGetVersion())
        }
    }
    
    private func initializeVless(call: FlutterMethodCall, result: @escaping FlutterResult) {
        guard let arguments = call.arguments as? [String: Any],
              let providerBundleIdentifier = arguments["providerBundleIdentifier"] as? String,
              let groupIdentifier = arguments["groupIdentifier"] as? String else {
            result(FlutterError(code: "INVALID_ARGUMENTS", message: "Invalid arguments for initializeVless.", details: nil))
            return
        }
        if let customAppName = arguments["appName"] as? String {
            self.appName = customAppName
        } else if let displayName = Bundle.main.object(forInfoDictionaryKey: "CFBundleDisplayName") as? String {
            self.appName = displayName
        } else if let bundleName = Bundle.main.object(forInfoDictionaryKey: "CFBundleName") as? String {
            self.appName = bundleName
        }
        
        let manager = PacketTunnelManager(providerBundleIdentifier: "\(providerBundleIdentifier).XrayTunnel", groupIdentifier: groupIdentifier, appName: appName)
        self.packetTunnelManager = manager
        
        // Setup status observer
        self.statusCancellable?.cancel()
        self.statusCancellable = manager.objectWillChange
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in
                guard let self = self else { return }
                self.handleStatusChange()
            }
        
        // Initial check
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) {
            self.handleStatusChange()
        }
        
        result(nil)
    }
    
    private func handleStatusChange() {
        guard let status = self.packetTunnelManager?.status else { return }
        
        let statusString: String
        switch status {
        case .connected:
            statusString = "CONNECTED"
            if timer == nil {
                startTimer()
            }
        case .connecting:
            statusString = "CONNECTING"
            if timer == nil {
                startTimer()
            }
        case .disconnecting:
            statusString = "DISCONNECTING"
            if timer == nil {
                startTimer()
            }
        case .disconnected, .invalid:
            stopTimer()
            // Use AutoDisconnectHelper to check and handle auto-disconnect
            let adjustedStatus = AutoDisconnectHelper.shared.checkAndHandleDisconnect(currentStatus: "DISCONNECTED")
            statusString = adjustedStatus
            
            // Send event to Flutter
            self.eventSink?(["0", "0", "0", "0", "0", statusString, NSNull()])
        case .reasserting:
            statusString = "CONNECTING"
        @unknown default:
            statusString = "DISCONNECTED"
        }
        
        self.lastStatus = statusString
        print("VPN Status Changed: \(statusString)")
    }
    
    // MARK: - Auto-Disconnect Methods (delegated to AutoDisconnectHelper)
    
    private func updateAutoDisconnectTime(call: FlutterMethodCall, result: @escaping FlutterResult) {
        AutoDisconnectHelper.shared.updateTime(call: call, packetTunnelManager: packetTunnelManager, result: result)
    }
    
    private func getRemainingAutoDisconnectTime(result: @escaping FlutterResult) {
        AutoDisconnectHelper.shared.getRemainingTime(packetTunnelManager: packetTunnelManager, result: result)
    }
    
    private func cancelAutoDisconnect(result: @escaping FlutterResult) {
        AutoDisconnectHelper.shared.cancel(packetTunnelManager: packetTunnelManager, result: result)
    }
    
    private func wasAutoDisconnected(result: @escaping FlutterResult) {
        AutoDisconnectHelper.shared.handleWasAutoDisconnected(result: result)
    }
    
    private func clearAutoDisconnectFlag(result: @escaping FlutterResult) {
        AutoDisconnectHelper.shared.handleClearFlag(result: result)
    }
}

