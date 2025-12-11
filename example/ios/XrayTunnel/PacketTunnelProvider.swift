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

/// Custom error types for better error handling
enum TunnelError: Error {
    case invalidConfiguration
    case missingXRayConfig
    case invalidPort
}

/// Packet Tunnel Provider for XRay VPN
class PacketTunnelProvider: NEPacketTunnelProvider {
    
    // MARK: - Properties
    
    private let logger = CustomXRayLogger()
    private static let defaultDNSServers = ["8.8.8.8", "114.114.114.114"]
    
    // MARK: - Lifecycle Methods
    
    override func startTunnel(options: [String: NSObject]?, completionHandler: @escaping (Error?) -> Void) {
        do {
            let config = try extractConfiguration()
            let port = try extractTunnelPort(from: config.xrayConfig)
            let settings = createNetworkSettings(dnsServers: config.dnsServers)
            
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
    
    private func extractConfiguration() throws -> (xrayConfig: Data, dnsServers: [String]) {
        guard let protocolConfig = protocolConfiguration as? NETunnelProviderProtocol,
              let providerConfig = protocolConfig.providerConfiguration else {
            throw TunnelError.invalidConfiguration
        }
        
        guard let xrayConfig = providerConfig["xrayConfig"] as? Data else {
            throw TunnelError.missingXRayConfig
        }
        
        let dnsServers = providerConfig["dnsServers"] as? [String] ?? Self.defaultDNSServers
        return (xrayConfig, dnsServers)
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
