package com.wisecodex.flutter_v2ray.xray.service

import android.content.Intent
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.wisecodex.flutter_v2ray.xray.core.XrayCoreManager
import com.wisecodex.flutter_v2ray.xray.dto.XrayConfig
import com.wisecodex.flutter_v2ray.xray.utils.AppConfigs
import java.io.File

/**
 * Android VPN Service implementation for XRay VPN.
 *
 * Responsibilities:
 * - Establishing the VPN interface (TUN device) using Android's VpnService API
 * - Managing the tun2socks process for traffic routing
 * - Handling VPN connection lifecycle (start, stop, cleanup)
 * - Supporting "Proxy Only" mode without VPN interface
 *
 * ## Technical Implementation
 * To support Android 15 (16KB page size) and prevent "bad file descriptor" errors,
 * the TUN file descriptor is passed to tun2socks via Unix Domain Socket instead of
 * command line arguments which fail across process boundaries.
 */
class XrayVPNService : VpnService() {

    // MARK: - Properties

    private var vpnInterface: ParcelFileDescriptor? = null
    private var tun2socksProcess: Process? = null
    private var isRunning = false

    // MARK: - Lifecycle Methods

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent ?: return START_NOT_STICKY.also { stopSelf() }

        startForegroundService()

        val command = extractCommand(intent)
        when (command) {
            AppConfigs.V2RAY_SERVICE_COMMANDS.START_SERVICE -> handleStartCommand(intent)
            AppConfigs.V2RAY_SERVICE_COMMANDS.STOP_SERVICE -> stopAll()
            else -> Log.w(TAG, "Unknown command received")
        }

        return START_STICKY
    }

    override fun onDestroy() {
        stopAll()
        super.onDestroy()
    }

    // MARK: - Service Configuration

    private fun startForegroundService() {
        createNotificationChannel()
        val notification = createNotification("VPN Service Running")
        
        try {
            val foregroundServiceType = if (Build.VERSION.SDK_INT >= 34) {
                FOREGROUND_SERVICE_TYPE_SPECIAL_USE // 32
            } else null
            
            if (foregroundServiceType != null) {
                startForeground(NOTIFICATION_ID, notification, foregroundServiceType)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
        }
    }

    private fun extractCommand(intent: Intent): AppConfigs.V2RAY_SERVICE_COMMANDS? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getSerializableExtra("COMMAND", AppConfigs.V2RAY_SERVICE_COMMANDS::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra("COMMAND") as? AppConfigs.V2RAY_SERVICE_COMMANDS
        }
    }

    private fun handleStartCommand(intent: Intent) {
        val config = extractConfig(intent) ?: return stopSelf()
        val proxyOnly = intent.getBooleanExtra("PROXY_ONLY", false)

        cleanup() // Ensure clean state

        if (XrayCoreManager.startCore(this, config)) {
            if (proxyOnly) {
                isRunning = true
                Log.d(TAG, "Started in PROXY_ONLY mode")
            } else {
                setupVpn(config)
            }
        } else {
            Log.e(TAG, "Failed to start XRay Core")
            stopSelf()
        }
    }

    private fun extractConfig(intent: Intent): XrayConfig? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getSerializableExtra("V2RAY_CONFIG", XrayConfig::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra("V2RAY_CONFIG") as? XrayConfig
        }
    }

    // MARK: - VPN Setup

    /**
     * Establishes the VPN interface (TUN device) and starts tun2socks process.
     */
    private fun setupVpn(config: XrayConfig) {
        try {
            closeExistingInterface()
            val builder = configureVpnBuilder(config)
            vpnInterface = builder.establish()

            if (vpnInterface == null) {
                handleVpnEstablishmentFailure()
                return
            }

            isRunning = true
            runTun2socks(config)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup VPN", e)
            stopAll()
        }
    }

    private fun closeExistingInterface() {
        vpnInterface?.runCatching {
            close()
            Thread.sleep(INTERFACE_CLOSE_DELAY_MS) // Allow system cleanup
        }?.onFailure {
            Log.e(TAG, "Error closing old VPN interface", it)
        }
        vpnInterface = null
    }

    private fun configureVpnBuilder(config: XrayConfig): Builder {
        return Builder().apply {
            setSession(config.REMARK)
            setMtu(VPN_MTU)
            addAddress(VPN_ADDRESS, VPN_PREFIX_LENGTH)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setMetered(false)
            }

            // Exclude this app from VPN to prevent loops
            runCatching {
                addDisallowedApplication(packageName)
            }.onFailure {
                Log.e(TAG, "Failed to exclude app from VPN", it)
            }

            // Apply blocked apps (Per-App VPN)
            config.BLOCKED_APPS.forEach { blockedApp ->
                runCatching {
                    addDisallowedApplication(blockedApp)
                    Log.d(TAG, "Excluded app from VPN: $blockedApp")
                }.onFailure {
                    Log.w(TAG, "Failed to exclude app '$blockedApp' from VPN", it)
                }
            }

            configureRoutes(this, config)
            configureDns(this, config)
        }
    }

    private fun configureRoutes(builder: Builder, config: XrayConfig) {

        val serverIp = config.CONNECTED_V2RAY_SERVER_ADDRESS
        if (serverIp.isNotEmpty() && !serverIp.contains(":")) {
            runCatching {
                Log.d(TAG, "Excluding server IP: $serverIp")
                excludeIp(serverIp).forEach { route ->
                    val (address, prefix) = route.split("/")
                    builder.addRoute(address, prefix.toInt())
                }
            }.onFailure {
                Log.e(TAG, "Failed to exclude server IP, using default route", it)
                builder.addRoute(DEFAULT_ROUTE_ADDRESS, DEFAULT_ROUTE_PREFIX)
            }
        } else {
            builder.addRoute(DEFAULT_ROUTE_ADDRESS, DEFAULT_ROUTE_PREFIX)
        }
    }

    private fun configureDns(builder: Builder, config: XrayConfig) {
        val dnsServers = config.DNS_SERVERS ?: DEFAULT_DNS_SERVERS
        
        runCatching {
            dnsServers.forEach { builder.addDnsServer(it) }
        }.onFailure {
            Log.w(TAG, "Failed to configure DNS, using fallback", it)
            DEFAULT_DNS_FALLBACK.forEach { builder.addDnsServer(it) }
        }
    }

    private fun handleVpnEstablishmentFailure() {
        Log.e(TAG, "Failed to establish VPN interface. " +
                "This can happen if another VPN is active or permission was revoked.")

        sendBroadcast(Intent(AppConfigs.V2RAY_CONNECTION_INFO).apply {
            putExtra("STATE", AppConfigs.V2RAY_STATES.V2RAY_DISCONNECTED)
        })

        XrayCoreManager.stopCore(this)
        stopSelf()
    }

    // MARK: - Tun2socks Management

    /**
     * Starts the tun2socks process and initiates file descriptor transfer.
     */
    private fun runTun2socks(config: XrayConfig) {
        val tun2socksPath = File(applicationInfo.nativeLibraryDir, "libtun2socks.so").absolutePath
        val sockPath = File(filesDir, SOCKET_PATH).absolutePath

        val command = buildTun2socksCommand(tun2socksPath, sockPath, config)
        Log.d(TAG, "Starting tun2socks: ${command.joinToString(" ")}")

        runCatching {
            tun2socksProcess = ProcessBuilder(command).apply {
                redirectErrorStream(true)
                directory(filesDir)
            }.start()

            monitorTun2socksProcess(config)
            sendFd()
        }.onFailure {
            Log.e(TAG, "Failed to start tun2socks", it)
            stopAll()
        }
    }

    private fun buildTun2socksCommand(
        executablePath: String,
        socketPath: String,
        config: XrayConfig
    ): List<String> {
        return listOf(
            executablePath,
            "-sock-path", socketPath,
            "-proxy", "socks5://127.0.0.1:${config.LOCAL_SOCKS5_PORT}",
            "-mtu", TUN2SOCKS_MTU.toString(),
            "-loglevel", TUN2SOCKS_LOG_LEVEL
        )
    }

    private fun monitorTun2socksProcess(config: XrayConfig) {
        Thread {
            runCatching {
                tun2socksProcess?.inputStream?.bufferedReader()?.use { reader ->
                    reader.forEachLine { line ->
                        Log.d(TAG, "tun2socks: $line")
                    }
                }

                val exitCode = tun2socksProcess?.waitFor()
                if (isRunning) {
                    Log.e(TAG, "tun2socks exited unexpectedly (code: $exitCode), restarting...")
                    Thread.sleep(TUN2SOCKS_START_DELAY_MS)
                    runTun2socks(config)
                }
            }.onFailure { exception ->
                when (exception) {
                    is java.io.InterruptedIOException, is InterruptedException -> {
                        // Expected when stopping
                        Log.d(TAG, "tun2socks monitor thread interrupted")
                    }
                    else -> Log.e(TAG, "Error in tun2socks monitor", exception)
                }
            }
        }.start()
    }

    // MARK: - File Descriptor Transfer

    /**
     * Sends the TUN interface file descriptor to the running tun2socks process.
     *
     * Uses a Unix Domain Socket to pass the FD across process boundaries,
     * which is required because ProcessBuilder cannot inherit FDs on Android.
     */
    private fun sendFd() {
        val fd = vpnInterface?.fileDescriptor ?: return
        val sockPath = File(filesDir, SOCKET_PATH).absolutePath

        Thread {
            repeat(FD_TRANSFER_MAX_RETRIES) { attempt ->
                runCatching {
                    Thread.sleep(FD_TRANSFER_RETRY_DELAY_MS)
                    
                    LocalSocket().use { socket ->
                        socket.connect(LocalSocketAddress(sockPath, LocalSocketAddress.Namespace.FILESYSTEM))
                        socket.setFileDescriptorsForSend(arrayOf(fd))
                        socket.outputStream.write(FD_TRANSFER_MAGIC_BYTE)
                        socket.setFileDescriptorsForSend(null)
                        socket.shutdownOutput()
                    }
                    
                    Log.d(TAG, "Successfully transferred TUN FD to tun2socks")
                    return@Thread
                }.onFailure {
                    if (attempt == FD_TRANSFER_MAX_RETRIES - 1) {
                        Log.e(TAG, "Failed to send FD after $FD_TRANSFER_MAX_RETRIES attempts", it)
                    }
                }
            }
        }.start()
    }

    // MARK: - Cleanup Methods

    /**
     * Cleans up VPN resources without stopping the service.
     * Used when restarting or switching configurations.
     */
    private fun cleanup() {
        isRunning = false
        tun2socksProcess?.destroy()
        tun2socksProcess = null
        vpnInterface?.runCatching { close() }
        vpnInterface = null
    }

    /**
     * Stops all components: tun2socks, VPN interface, and XRay Core.
     */
    private fun stopAll() {
        cleanup()
        XrayCoreManager.stopCore(this)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        
        stopSelf()
    }

    /**
     * Calculates routes to exclude a specific IP address from the VPN.
     * This is done by splitting the 0.0.0.0/0 route into smaller subnets that cover everything EXCEPT the target IP.
     */
    private fun excludeIp(ip: String): List<String> {
        val parts = ip.split(".").map { it.toInt() }
        val ipLong = (parts[0].toLong() shl 24) + (parts[1].toLong() shl 16) + (parts[2].toLong() shl 8) + parts[3].toLong()
        
        val routes = ArrayList<String>()
        var start = 0L
        var end = 4294967295L // 255.255.255.255
        
        fun addRoutesExcluding(target: Long, current: Long, prefix: Int) {
            if (prefix >= 32) return
            
            val size = 1L shl (32 - prefix)
            val nextPrefix = prefix + 1
            val left = current
            val right = current + (1L shl (32 - nextPrefix))
            
            // Check if target is in left half
            if (target >= left && target < left + (1L shl (32 - nextPrefix))) {
                // Target is in left half, so add right half fully
                routes.add(longToIp(right) + "/$nextPrefix")
                addRoutesExcluding(target, left, nextPrefix)
            } else {
                // Target is in right half, so add left half fully
                routes.add(longToIp(left) + "/$nextPrefix")
                addRoutesExcluding(target, right, nextPrefix)
            }
        }
        
        addRoutesExcluding(ipLong, 0L, 0)
        return routes
    }

    private fun longToIp(ip: Long): String {
        return "${(ip shr 24) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 8) and 0xFF}.${ip and 0xFF}"
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "vpn_service_channel"
            val channelName = "VPN Service"
            val channel = android.app.NotificationChannel(
                channelId,
                channelName,
                android.app.NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(android.app.NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(content: String): android.app.Notification {
        val channelId = "vpn_service_channel"
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(this)
        }
        
        // Use a default icon if not set
        var icon = android.R.drawable.ic_dialog_info
        
        return builder
            .setContentTitle("VPN Service")
            .setContentText(content)
            .setSmallIcon(icon)
            .build()
    }

    // MARK: - Companion Object

    companion object {
        private const val TAG = "XrayVPNService"
        
        // Notification
        private const val NOTIFICATION_ID = 1
        private const val FOREGROUND_SERVICE_TYPE_SPECIAL_USE = 32
        
        // VPN Configuration
        private const val VPN_MTU = 1500
        private const val VPN_ADDRESS = "26.26.26.1"
        private const val VPN_PREFIX_LENGTH = 30
        private const val DEFAULT_ROUTE_ADDRESS = "0.0.0.0"
        private const val DEFAULT_ROUTE_PREFIX = 0
        
        // DNS Configuration
        private val DEFAULT_DNS_SERVERS = arrayListOf("8.8.8.8", "114.114.114.114")
        private val DEFAULT_DNS_FALLBACK = arrayListOf("8.8.8.8", "1.1.1.1")
        
        // Tun2socks Configuration
        private const val SOCKET_PATH = "sock_path"
        private const val TUN2SOCKS_MTU = 1500
        private const val TUN2SOCKS_LOG_LEVEL = "debug"
        
        // File Descriptor Transfer
        private const val FD_TRANSFER_MAX_RETRIES = 10
        private const val FD_TRANSFER_RETRY_DELAY_MS = 500L
        private const val FD_TRANSFER_MAGIC_BYTE = 32
        
        // Timing
        private const val INTERFACE_CLOSE_DELAY_MS = 200L
        private const val TUN2SOCKS_START_DELAY_MS = 1000L
    }
}

