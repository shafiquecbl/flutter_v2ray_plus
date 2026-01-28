//
//  PacketTunnelProvider.swift
//  XrayTunnel
//
//  Created by Muhammad Shafique on 24/11/2025. https://shafique.dev/
//

import NetworkExtension
import XRay
import Tun2SocksKit
import os.log
import UserNotifications

/// Custom error types for better error handling
enum TunnelError: Error {
    case invalidConfiguration
    case missingXRayConfig
    case invalidPort
}

// MARK: - Auto-Disconnect Manager

/// Manages auto-disconnect timer functionality in the Network Extension.
/// Runs independently of the main app and survives app termination.
final class AutoDisconnectManager {
    
    // MARK: - Configuration
    
    struct Config {
        let duration: Int
        let showNotification: Bool
        let timeFormat: Int  // 0 = withSeconds, 1 = withoutSeconds
        let onExpire: Int    // 0 = silent, 1 = withNotification
        let expiredMessage: String
        let groupIdentifier: String?
        
        static func from(providerConfig: [String: Any]?) -> Config? {
            guard let autoDisconnect = providerConfig?["autoDisconnect"] as? [String: Any],
                  let duration = autoDisconnect["duration"] as? Int,
                  duration > 0 else {
                return nil
            }
            
            return Config(
                duration: duration,
                showNotification: autoDisconnect["showRemainingTimeInNotification"] as? Bool ?? true,
                timeFormat: autoDisconnect["timeFormat"] as? Int ?? 0,
                onExpire: autoDisconnect["onExpire"] as? Int ?? 1,
                expiredMessage: autoDisconnect["expiredNotificationMessage"] as? String ?? "Free time expired - VPN disconnected",
                groupIdentifier: providerConfig?["groupIdentifier"] as? String
            )
        }
    }
    
    // MARK: - Properties
    
    private var timer: DispatchSourceTimer?
    private var remainingSeconds: Int = -1
    private var config: Config?
    private weak var tunnelProvider: NEPacketTunnelProvider?
    
    private static let timestampKey = "flutter_v2ray_auto_disconnect_timestamp"
    
    var isEnabled: Bool { config != nil && remainingSeconds > 0 }
    
    // MARK: - Lifecycle
    
    func start(config: Config, tunnelProvider: NEPacketTunnelProvider) {
        self.config = config
        self.tunnelProvider = tunnelProvider
        self.remainingSeconds = config.duration
        
        os_log("AutoDisconnect: Starting with %d seconds", type: .info, config.duration)
        
        timer?.cancel()
        timer = DispatchSource.makeTimerSource(queue: DispatchQueue.main)
        timer?.schedule(deadline: .now() + 1, repeating: 1)
        timer?.setEventHandler { [weak self] in
            self?.tick()
        }
        timer?.resume()
    }
    
    func stop() {
        timer?.cancel()
        timer = nil
        remainingSeconds = -1
        config = nil
        os_log("AutoDisconnect: Stopped", type: .info)
    }
    
    // MARK: - Timer
    
    private func tick() {
        guard remainingSeconds > 0 else { return }
        
        remainingSeconds -= 1
        
        if remainingSeconds <= 0 {
            handleExpiry()
        }
    }
    
    private func handleExpiry() {
        os_log("AutoDisconnect: Time expired", type: .info)
        
        // Save flag to shared UserDefaults
        saveExpiredFlag()
        
        // Show notification if configured
        if config?.onExpire == 1 {
            showExpiryNotification()
        }
        
        // Stop timer
        stop()
        
        // Stop tunnel
        tunnelProvider?.cancelTunnelWithError(nil)
    }
    
    // MARK: - Public Methods
    
    func updateTime(additionalSeconds: Int) -> Int {
        guard isEnabled else { return -1 }
        
        remainingSeconds += additionalSeconds
        if remainingSeconds < 0 { remainingSeconds = 0 }
        
        os_log("AutoDisconnect: Time updated, %d seconds remaining", type: .info, remainingSeconds)
        return remainingSeconds
    }
    
    func getRemainingTime() -> Int {
        return isEnabled ? remainingSeconds : -1
    }
    
    func cancel() {
        stop()
        os_log("AutoDisconnect: Cancelled by user", type: .info)
    }
    
    // MARK: - Persistence
    
    private func saveExpiredFlag() {
        let timestamp = Date().timeIntervalSince1970 * 1000 // milliseconds for cross-platform consistency
        if let groupId = config?.groupIdentifier {
            // Use shared UserDefaults for App Group
            let sharedDefaults = UserDefaults(suiteName: groupId)
            sharedDefaults?.set(timestamp, forKey: Self.timestampKey)
            sharedDefaults?.synchronize()
            os_log("AutoDisconnect: Timestamp saved to shared UserDefaults (group: %{public}@)", type: .info, groupId)
        } else {
            // Fallback to standard UserDefaults
            UserDefaults.standard.set(timestamp, forKey: Self.timestampKey)
            os_log("AutoDisconnect: Timestamp saved to standard UserDefaults", type: .info)
        }
    }
    
    static func wasAutoDisconnected(groupIdentifier: String?) -> Bool {
        if let groupId = groupIdentifier {
            return (UserDefaults(suiteName: groupId)?.double(forKey: timestampKey) ?? 0) > 0
        }
        return UserDefaults.standard.double(forKey: timestampKey) > 0
    }
    
    static func getAutoDisconnectTimestamp(groupIdentifier: String?) -> Int64 {
        if let groupId = groupIdentifier {
            return Int64(UserDefaults(suiteName: groupId)?.double(forKey: timestampKey) ?? 0)
        }
        return Int64(UserDefaults.standard.double(forKey: timestampKey))
    }
    
    static func clearExpiredFlag(groupIdentifier: String?) {
        if let groupId = groupIdentifier {
            let sharedDefaults = UserDefaults(suiteName: groupId)
            sharedDefaults?.removeObject(forKey: timestampKey)
            sharedDefaults?.synchronize()
        } else {
            UserDefaults.standard.removeObject(forKey: timestampKey)
        }
    }
    
    // MARK: - Notification
    
    private func showExpiryNotification() {
        let content = UNMutableNotificationContent()
        content.title = "VPN"
        content.body = config?.expiredMessage ?? "Free time expired - VPN disconnected"
        content.sound = .default
        
        let request = UNNotificationRequest(identifier: "auto_disconnect_expiry", content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request) { error in
            if let error = error {
                os_log("AutoDisconnect: Failed to show notification: %{public}@", type: .error, error.localizedDescription)
            }
        }
    }
}

// MARK: - Packet Tunnel Provider

/// Packet Tunnel Provider for XRay VPN
class PacketTunnelProvider: NEPacketTunnelProvider {
    
    // MARK: - Properties
    
    private let logger = CustomXRayLogger()
    private let autoDisconnectManager = AutoDisconnectManager()
    private var groupIdentifier: String?
    private static let defaultDNSServers = ["8.8.8.8", "114.114.114.114"]
    
    // MARK: - Lifecycle Methods
    
    override func startTunnel(options: [String: NSObject]?, completionHandler: @escaping (Error?) -> Void) {
        do {
            let config = try extractConfiguration()
            let port = try extractTunnelPort(from: config.xrayConfig)
            let settings = createNetworkSettings(dnsServers: config.dnsServers)
            
            // Store group identifier for auto-disconnect
            self.groupIdentifier = config.groupIdentifier
            
            // CRITICAL: Must set network settings BEFORE starting Xray and tun2socks
            // Otherwise traffic is intercepted but not properly routed
            setTunnelNetworkSettings(settings) { [weak self] error in
                guard let self = self else {
                    completionHandler(TunnelError.invalidConfiguration)
                    return
                }
                
                if let error = error {
                    os_log("Failed to set tunnel network settings: %{public}@", type: .error, error.localizedDescription)
                    completionHandler(error)
                    return
                }
                
                // Network settings established - now start Xray and tun2socks
                self.startXRay(xrayConfig: config.xrayConfig)
                
                // Small delay to ensure Xray SOCKS server is ready
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                    self.startSocks5Tunnel(serverPort: port)
                    
                    // Start auto-disconnect timer if configured
                    if let autoConfig = config.autoDisconnectConfig {
                        self.autoDisconnectManager.start(config: autoConfig, tunnelProvider: self)
                    }
                    
                    os_log("Tunnel started successfully", type: .info)
                    completionHandler(nil)
                }
            }
        } catch {
            os_log("Failed to start tunnel: %{public}@", type: .error, error.localizedDescription)
            completionHandler(error)
        }
    }
    
    override func stopTunnel(with reason: NEProviderStopReason, completionHandler: @escaping () -> Void) {
        autoDisconnectManager.stop()
        stopXRay()
        Socks5Tunnel.quit()
        completionHandler()
    }
    
    override func handleAppMessage(_ messageData: Data, completionHandler: ((Data?) -> Void)?) {
        guard let message = String(data: messageData, encoding: .utf8) else {
            completionHandler?(messageData)
            return
        }
        
        switch message {
        case "xray_traffic":
            handleTrafficRequest(completionHandler)
        case let msg where msg.hasPrefix("xray_delay"):
            handleDelayRequest(message: msg, completionHandler)
        // Auto-disconnect messages
        case "auto_disconnect_remaining":
            let remaining = autoDisconnectManager.getRemainingTime()
            completionHandler?("\(remaining)".data(using: .utf8))
        case "auto_disconnect_cancel":
            autoDisconnectManager.cancel()
            completionHandler?("ok".data(using: .utf8))
        case let msg where msg.hasPrefix("auto_disconnect_update:"):
            let secondsStr = String(msg.dropFirst("auto_disconnect_update:".count))
            if let seconds = Int(secondsStr) {
                let remaining = autoDisconnectManager.updateTime(additionalSeconds: seconds)
                completionHandler?("\(remaining)".data(using: .utf8))
            } else {
                completionHandler?("-1".data(using: .utf8))
            }
        default:
            completionHandler?(messageData)
        }
    }
    
    override func sleep(completionHandler: @escaping () -> Void) {
        completionHandler()
    }
    
    override func wake() {
        // Reserved for future implementation
    }
    
    // MARK: - Configuration Methods
    
    private func extractConfiguration() throws -> (xrayConfig: Data, dnsServers: [String], groupIdentifier: String?, autoDisconnectConfig: AutoDisconnectManager.Config?) {
        guard let protocolConfig = protocolConfiguration as? NETunnelProviderProtocol,
              let providerConfig = protocolConfig.providerConfiguration else {
            throw TunnelError.invalidConfiguration
        }
        
        guard let xrayConfig = providerConfig["xrayConfig"] as? Data else {
            throw TunnelError.missingXRayConfig
        }
        
        // Get DNS servers from configuration, or use default
        // Handle both nil and empty array cases
        var dnsServers = providerConfig["dnsServers"] as? [String] ?? []
        if dnsServers.isEmpty {
            dnsServers = Self.defaultDNSServers
            NSLog("XRay: Using default DNS servers: \(dnsServers)")
        } else {
            NSLog("XRay: Using custom DNS servers: \(dnsServers)")
        }
        
        // Get group identifier
        let groupIdentifier = providerConfig["groupIdentifier"] as? String
        
        // Get auto-disconnect config
        let autoDisconnectConfig = AutoDisconnectManager.Config.from(providerConfig: providerConfig)
        
        return (xrayConfig, dnsServers, groupIdentifier, autoDisconnectConfig)
    }
    
    private func createNetworkSettings(dnsServers: [String]) -> NEPacketTunnelNetworkSettings {
        let settings = NEPacketTunnelNetworkSettings(tunnelRemoteAddress: "254.1.1.1")
        settings.mtu = 9000
        settings.ipv4Settings = createIPv4Settings()
        settings.ipv6Settings = createIPv6Settings()
        settings.dnsSettings = NEDNSSettings(servers: dnsServers)
        return settings
    }
    
    private func createIPv4Settings() -> NEIPv4Settings {
        let settings = NEIPv4Settings(addresses: ["198.18.0.1"], subnetMasks: ["255.255.0.0"])
        settings.includedRoutes = [NEIPv4Route.default()]
        return settings
    }
    
    private func createIPv6Settings() -> NEIPv6Settings {
        let settings = NEIPv6Settings(addresses: ["fd6e:a81b:704f:1211::1"], networkPrefixLengths: [64])
        settings.includedRoutes = [NEIPv6Route.default()]
        return settings
    }
    
    // MARK: - Message Handlers
    
    private func handleTrafficRequest(_ completionHandler: ((Data?) -> Void)?) {
        let stats = "\(Socks5Tunnel.stats.up.bytes),\(Socks5Tunnel.stats.down.bytes)"
        completionHandler?(stats.data(using: .utf8))
    }
    
    private func handleDelayRequest(message: String, _ completionHandler: ((Data?) -> Void)?) {
        let url = String(message.dropFirst(10))
        var delay: Int64 = -1
        var error: NSError?
        
        XRayMeasureDelay(url, &delay, &error)
        completionHandler?("\(delay)".data(using: .utf8))
    }
    
    // MARK: - Socks5 Tunnel
    
    private func startSocks5Tunnel(serverPort port: Int) {
        let config = """
        tunnel:
          mtu: 9000
        socks5:
          port: \(port)
          address: 127.0.0.1
          udp: 'udp'
        misc:
          task-stack-size: 20480
          connect-timeout: 5000
          read-write-timeout: 60000
          log-file: stdout
          log-level: debug
          limit-nofile: 65535
        """
        
        DispatchQueue.global(qos: .userInitiated).async {
            let result = Socks5Tunnel.run(withConfig: .string(content: config))
            os_log("Socks5 tunnel exited with code: %d", type: .info, result)
        }
    }
    
    // MARK: - XRay Management
    
    private func startXRay(xrayConfig: Data) {
        XRaySetMemoryLimit()
        
        var error: NSError?
        let started = XRayStart(xrayConfig, logger, &error)
        
        if started {
            os_log("XRay started successfully", type: .info)
        } else if let error = error {
            os_log("Failed to start XRay: %{public}@", type: .error, error.localizedDescription)
        }
    }
    
    private func stopXRay() {
        XRayStop()
        os_log("XRay stopped (version: %{public}@)", type: .info, XRayGetVersion())
    }
    
    private func extractTunnelPort(from jsonData: Data) throws -> Int {
        do {
            guard let config = try JSONSerialization.jsonObject(with: jsonData) as? [String: Any],
                  let inbounds = config["inbounds"] as? [[String: Any]] else {
                throw TunnelError.invalidConfiguration
            }
            
            for inbound in inbounds {
                if let protocolType = inbound["protocol"] as? String,
                   let port = inbound["port"] as? Int,
                   ["socks", "http"].contains(protocolType) {
                    return port
                }
            }
        } catch {
            os_log("Failed to parse XRay config: %{public}@", type: .error, error.localizedDescription)
            throw TunnelError.invalidConfiguration
        }
        
        throw TunnelError.invalidPort
    }
}

// MARK: - Custom Logger

final class CustomXRayLogger: NSObject, XRayLoggerProtocol {
    func logInput(_ message: String?) {
        guard let message = message else { return }
        os_log("XRay: %{public}@", type: .debug, message)
    }
}

