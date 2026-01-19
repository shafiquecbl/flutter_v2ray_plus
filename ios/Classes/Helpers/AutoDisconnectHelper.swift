//
//  AutoDisconnectHelper.swift
//  flutter_v2ray
//
//  Handles auto-disconnect flag checking, method handlers, and coordination for the Flutter plugin.
//  Works with the Network Extension's AutoDisconnectManager via shared UserDefaults and IPC.
//

import Foundation
import Flutter

/// Handles auto-disconnect operations in the plugin layer.
/// The actual timer runs in the Network Extension's AutoDisconnectManager.
final class AutoDisconnectHelper {
    
    // MARK: - Singleton
    
    static let shared = AutoDisconnectHelper()
    private init() {}
    
    // MARK: - Properties
    
    /// Group identifier for shared UserDefaults (App Group)
    private var groupIdentifier: String?
    
    /// App name for notifications
    private var appName: String = "VPN"
    
    /// UserDefaults key for auto-disconnect expired flag
    private static let expiredFlagKey = "flutter_v2ray_auto_disconnect_expired"
    
    // MARK: - Configuration
    
    /// Configure the helper with group identifier and notification settings
    /// - Parameters:
    ///   - groupIdentifier: App Group identifier for shared UserDefaults
    ///   - appName: App name to show in notifications
    ///   - expiredMessage: Message to show when VPN auto-disconnects (optional)
    func configure(groupIdentifier: String?, appName: String, expiredMessage: String? = nil) {
        self.groupIdentifier = groupIdentifier
        self.appName = appName
        
        // Configure notification manager if custom message provided
        if let message = expiredMessage {
            AutoDisconnectNotificationManager.shared.configure(
                appName: appName,
                expiredMessage: message
            )
        }
    }
    
    // MARK: - Flag Management
    
    /// Check if VPN was auto-disconnected while app was killed/backgrounded
    /// - Returns: true if auto-disconnect expired, false otherwise
    func wasAutoDisconnected() -> Bool {
        return getUserDefaults().bool(forKey: Self.expiredFlagKey)
    }
    
    /// Clear the auto-disconnect expired flag
    /// Should be called after the app has handled the expired state
    func clearExpiredFlag() {
        let defaults = getUserDefaults()
        defaults.set(false, forKey: Self.expiredFlagKey)
        defaults.synchronize()
    }
    
    // MARK: - Status Handling
    
    /// Check VPN disconnect status and handle auto-disconnect case
    /// - Parameter currentStatus: The current VPN status string
    /// - Returns: Adjusted status ("AUTO_DISCONNECTED" if was auto-disconnected, otherwise original status)
    func checkAndHandleDisconnect(currentStatus: String) -> String {
        guard currentStatus == "DISCONNECTED" else {
            return currentStatus
        }
        
        if wasAutoDisconnected() {
            // Show notification
            AutoDisconnectNotificationManager.shared.showExpiryNotification()
            return "AUTO_DISCONNECTED"
        }
        
        return currentStatus
    }
    
    // MARK: - Method Handlers (forwarded to Network Extension)
    
    /// Update auto-disconnect time by adding seconds
    /// - Parameters:
    ///   - call: Flutter method call with "additional_seconds" argument
    ///   - packetTunnelManager: Manager to send message to extension
    ///   - result: Flutter result callback
    func updateTime(call: FlutterMethodCall, packetTunnelManager: PacketTunnelManager?, result: @escaping FlutterResult) {
        guard let arguments = call.arguments as? [String: Any],
              let additionalSeconds = arguments["additional_seconds"] as? Int else {
            result(-1)
            return
        }
        
        Task {
            do {
                let message = "auto_disconnect_update:\(additionalSeconds)"
                let response = try await packetTunnelManager?.sendProviderMessage(data: message.data(using: .utf8)!)
                if let response = response, let remaining = Int(String(decoding: response, as: UTF8.self)) {
                    result(remaining)
                } else {
                    result(-1)
                }
            } catch {
                result(-1)
            }
        }
    }
    
    /// Get remaining auto-disconnect time
    /// - Parameters:
    ///   - packetTunnelManager: Manager to send message to extension
    ///   - result: Flutter result callback
    func getRemainingTime(packetTunnelManager: PacketTunnelManager?, result: @escaping FlutterResult) {
        Task {
            do {
                let response = try await packetTunnelManager?.sendProviderMessage(data: "auto_disconnect_remaining".data(using: .utf8)!)
                if let response = response, let remaining = Int(String(decoding: response, as: UTF8.self)) {
                    result(remaining)
                } else {
                    result(-1)
                }
            } catch {
                result(-1)
            }
        }
    }
    
    /// Cancel auto-disconnect timer
    /// - Parameters:
    ///   - packetTunnelManager: Manager to send message to extension
    ///   - result: Flutter result callback
    func cancel(packetTunnelManager: PacketTunnelManager?, result: @escaping FlutterResult) {
        Task {
            do {
                let _ = try await packetTunnelManager?.sendProviderMessage(data: "auto_disconnect_cancel".data(using: .utf8)!)
                result(nil)
            } catch {
                result(nil) // Still return success even if message fails
            }
        }
    }
    
    /// Handle wasAutoDisconnected method call
    /// - Parameter result: Flutter result callback
    func handleWasAutoDisconnected(result: @escaping FlutterResult) {
        result(wasAutoDisconnected())
    }
    
    /// Handle clearAutoDisconnectFlag method call
    /// - Parameter result: Flutter result callback
    func handleClearFlag(result: @escaping FlutterResult) {
        clearExpiredFlag()
        result(nil)
    }
    
    // MARK: - Private Methods
    
    private func getUserDefaults() -> UserDefaults {
        if let groupId = groupIdentifier, !groupId.isEmpty {
            return UserDefaults(suiteName: groupId) ?? UserDefaults.standard
        }
        return UserDefaults.standard
    }
}
