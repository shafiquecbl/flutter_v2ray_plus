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
                        let response = try await self.packetTunnelManager?.sendProviderMessage(data: "xray_traffic".data(using: .utf8)!)
                        if let response = response {
                            let traffic = String(decoding: response, as: UTF8.self)
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
                    } catch {
                        print("Error in traffic: \(error.localizedDescription)")
                    }
                }
            }
            
            self.eventSink?(["\(seconds)", "\(self.uploadSpeed)", "\(self.downloadSpeed)", "\(self.totalUpload)", "\(self.totalDownload)", status])
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
        
        packetTunnelManager?.remark = remark
        packetTunnelManager?.xrayConfig = configData
        packetTunnelManager?.dnsServers = dnsServers
        
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
            statusString = "DISCONNECTED"
            stopTimer()
            // Send one last event to ensure UI shows DISCONNECTED
            self.eventSink?(["0", "0", "0", "0", "0", "DISCONNECTED"])
        case .reasserting:
            statusString = "CONNECTING"
        @unknown default:
            statusString = "DISCONNECTED"
        }
        
        self.lastStatus = statusString
        print("VPN Status Changed: \(statusString)")
    }
}
final class PacketTunnelManager: ObservableObject {
    var providerBundleIdentifier: String?
    var groupIdentifier: String?
    var appName: String = "VPN"
    var remark: String = "Xray"
    var xrayConfig: Data = "".data(using: .utf8)!
    var dnsServers: [String]?
    
    private var cancellables: Set<AnyCancellable> = []
    
    @Published private var manager: NETunnelProviderManager?
    
    @Published private(set) var isProcessing: Bool = false
    
    var status: NEVPNStatus? {
        manager.flatMap { $0.connection.status }
    }
    
    var connectedDate: Date? {
        manager.flatMap { $0.connection.connectedDate }
    }
    
    init(providerBundleIdentifier: String, groupIdentifier: String, appName: String = "VPN") {
        self.providerBundleIdentifier = providerBundleIdentifier
        self.groupIdentifier = groupIdentifier
        self.appName = appName
        isProcessing = true
        Task(priority: .userInitiated) {
            await self.reload()
            await MainActor.run {
                self.isProcessing = false
            }
        }
    }
    
    
    func reload() async {
        self.cancellables.removeAll()
        self.manager = await self.loadTunnelProviderManager()
        NotificationCenter.default
            .publisher(for: .NEVPNConfigurationChange, object: nil)
            .receive(on: DispatchQueue.main)
            .sink { [unowned self] _ in
                Task(priority: .high) {
                    self.manager = await self.loadTunnelProviderManager()
                }
            }
            .store(in: &cancellables)
        NotificationCenter.default
            .publisher(for: .NEVPNStatusDidChange)
            .receive(on: DispatchQueue.main)
            .sink { [unowned self] _ in objectWillChange.send() }
            .store(in: &cancellables)
    }
    
    func saveToPreferences() async throws {
        guard let providerBundleIdentifier = providerBundleIdentifier else {
            throw NSError(domain: "VPN", code: 1, userInfo: [NSLocalizedDescriptionKey: "Provider bundle identifier is missing."])
        }
        
        do {
            // Load ALL existing managers to check if one already exists
            let allManagers = try await NETunnelProviderManager.loadAllFromPreferences()
            
            // Find ANY manager with our providerBundleIdentifier (created by vpn_permission or us)
            let existingManager = allManagers.first(where: {
                guard let configuration = $0.protocolConfiguration as? NETunnelProviderProtocol else {
                    return false
                }
                return configuration.providerBundleIdentifier == providerBundleIdentifier
            })
            
            // Reuse existing manager (from vpn_permission) or create new one
            let manager = existingManager ?? self.manager ?? NETunnelProviderManager()
            manager.localizedDescription = appName
            manager.protocolConfiguration = {
                let configuration = NETunnelProviderProtocol()
                configuration.providerBundleIdentifier = providerBundleIdentifier
                configuration.serverAddress = "Xray"
                configuration.providerConfiguration = [
                    "xrayConfig": xrayConfig,
                    "dnsServers": dnsServers ?? []
                ]
                if #available(iOS 14.2, *) {
                    configuration.excludeLocalNetworks = true
                } else {
                    // Fallback on earlier versions
                }
                return configuration
            }()
            manager.isEnabled = true
            try await manager.saveToPreferences()
            
            await self.reload()
        } catch {
            print("Error saving VPN preferences: \\(error.localizedDescription)")
            throw error
        }
    }
    
    func removeFromPreferences() async throws {
        guard let manager = manager else {
            return
        }
        try await manager.removeFromPreferences()
    }
    
    func start() async throws {
        guard let manager = manager else {
            throw NSError(domain: "VPN", code: 1, userInfo: [NSLocalizedDescriptionKey: "Manager not found"])
        }
        
        if !manager.isEnabled {
            manager.isEnabled = true
            try await manager.saveToPreferences()
        }
        
        do {
            // Assuming you have a manager instance of NETunnelProviderManager
            try  manager.connection.startVPNTunnel()
        } catch {
            print("Failed to start VPN tunnel: \(error.localizedDescription)")
        }
    }
    
    func stop() {
        guard let manager = manager else {
            return
        }
        manager.connection.stopVPNTunnel()
    }
    
    @discardableResult
    func sendProviderMessage(data: Data) async throws -> Data? {
        guard let manager = manager else {
            return nil
        }
        
        guard let session = manager.connection as? NETunnelProviderSession else {
            throw NSError(domain: "VPN", code: 1, userInfo: [NSLocalizedDescriptionKey: "Invalid connection type"])
        }
        
        return try await withCheckedThrowingContinuation { continuation in
            do {
                try session.sendProviderMessage(data) { response in
                    continuation.resume(with: .success(response))
                }
            } catch {
                continuation.resume(with: .failure(error))
            }
        }
    }
    
    func testSaveAndLoadProfile() async -> Bool{
        do {
            try await saveToPreferences()
            
            // Now reload the manager after saving
            let _ = await loadTunnelProviderManager()
            return true
            
        } catch {
            print("Error during save and load test: \(error.localizedDescription)")
            return false
        }
    }
    
    
    private func loadTunnelProviderManager() async -> NETunnelProviderManager? {
        do {
            let managers = try await NETunnelProviderManager.loadAllFromPreferences()
            
            
            guard let reval = managers.first(where: {
                guard let configuration = $0.protocolConfiguration as? NETunnelProviderProtocol else {
                    return false
                }
                return configuration.providerBundleIdentifier == providerBundleIdentifier
            }) else {
                return nil
            }
            
            try await reval.loadFromPreferences()
            return reval
        } catch {
            print("Error loading tunnel provider manager: \(error.localizedDescription)")
            return nil
        }
    }
}


