//
//  AutoDisconnectNotificationManager.swift
//  flutter_v2ray
//
//  Manages auto-disconnect notification functionality for the Flutter plugin.
//

import Foundation
import UserNotifications
import os.log

/// Manages auto-disconnect notifications in the Flutter plugin layer.
/// This handles notification display when VPN is auto-disconnected.
final class AutoDisconnectNotificationManager {
    
    // MARK: - Singleton
    
    static let shared = AutoDisconnectNotificationManager()
    private init() {}
    
    // MARK: - Properties
    
    /// The app name to show in notification title
    var appName: String = "VPN"
    
    /// The message to show when auto-disconnect expires
    var expiredMessage: String = "Free time expired - VPN disconnected"
    
    // MARK: - Notification Identifiers
    
    private enum NotificationID {
        static let autoDisconnectExpiry = "auto_disconnect_expiry"
    }
    
    // MARK: - Public Methods
    
    /// Configure the notification manager with custom settings
    /// - Parameters:
    ///   - appName: App name to show in notification title
    ///   - expiredMessage: Message to show when VPN auto-disconnects
    func configure(appName: String, expiredMessage: String) {
        self.appName = appName
        self.expiredMessage = expiredMessage
    }
    
    /// Show auto-disconnect expiry notification
    /// Requests permission if not granted yet, then shows notification
    func showExpiryNotification() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { [weak self] granted, error in
            guard let self = self else { return }
            
            if let error = error {
                os_log("Notification permission error: %{public}@", type: .error, error.localizedDescription)
                return
            }
            
            if granted {
                self.sendNotification()
            } else {
                os_log("Notification permission not granted", type: .info)
            }
        }
    }
    
    /// Request notification permission (call early in app lifecycle)
    func requestPermission(completion: ((Bool) -> Void)? = nil) {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { granted, error in
            if let error = error {
                os_log("Notification permission error: %{public}@", type: .error, error.localizedDescription)
            }
            completion?(granted)
        }
    }
    
    // MARK: - Private Methods
    
    private func sendNotification() {
        let content = UNMutableNotificationContent()
        content.title = appName
        content.body = expiredMessage
        content.sound = .default
        
        let request = UNNotificationRequest(
            identifier: NotificationID.autoDisconnectExpiry,
            content: content,
            trigger: nil
        )
        
        UNUserNotificationCenter.current().add(request) { error in
            if let error = error {
                os_log("Error showing auto-disconnect notification: %{public}@", type: .error, error.localizedDescription)
            } else {
                os_log("Auto-disconnect notification sent successfully", type: .info)
            }
        }
    }
}
