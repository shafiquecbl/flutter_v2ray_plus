package com.wisecodex.flutter_v2ray.xray.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.CountDownTimer
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.wisecodex.flutter_v2ray.xray.dto.XrayConfig
import com.wisecodex.flutter_v2ray.xray.service.XrayVPNService
import com.wisecodex.flutter_v2ray.xray.utils.AppConfigs
import com.wisecodex.flutter_v2ray.xray.utils.Utilities
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Manages the Xray Core process lifecycle and configuration.
 *
 * Responsibilities:
 * - Generating and injecting Xray configuration (SOCKS, HTTP, API inbounds)
 * - Starting and monitoring the Xray process
 * - Collecting traffic statistics via Xray API
 * - Managing foreground notification for the service
 * - Measuring server delay/latency
 *
 * This is a singleton object that maintains the Xray process state across
 * the application lifecycle.
 */
object XrayCoreManager {

    // MARK: - Properties

    private var xrayProcess: Process? = null
    private var countDownTimer: CountDownTimer? = null
    private var connectionDurationSeconds = 0
    
    // Auto-disconnect state
    private var remainingAutoDisconnectSeconds = -1
    private var autoDisconnectEnabled = false
    private var serviceContext: Service? = null

    // MARK: - Core Lifecycle Management

    /**
     * Starts the Xray Core process with the provided configuration.
     *
     * This sets the state to CONNECTING and starts the Xray proxy process.
     * The state will be updated to CONNECTED when onVpnEstablished() is called after
     * the VPN interface is successfully established.
     *
     * @param context The service context for file access and notifications
     * @param config The Xray configuration object
     * @return true if started successfully, false otherwise
     */
    fun startCore(context: Service, config: XrayConfig): Boolean {
        // Set state to CONNECTING immediately - this is critical for VPN activation
        AppConfigs.V2RAY_STATE = AppConfigs.V2RAY_STATES.V2RAY_CONNECTING
        AppConfigs.V2RAY_CONFIG = config

        return runCatching {
            val configFile = prepareConfigurationFile(context, config)
            val xrayExecutable = findXrayExecutable(context) ?: return false
            
            Utilities.copyAssets(context)
            startXrayProcess(context, configFile, xrayExecutable, config)
            Log.d(TAG, "Xray Core process started successfully")
            true
        }.onFailure {
            Log.e(TAG, "Failed to start Xray Core", it)
        }.getOrDefault(false)
    }

    /**
     * Called by XrayVPNService after VPN interface is successfully established.
     * This is when we mark the connection as active and start UI updates.
     *
     * @param context The service context
     */
    fun onVpnEstablished(context: Service) {
        Log.d(TAG, "VPN interface established, transitioning to CONNECTED state")
        
        AppConfigs.V2RAY_STATE = AppConfigs.V2RAY_STATES.V2RAY_CONNECTED
        
        val config = AppConfigs.V2RAY_CONFIG
        if (config != null) {
            showNotification(context, config)
            startTimer(context)
        } else {
            Log.e(TAG, "Config is null in onVpnEstablished")
        }
    }

    /**
     * Stops the Xray Core process and cleans up resources.
     */
    fun stopCore(context: Service) {
        xrayProcess?.destroy()
        xrayProcess = null

        AppConfigs.V2RAY_STATE = AppConfigs.V2RAY_STATES.V2RAY_DISCONNECTED
        stopTimer()

        (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
            ?.cancel(NOTIFICATION_ID)

        sendDisconnectedBroadcast(context)
    }

    /**
     * Checks if Xray Core is currently running.
     */
    fun isXrayRunning(): Boolean {
        return AppConfigs.V2RAY_STATE == AppConfigs.V2RAY_STATES.V2RAY_CONNECTED ||
               AppConfigs.V2RAY_STATE == AppConfigs.V2RAY_STATES.V2RAY_CONNECTING
    }

    // MARK: - Configuration Management

    private fun prepareConfigurationFile(context: Context, config: XrayConfig): File {
        val configJson = JSONObject(config.V2RAY_FULL_JSON_CONFIG)

        injectApiConfiguration(configJson)
        injectStatsConfiguration(configJson)
        injectPolicyConfiguration(configJson)
        injectInbounds(configJson, config)
        injectRoutingRules(configJson)

        return File(context.filesDir, CONFIG_FILE_NAME).apply {
            writeText(configJson.toString())
        }
    }

    private fun injectApiConfiguration(config: JSONObject) {
        config.put("api", JSONObject().apply {
            put("tag", "api")
            put("services", JSONArray().put("StatsService"))
        })
    }

    private fun injectStatsConfiguration(config: JSONObject) {
        config.put("stats", JSONObject())
    }

    private fun injectPolicyConfiguration(config: JSONObject) {
        config.put("policy", JSONObject().apply {
            put("levels", JSONObject().apply {
                put("8", JSONObject().apply {
                    put("statsUserUplink", true)
                    put("statsUserDownlink", true)
                })
            })
            put("system", JSONObject().apply {
                put("statsInboundUplink", true)
                put("statsInboundDownlink", true)
                put("statsOutboundUplink", true)
                put("statsOutboundDownlink", true)
            })
        })
    }

    private fun injectInbounds(config: JSONObject, xrayConfig: XrayConfig) {
        val inbounds = config.optJSONArray("inbounds") ?: JSONArray()
        
        val existingProtocols = (0 until inbounds.length())
            .map { inbounds.getJSONObject(it).optString("protocol") }
            .toSet()

        if ("socks" !in existingProtocols) {
            inbounds.put(createSocksInbound(xrayConfig.LOCAL_SOCKS5_PORT))
            Log.d(TAG, "Injected SOCKS inbound on port ${xrayConfig.LOCAL_SOCKS5_PORT}")
        }

        if ("http" !in existingProtocols) {
            inbounds.put(createHttpInbound(xrayConfig.LOCAL_HTTP_PORT))
            Log.d(TAG, "Injected HTTP inbound on port ${xrayConfig.LOCAL_HTTP_PORT}")
        }

        inbounds.put(createApiInbound(xrayConfig.LOCAL_API_PORT))
        config.put("inbounds", inbounds)
    }

    private fun createSocksInbound(port: Int) = JSONObject().apply {
        put("tag", "socks")
        put("port", port)
        put("listen", LOCALHOST)
        put("protocol", "socks")
        put("settings", JSONObject().apply {
            put("auth", "noauth")
            put("udp", true)
        })
        put("sniffing", JSONObject().apply {
            put("enabled", true)
            put("destOverride", JSONArray().put("http").put("tls"))
        })
    }

    private fun createHttpInbound(port: Int) = JSONObject().apply {
        put("tag", "http")
        put("port", port)
        put("listen", LOCALHOST)
        put("protocol", "http")
    }

    private fun createApiInbound(port: Int) = JSONObject().apply {
        put("tag", "api")
        put("port", port)
        put("listen", LOCALHOST)
        put("protocol", "dokodemo-door")
        put("settings", JSONObject().put("address", LOCALHOST))
    }

    private fun injectRoutingRules(config: JSONObject) {
        val routing = config.optJSONObject("routing") ?: JSONObject()
        val rules = routing.optJSONArray("rules") ?: JSONArray()
        
        rules.put(JSONObject().apply {
            put("type", "field")
            put("inboundTag", JSONArray().put("api"))
            put("outboundTag", "api")
        })
        
        routing.put("rules", rules)
        config.put("routing", routing)
    }

    // MARK: - Process Management

    private fun findXrayExecutable(context: Context): File? {
        return File(context.applicationInfo.nativeLibraryDir, XRAY_EXECUTABLE_NAME).takeIf { it.exists() }
            ?.also { Log.d(TAG, "Found Xray executable: ${it.absolutePath}") }
            ?: run {
                Log.e(TAG, "Xray executable not found")
                null
            }
    }

    // MARK: - Process Management

    private fun startXrayProcess(
        context: Service,
        configFile: File,
        executable: File,
        config: XrayConfig
    ) {
        val command = listOf(executable.absolutePath, "-config", configFile.absolutePath)
        
        xrayProcess = ProcessBuilder(command).apply {
            directory(context.filesDir)
            environment()[ENV_XRAY_LOCATION_ASSET] = Utilities.getUserAssetsPath(context)
        }.start()

        monitorXrayProcess(context)
        Log.d(TAG, "Xray process started, waiting for VPN interface confirmation")
    }

    private fun monitorXrayProcess(context: Service) {
        Thread {
            runCatching {
                xrayProcess?.inputStream?.bufferedReader()?.use { reader ->
                    reader.forEachLine { line ->
                        Log.d(TAG, "xray: $line")
                    }
                }

                val exitCode = xrayProcess?.waitFor()
                Log.e(TAG, "Xray process exited with code $exitCode")
                
                if (AppConfigs.V2RAY_STATE == AppConfigs.V2RAY_STATES.V2RAY_CONNECTED) {
                    stopCore(context)
                }
            }.onFailure { exception ->
                when (exception) {
                    is java.io.InterruptedIOException, is InterruptedException -> {
                        Log.d(TAG, "Xray monitor thread interrupted")
                    }
                    else -> Log.e(TAG, "Error in Xray monitor", exception)
                }
            }
        }.start()
    }

    // MARK: - Traffic Statistics & Timer

    private fun startTimer(context: Context) {
        countDownTimer?.cancel()
        connectionDurationSeconds = 0
        serviceContext = context as? Service
        
        // Initialize auto-disconnect from config
        val config = AppConfigs.V2RAY_CONFIG
        if (config != null && config.AUTO_DISCONNECT_DURATION > 0) {
            autoDisconnectEnabled = true
            remainingAutoDisconnectSeconds = config.AUTO_DISCONNECT_DURATION
            Log.d(TAG, "Auto-disconnect enabled: ${remainingAutoDisconnectSeconds}s")
        } else {
            autoDisconnectEnabled = false
            remainingAutoDisconnectSeconds = -1
        }
        
        countDownTimer = object : CountDownTimer(Long.MAX_VALUE, TIMER_INTERVAL_MS) {
            override fun onTick(millisUntilFinished: Long) {
                connectionDurationSeconds++
                
                // Handle auto-disconnect countdown
                if (autoDisconnectEnabled && remainingAutoDisconnectSeconds > 0) {
                    remainingAutoDisconnectSeconds--
                    
                    // Update notification with remaining time
                    val config = AppConfigs.V2RAY_CONFIG
                    if (config != null && config.AUTO_DISCONNECT_SHOW_IN_NOTIFICATION) {
                        updateNotificationWithRemainingTime(context, config)
                    }
                    
                    // Check for expiry
                    if (remainingAutoDisconnectSeconds <= 0) {
                        handleAutoDisconnectExpiry(context)
                        return
                    }
                }
                
                broadcastConnectionStatus(context)
            }

            override fun onFinish() {}
        }.start()
    }

    private fun stopTimer() {
        countDownTimer?.cancel()
        countDownTimer = null
        connectionDurationSeconds = 0
        remainingAutoDisconnectSeconds = -1
        autoDisconnectEnabled = false
        serviceContext = null
    }

    private fun broadcastConnectionStatus(context: Context) {
        val traffic = getV2rayTraffic(context)
        
        Intent(AppConfigs.V2RAY_CONNECTION_INFO).apply {
            putExtra("STATE", AppConfigs.V2RAY_STATE)
            putExtra("DURATION", connectionDurationSeconds.toString())
            putExtra("UPLOAD_SPEED", traffic[0])
            putExtra("DOWNLOAD_SPEED", traffic[1])
            putExtra("UPLOAD_TRAFFIC", traffic[2])
            putExtra("DOWNLOAD_TRAFFIC", traffic[3])
            // Add remaining auto-disconnect time
            if (autoDisconnectEnabled && remainingAutoDisconnectSeconds >= 0) {
                putExtra("REMAINING_TIME", remainingAutoDisconnectSeconds.toString())
            }
        }.also { context.sendBroadcast(it) }
    }

    /**
     * Queries Xray API for traffic statistics.
     *
     * @return Array of [uploadSpeed, downloadSpeed, totalUpload, totalDownload]
     */
    fun getV2rayTraffic(context: Context): LongArray {
        if (!isXrayRunning()) return ZERO_TRAFFIC

        val apiPort = AppConfigs.V2RAY_CONFIG?.LOCAL_API_PORT ?: DEFAULT_API_PORT
        val xrayPath = File(context.applicationInfo.nativeLibraryDir, XRAY_EXECUTABLE_NAME).absolutePath
        
        return runCatching {
            val command = listOf(
                xrayPath, "api", "statsquery",
                "--server=$LOCALHOST:$apiPort",
                "--pattern", ""
            )
            
            val process = ProcessBuilder(command).start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()

            parseTrafficStats(output)
        }.onFailure {
            Log.e(TAG, "Failed to query traffic stats", it)
        }.getOrDefault(ZERO_TRAFFIC)
    }

    private fun parseTrafficStats(jsonOutput: String): LongArray {
        if (jsonOutput.isEmpty()) return ZERO_TRAFFIC

        return runCatching {
            val json = JSONObject(jsonOutput)
            val stats = json.optJSONArray("stat") ?: return ZERO_TRAFFIC

            var uplink = 0L
            var downlink = 0L

            for (i in 0 until stats.length()) {
                val stat = stats.getJSONObject(i)
                val name = stat.optString("name")
                val value = stat.optLong("value")

                when {
                    name.contains("uplink") -> uplink += value
                    name.contains("downlink") -> downlink += value
                }
            }
            
            longArrayOf(uplink, downlink, uplink, downlink)
        }.getOrDefault(ZERO_TRAFFIC)
    }

    // MARK: - Auto-Disconnect Management
    
    /**
     * Updates remaining auto-disconnect time (e.g., after watching an ad).
     * @return New remaining time in seconds, or -1 if auto-disconnect not active
     */
    /**
     * Updates remaining auto-disconnect time (e.g., after watching an ad).
     * Handles cross-process communication if called from App Process.
     * @return New remaining time in seconds, or -1 if update sent async or failed
     */
    fun updateAutoDisconnectTime(context: Context, additionalSeconds: Int): Int {
        // CASE 1: Active Service Process (Timer running)
        if (serviceContext != null) {
            if (!autoDisconnectEnabled || remainingAutoDisconnectSeconds < 0) {
                return -1
            }
            remainingAutoDisconnectSeconds += additionalSeconds
            if (remainingAutoDisconnectSeconds < 0) remainingAutoDisconnectSeconds = 0
            Log.d(TAG, "Auto-disconnect time updated: ${remainingAutoDisconnectSeconds}s remaining")
            
            // Update notification to show new remaining time
            val config = AppConfigs.V2RAY_CONFIG
            if (config != null) {
                updateNotificationWithRemainingTime(context, config)
            }
            
            return remainingAutoDisconnectSeconds
        }
        
        // CASE 2: Service Process but timer not started (called from onStartCommand before VPN ready)
        // Check exact class matching to avoid instanceof inheritance issues, though unlikely for Service
        if (context.javaClass.name == XrayVPNService::class.java.name) {
            Log.w(TAG, "Update called in Service before timer started")
            return -1
        }
        
        // CASE 3: App Process (sending command to service)
        return try {
            val intent = Intent(context, XrayVPNService::class.java).apply {
                putExtra("COMMAND", AppConfigs.V2RAY_SERVICE_COMMANDS.UPDATE_AUTO_DISCONNECT)
                putExtra("ADDITIONAL_SECONDS", additionalSeconds)
            }
            context.startService(intent)
            Log.d(TAG, "Sent UPDATE_AUTO_DISCONNECT command to service")
            -1 // Return -1 as we don't know the result synchronously
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send update command to service", e)
            -1
        }
    }
    
    /**
     * Gets the current remaining auto-disconnect time.
     * @return Remaining time in seconds, or -1 if auto-disconnect not active
     */
    fun getRemainingAutoDisconnectTime(): Int {
        return if (autoDisconnectEnabled) remainingAutoDisconnectSeconds else -1
    }
    
    /**
     * Cancels auto-disconnect - VPN stays connected indefinitely.
     */
    fun cancelAutoDisconnect() {
        autoDisconnectEnabled = false
        remainingAutoDisconnectSeconds = -1
        
        // Update notification to remove remaining time
        serviceContext?.let { context ->
            val config = AppConfigs.V2RAY_CONFIG
            if (config != null) {
                showNotification(context, config)
            }
        }
        Log.d(TAG, "Auto-disconnect cancelled")
    }
    
    private fun handleAutoDisconnectExpiry(context: Context) {
        Log.d(TAG, "Auto-disconnect time expired")
        autoDisconnectEnabled = false
        
        val config = AppConfigs.V2RAY_CONFIG
        
        // Save flag to SharedPreferences so app can check on startup
        @Suppress("DEPRECATION")
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS)
            .edit()
            .putBoolean(KEY_AUTO_DISCONNECT_EXPIRED, true)
            .commit() // Use commit() to ensure write to disk before service stops
        Log.d(TAG, "Auto-disconnect expired flag saved to SharedPreferences (Synced)")
        
        // Send AUTO_DISCONNECTED state broadcast
        Intent(AppConfigs.V2RAY_CONNECTION_INFO).apply {
            putExtra("STATE", AppConfigs.V2RAY_STATES.V2RAY_AUTO_DISCONNECTED)
            putExtra("DURATION", connectionDurationSeconds.toString())
            putExtra("UPLOAD_SPEED", 0L)
            putExtra("DOWNLOAD_SPEED", 0L)
            putExtra("UPLOAD_TRAFFIC", 0L)
            putExtra("DOWNLOAD_TRAFFIC", 0L)
        }.also { context.sendBroadcast(it) }
        
        // Show expiry notification if configured
        if (config != null && config.AUTO_DISCONNECT_ON_EXPIRE == 1) {
            showExpiryNotification(context, config)
        }
        
        // Stop VPN service properly by sending STOP_SERVICE command
        val stopIntent = Intent(context, XrayVPNService::class.java).apply {
            putExtra("COMMAND", AppConfigs.V2RAY_SERVICE_COMMANDS.STOP_SERVICE)
        }
        context.startService(stopIntent)
    }
    
    private fun showExpiryNotification(context: Context, config: XrayConfig) {
        val channelId = createNotificationChannel(context, config.APPLICATION_NAME)
        
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        launchIntent?.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val contentPendingIntent = PendingIntent.getActivity(context, 1, launchIntent, flags)
        
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(config.APPLICATION_ICON)
            .setContentTitle(config.APPLICATION_NAME)
            .setContentText(config.AUTO_DISCONNECT_EXPIRED_MESSAGE)
            .setContentIntent(contentPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.notify(EXPIRY_NOTIFICATION_ID, notification)
    }
    
    private fun updateNotificationWithRemainingTime(context: Context, config: XrayConfig) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return
            }
        }
        
        val channelId = createNotificationChannel(context, config.APPLICATION_NAME)
        val timeText = formatRemainingTime(remainingAutoDisconnectSeconds, config.AUTO_DISCONNECT_TIME_FORMAT)
        
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        launchIntent?.action = "FROM_DISCONNECT_BTN"
        launchIntent?.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val contentPendingIntent = PendingIntent.getActivity(context, 0, launchIntent, flags)
        
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(config.APPLICATION_ICON)
            .setContentTitle(config.REMARK)
            .setContentText("Connected • $timeText remaining")
            .setContentIntent(contentPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        }
        
        if (config.SHOW_NOTIFICATION_DISCONNECT_BUTTON) {
            val stopIntent = Intent(context, XrayVPNService::class.java)
            stopIntent.putExtra("COMMAND", AppConfigs.V2RAY_SERVICE_COMMANDS.STOP_SERVICE)
            val stopPendingIntent = PendingIntent.getService(context, 0, stopIntent, flags)
            builder.addAction(0, config.NOTIFICATION_DISCONNECT_BUTTON_NAME, stopPendingIntent)
        }
        
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.notify(NOTIFICATION_ID, builder.build())
    }
    
    private fun formatRemainingTime(seconds: Int, format: Int): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        
        return if (format == 0) {
            // withSeconds: "1h 30m 10s"
            buildString {
                if (hours > 0) append("${hours}h ")
                if (minutes > 0 || hours > 0) append("${minutes}m ")
                append("${secs}s")
            }.trim()
        } else {
            // withoutSeconds: "1h 30m"
            buildString {
                if (hours > 0) append("${hours}h ")
                append("${minutes}m")
            }.trim().ifEmpty { "<1m" }
        }
    }

    // MARK: - Broadcast Helpers

    private fun sendDisconnectedBroadcast(context: Context) {
        Intent(AppConfigs.V2RAY_CONNECTION_INFO).apply {
            putExtra("STATE", AppConfigs.V2RAY_STATES.V2RAY_DISCONNECTED)
            putExtra("DURATION", "0")
            putExtra("UPLOAD_SPEED", 0L)
            putExtra("DOWNLOAD_SPEED", 0L)
            putExtra("UPLOAD_TRAFFIC", 0L)
            putExtra("DOWNLOAD_TRAFFIC", 0L)
        }.also { context.sendBroadcast(it) }
    }

    private fun showNotification(context: Service, config: XrayConfig) {
        // Check notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Notification permission not granted - VPN will run without notification")
                Log.w(TAG, "Service will still appear in 'VPN' section of system settings")
                return
            }
        }

        val channelId = createNotificationChannel(context, config.APPLICATION_NAME)
        
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        launchIntent?.action = "FROM_DISCONNECT_BTN"
        launchIntent?.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val contentPendingIntent = PendingIntent.getActivity(context, 0, launchIntent, flags)

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(config.APPLICATION_ICON)
            .setContentTitle(config.REMARK)
            .setContentText("Connected")
            .setContentIntent(contentPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)  // Changed from PRIORITY_MIN
            .setOngoing(true)
            .setShowWhen(true)
        
        // Android 12+ (API 31): Show notification immediately instead of 10-second delay
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        }

        // Conditionally add disconnect button based on config
        if (config.SHOW_NOTIFICATION_DISCONNECT_BUTTON) {
            val stopIntent = Intent(context, XrayVPNService::class.java)
            stopIntent.putExtra("COMMAND", AppConfigs.V2RAY_SERVICE_COMMANDS.STOP_SERVICE)
            val stopPendingIntent = PendingIntent.getService(context, 0, stopIntent, flags)
            builder.addAction(0, config.NOTIFICATION_DISCONNECT_BUTTON_NAME, stopPendingIntent)
        }

        context.startForeground(NOTIFICATION_ID, builder.build())
        Log.d(TAG, "Notification shown with disconnect button: ${config.SHOW_NOTIFICATION_DISCONNECT_BUTTON}")
    }

    // MARK: - Delay Measurement

    /**
     * Measures delay for the currently connected server.
     */
    fun getConnectedV2rayServerDelay(context: Context, url: String): Long {
        val port = AppConfigs.V2RAY_CONFIG?.LOCAL_SOCKS5_PORT ?: DEFAULT_SOCKS_PORT
        Log.d(TAG, "Measuring delay to $url via SOCKS port $port")

        return measureDelay(url, port)
    }

    /**
     * Measures delay for a server configuration (when not connected).
     * Temporarily starts Xray with the provided config, measures delay, then stops it.
     */
    fun getServerDelay(context: Context, configJson: String, url: String): Long {
        Log.d(TAG, "Starting temporary Xray instance for delay measurement")

        var tempProcess: Process? = null
        return runCatching {
            val freePort = findFreePort()
            val tempConfig = prepareTemporaryConfig(context, configJson, freePort)
            
            tempProcess = startTemporaryXray(context, tempConfig)
            Thread.sleep(TEMP_XRAY_STARTUP_DELAY_MS)
            
            measureDelay(url, freePort).also {
                tempProcess?.destroy()
                tempConfig.delete()
            }
        }.onFailure {
            Log.e(TAG, "Error measuring server delay", it)
            tempProcess?.destroy()
        }.getOrDefault(-1L)
    }

    private fun measureDelay(url: String, socksPort: Int): Long {
        return runCatching {
            val startTime = System.currentTimeMillis()
            val proxy = java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress(LOCALHOST, socksPort))
            
            val connection = java.net.URL(url).openConnection(proxy) as java.net.HttpURLConnection
            connection.connectTimeout = CONNECTION_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.requestMethod = "HEAD"
            
            try {
                val responseCode = connection.responseCode
                val delay = System.currentTimeMillis() - startTime
                Log.d(TAG, "Delay measurement successful: ${delay}ms (response code: $responseCode)")
                delay
            } finally {
                connection.disconnect()
            }
        }.onFailure {
            Log.e(TAG, "Failed to measure delay", it)
        }.getOrDefault(-1L)
    }

    private fun findFreePort(): Int {
        return runCatching {
            java.net.ServerSocket(0).use { it.localPort }
        }.getOrDefault(DEFAULT_TEMP_PORT)
    }

    private fun prepareTemporaryConfig(context: Context, configJson: String, port: Int): File {
        val json = JSONObject(configJson)
        val inbounds = json.optJSONArray("inbounds") ?: JSONArray().also { json.put("inbounds", it) }

        // Update or inject SOCKS inbound
        var updated = false
        for (i in 0 until inbounds.length()) {
            val inbound = inbounds.getJSONObject(i)
            if (inbound.optString("protocol") == "socks") {
                inbound.put("port", port)
                updated = true
                break
            }
        }

        if (!updated) {
            inbounds.put(createSocksInbound(port))
        }

        Log.d(TAG, "Using SOCKS port $port for delay measurement")
        
        return File(context.filesDir, TEMP_CONFIG_FILE_NAME).apply {
            writeText(json.toString())
        }
    }

    private fun startTemporaryXray(context: Context, configFile: File): Process {
        val xrayExecutable = File(context.applicationInfo.nativeLibraryDir, XRAY_EXECUTABLE_NAME)
        require(xrayExecutable.exists()) { "Xray executable not found" }

        Utilities.copyAssets(context)

        return ProcessBuilder(listOf(xrayExecutable.absolutePath, "-config", configFile.absolutePath))
            .apply {
                directory(context.filesDir)
                environment()[ENV_XRAY_LOCATION_ASSET] = Utilities.getUserAssetsPath(context)
            }
            .start()
    }

    // MARK: - Notification Management

    private fun createNotificationChannel(context: Context, appName: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "$appName Background Service",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                lightColor = Color.BLUE
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
            
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                ?.createNotificationChannel(channel)
                
            return NOTIFICATION_CHANNEL_ID
        }
        return ""
    }

    // MARK: - Constants
    
    private const val TAG = "XrayCoreManager"
    
    // Notification
    private const val NOTIFICATION_ID = 1
    private const val EXPIRY_NOTIFICATION_ID = 2
    private const val NOTIFICATION_CHANNEL_ID = "XRAY_SERVICE_CHANNEL"
    
    // Configuration Files
    private const val CONFIG_FILE_NAME = "config.json"
    private const val TEMP_CONFIG_FILE_NAME = "temp_delay_config.json"
    private const val XRAY_EXECUTABLE_NAME = "libxray.so"
    
    // Network
    private const val LOCALHOST = "127.0.0.1"
    private const val DEFAULT_API_PORT = 10809
    private const val DEFAULT_SOCKS_PORT = 10807
    private const val DEFAULT_TEMP_PORT = 10806
    
    // Environment Variables
    private const val ENV_XRAY_LOCATION_ASSET = "XRAY_LOCATION_ASSET"
    
    // Traffic Statistics
    private val ZERO_TRAFFIC = longArrayOf(0, 0, 0, 0)
    
    // Timing
    private const val TIMER_INTERVAL_MS = 1000L
    private const val TEMP_XRAY_STARTUP_DELAY_MS = 1000L
    private const val CONNECTION_TIMEOUT_MS = 5000
    private const val READ_TIMEOUT_MS = 5000
    
    // SharedPreferences for auto-disconnect flag
    private const val PREFS_NAME = "flutter_v2ray_prefs"
    private const val KEY_AUTO_DISCONNECT_EXPIRED = "auto_disconnect_expired"
    
    /**
     * Checks if VPN was auto-disconnected while app was killed.
     * @return true if auto-disconnect expired, false otherwise
     */
    fun wasAutoDisconnected(context: Context): Boolean {
        @Suppress("DEPRECATION")
        val result = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS)
            .getBoolean(KEY_AUTO_DISCONNECT_EXPIRED, false)
        Log.d(TAG, "wasAutoDisconnected check: $result")
        return result
    }
    
    /**
     * Clears the auto-disconnect expired flag.
     * Should be called after app has handled the expired state.
     */
    fun clearAutoDisconnectFlag(context: Context) {
        @Suppress("DEPRECATION")
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS)
            .edit()
            .putBoolean(KEY_AUTO_DISCONNECT_EXPIRED, false)
            .commit()
    }
}
