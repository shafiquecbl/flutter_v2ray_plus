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
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream

/**
 * Manages the Xray Core process (libxray.so).
 * 
 * This singleton object is responsible for:
 * 1. Generating the final Xray configuration file (config.json).
 * 2. Injecting necessary inbounds (SOCKS, HTTP, API) into the user-provided config.
 * 3. Starting and monitoring the Xray process.
 * 4. Collecting traffic statistics via the Xray API.
 * 5. Showing the persistent foreground notification.
 */
object XrayCoreManager {

    private const val NOTIFICATION_ID = 1
    private const val TAG = "XrayCoreManager"
    private var xrayProcess: Process? = null
    private var countDownTimer: CountDownTimer? = null
    private var seconds = 0

    /**
     * Starts the Xray Core process.
     * 
     * @param context The service context (needed for file access and notifications).
     * @param config The configuration object containing the user's settings.
     * @return true if started successfully, false otherwise.
     */
    fun startCore(context: Service, config: XrayConfig): Boolean {
        AppConfigs.V2RAY_STATE = AppConfigs.V2RAY_STATES.V2RAY_CONNECTING
        AppConfigs.V2RAY_CONFIG = config

        // 1. Prepare the configuration file
        val configFilesDir = context.filesDir
        
        try {
            // Parse the user-provided JSON config
            val configJson = JSONObject(config.V2RAY_FULL_JSON_CONFIG)
            
            // --- Configuration Injection ---
            // We need to inject several things into the config to make it work with our app:
            // 1. API Service (for stats)
            // 2. SOCKS/HTTP Inbounds (for local proxying)
            // 3. Routing rules (to route API traffic)

            // 1. Add API section (StatsService)
            val apiObj = JSONObject()
            apiObj.put("tag", "api")
            val servicesArr = org.json.JSONArray()
            servicesArr.put("StatsService")
            apiObj.put("services", servicesArr)
            configJson.put("api", apiObj)
            
            // 2. Add Stats section
            configJson.put("stats", JSONObject())
            
            // 3. Add Policy section (enable stats for all levels)
            val policyObj = JSONObject()
            val levelsObj = JSONObject()
            val level8Obj = JSONObject()
            level8Obj.put("statsUserUplink", true)
            level8Obj.put("statsUserDownlink", true)
            levelsObj.put("8", level8Obj)
            
            val systemObj = JSONObject()
            systemObj.put("statsInboundUplink", true)
            systemObj.put("statsInboundDownlink", true)
            systemObj.put("statsOutboundUplink", true)
            systemObj.put("statsOutboundDownlink", true)
            
            policyObj.put("levels", levelsObj)
            policyObj.put("system", systemObj)
            configJson.put("policy", policyObj)
            
            // 4. Add Inbounds (SOCKS, HTTP, API)
            val inbounds = configJson.optJSONArray("inbounds") ?: org.json.JSONArray()
            
            // Check if SOCKS/HTTP inbounds already exist in the user config
            var hasSocks = false
            var hasHttp = false
            for (i in 0 until inbounds.length()) {
                val inbound = inbounds.getJSONObject(i)
                val protocol = inbound.optString("protocol")
                if (protocol == "socks") hasSocks = true
                if (protocol == "http") hasHttp = true
            }

            // Inject SOCKS inbound if missing (Required for tun2socks and Proxy Mode)
            if (!hasSocks) {
                val socksInbound = JSONObject()
                socksInbound.put("tag", "socks")
                socksInbound.put("port", config.LOCAL_SOCKS5_PORT)
                socksInbound.put("listen", "127.0.0.1")
                socksInbound.put("protocol", "socks")
                val settings = JSONObject()
                settings.put("auth", "noauth")
                settings.put("udp", true)
                socksInbound.put("settings", settings)
                socksInbound.put("sniffing", JSONObject().put("enabled", true).put("destOverride", JSONArray().put("http").put("tls")))
                inbounds.put(socksInbound)
                Log.d(TAG, "Injected SOCKS inbound on port ${config.LOCAL_SOCKS5_PORT}")
            }

            // Inject HTTP inbound if missing (Useful for Proxy Mode)
            if (!hasHttp) {
                val httpInbound = JSONObject()
                httpInbound.put("tag", "http")
                httpInbound.put("port", config.LOCAL_HTTP_PORT)
                httpInbound.put("listen", "127.0.0.1")
                httpInbound.put("protocol", "http")
                inbounds.put(httpInbound)
                Log.d(TAG, "Injected HTTP inbound on port ${config.LOCAL_HTTP_PORT}")
            }

            // Inject API Inbound (dokodemo-door) for stats querying
            val apiInbound = JSONObject()
            apiInbound.put("tag", "api")
            apiInbound.put("port", config.LOCAL_API_PORT)
            apiInbound.put("listen", "127.0.0.1")
            apiInbound.put("protocol", "dokodemo-door")
            val settings = JSONObject()
            settings.put("address", "127.0.0.1")
            apiInbound.put("settings", settings)
            inbounds.put(apiInbound)
            configJson.put("inbounds", inbounds)
            
            // 5. Add Routing Rule for API
            val routing = configJson.optJSONObject("routing") ?: JSONObject()
            val rules = routing.optJSONArray("rules") ?: org.json.JSONArray()
            val apiRule = JSONObject()
            apiRule.put("type", "field")
            apiRule.put("inboundTag", org.json.JSONArray().put("api"))
            apiRule.put("outboundTag", "api")
            rules.put(apiRule)
            routing.put("rules", rules)
            configJson.put("routing", routing)

            // Write the final config to disk
            val configFile = File(context.filesDir, "config.json")
            configFile.writeText(configJson.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write config file", e)
            return false
        }

        // 2. Find Xray executable (libxray.so)
        val nativeLibraryDir = context.applicationInfo.nativeLibraryDir
        val xrayExecutable = File(nativeLibraryDir, "libxray.so")
        if (!xrayExecutable.exists()) {
            Log.e(TAG, "Xray executable not found at ${xrayExecutable.absolutePath}")
            // Fallback or error
            return false
        }

        // 3. Prepare assets (geoip, geosite)
        Utilities.copyAssets(context)

        // 4. Run Xray
        try {
            val cmd = listOf(
                xrayExecutable.absolutePath,
                "-config", File(configFilesDir, "config.json").absolutePath
            )
            val pb = ProcessBuilder(cmd)
            pb.directory(configFilesDir)
            // Redirect output to logcat or ignore
            // pb.redirectOutput(ProcessBuilder.Redirect.INHERIT)
            // pb.redirectError(ProcessBuilder.Redirect.INHERIT)
            
            // Set environment variables (XRAY_LOCATION_ASSET is crucial for finding geoip/geosite)
            val env = pb.environment()
            env["XRAY_LOCATION_ASSET"] = Utilities.getUserAssetsPath(context)

            xrayProcess = pb.start()
            
            AppConfigs.V2RAY_STATE = AppConfigs.V2RAY_STATES.V2RAY_CONNECTED
            startTimer(context)
            showNotification(context, config)
            
            // Monitor process in a separate thread to detect crash
            Thread {
                try {
                    xrayProcess?.inputStream?.bufferedReader()?.use { reader ->
                        reader.forEachLine { line ->
                            Log.d(TAG, "xray: $line")
                        }
                    }
                    
                    val exitCode = xrayProcess?.waitFor()
                    Log.e(TAG, "Xray process exited with code $exitCode")
                    if (AppConfigs.V2RAY_STATE == AppConfigs.V2RAY_STATES.V2RAY_CONNECTED) {
                        // Unexpected exit
                        stopCore(context)
                    }
                } catch (e: java.io.InterruptedIOException) {
                    // Expected when stopping
                } catch (e: InterruptedException) {
                    // Expected when stopping
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading xray output", e)
                }
            }.start()

            return true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Xray process", e)
            return false
        }
    }

    /**
     * Stops the Xray Core process and cleans up notifications.
     */
    fun stopCore(context: Service) {
        try {
            xrayProcess?.destroy()
            xrayProcess = null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to destroy Xray process", e)
        }

        AppConfigs.V2RAY_STATE = AppConfigs.V2RAY_STATES.V2RAY_DISCONNECTED
        stopTimer()
        
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)
        
        sendDisconnectedBroadcast(context)
    }

    fun isXrayRunning(): Boolean {
        // Check state instead of process because VPN runs in separate service process
        return AppConfigs.V2RAY_STATE == AppConfigs.V2RAY_STATES.V2RAY_CONNECTED ||
               AppConfigs.V2RAY_STATE == AppConfigs.V2RAY_STATES.V2RAY_CONNECTING
    }

    private fun startTimer(context: Context) {
        countDownTimer?.cancel()
        seconds = 0
        countDownTimer = object : CountDownTimer(Long.MAX_VALUE, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                seconds++
                val intent = Intent(AppConfigs.V2RAY_CONNECTION_INFO)
                intent.putExtra("STATE", AppConfigs.V2RAY_STATE)
                intent.putExtra("DURATION", seconds.toString())
                
                val traffic = getV2rayTraffic(context)
                intent.putExtra("UPLOAD_SPEED", traffic[0])
                intent.putExtra("DOWNLOAD_SPEED", traffic[1])
                intent.putExtra("UPLOAD_TRAFFIC", traffic[2])
                intent.putExtra("DOWNLOAD_TRAFFIC", traffic[3])
                
                context.sendBroadcast(intent)
            }

            override fun onFinish() {}
        }.start()
    }

    /**
     * Queries Xray API for traffic statistics.
     * Returns [uploadSpeed, downloadSpeed, totalUpload, totalDownload]
     */
    fun getV2rayTraffic(context: Context): LongArray {
        if (!isXrayRunning()) return longArrayOf(0, 0, 0, 0)

        val xrayPath = File(context.applicationInfo.nativeLibraryDir, "libxray.so").absolutePath
        val cmd = arrayListOf(
            xrayPath,
            "api",
            "statsquery",
            "--server=127.0.0.1:${AppConfigs.V2RAY_CONFIG?.LOCAL_API_PORT ?: 10809}",
            "--pattern", ""
        )

        try {
            val pb = ProcessBuilder(cmd)
            val process = pb.start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()

            if (output.isNotEmpty()) {
                Log.d(TAG, "Stats query output: $output")
                val json = JSONObject(output)
                val stats = json.optJSONArray("stat") ?: return longArrayOf(0, 0, 0, 0)

                var uplink = 0L
                var downlink = 0L

                for (i in 0 until stats.length()) {
                    val stat = stats.getJSONObject(i)
                    val name = stat.optString("name")
                    val value = stat.optLong("value")

                    if (name.contains("uplink")) {
                        uplink += value
                    } else if (name.contains("downlink")) {
                        downlink += value
                    }
                }
                return longArrayOf(uplink, downlink, uplink, downlink) 
            } else {
                Log.d(TAG, "Stats query returned empty output")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query stats", e)
        }
        return longArrayOf(0, 0, 0, 0)
    }

    private fun stopTimer() {
        countDownTimer?.cancel()
        countDownTimer = null
        seconds = 0
    }

    private fun sendDisconnectedBroadcast(context: Context) {
        val intent = Intent(AppConfigs.V2RAY_CONNECTION_INFO)
        intent.putExtra("STATE", AppConfigs.V2RAY_STATES.V2RAY_DISCONNECTED)
        intent.putExtra("DURATION", "0")
        intent.putExtra("UPLOAD_SPEED", 0L)
        intent.putExtra("DOWNLOAD_SPEED", 0L)
        intent.putExtra("UPLOAD_TRAFFIC", 0L)
        intent.putExtra("DOWNLOAD_TRAFFIC", 0L)
        context.sendBroadcast(intent)
    }

    private fun showNotification(context: Service, config: XrayConfig) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return
            }
        }

        val channelId = createNotificationChannel(context, config.APPLICATION_NAME)
        
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        launchIntent?.action = "FROM_DISCONNECT_BTN"
        launchIntent?.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_UPDATE_CURRENT
        val contentPendingIntent = PendingIntent.getActivity(context, 0, launchIntent, flags)

        val stopIntent = Intent(context, XrayVPNService::class.java)
        stopIntent.putExtra("COMMAND", AppConfigs.V2RAY_SERVICE_COMMANDS.STOP_SERVICE)
        val stopPendingIntent = PendingIntent.getService(context, 0, stopIntent, flags)

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(config.APPLICATION_ICON) // Ensure this icon resource exists or is passed correctly
            .setContentTitle(config.REMARK)
            .setContentText("Connected")
            .addAction(0, config.NOTIFICATION_DISCONNECT_BUTTON_NAME, stopPendingIntent)
            .setContentIntent(contentPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setShowWhen(true)

        context.startForeground(NOTIFICATION_ID, builder.build())
    }

    fun getConnectedV2rayServerDelay(context: Context, url: String): Long {
        // Use the configured SOCKS port (default 10807)
        val port = AppConfigs.V2RAY_CONFIG?.LOCAL_SOCKS5_PORT ?: 10807
        Log.d(TAG, "getConnectedV2rayServerDelay: Testing delay to $url via SOCKS port $port")
        
        return try {
            val start = System.currentTimeMillis()
            val proxy = java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress("127.0.0.1", port))
            val connection = java.net.URL(url).openConnection(proxy) as java.net.HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.requestMethod = "HEAD"
            val responseCode = connection.responseCode
            val end = System.currentTimeMillis()
            val delay = end - start
            connection.disconnect()
            Log.d(TAG, "getConnectedV2rayServerDelay: Success! Response code: $responseCode, Delay: ${delay}ms")
            delay
        } catch (e: Exception) {
            Log.e(TAG, "getConnectedV2rayServerDelay: Failed to measure delay (VPN may not be running)", e)
            -1L
        }
    }

    /**
     * Measure delay for a server configuration (when not connected).
     * Temporarily starts Xray with the provided config, measures delay, then stops it.
     */
    fun getServerDelay(context: Context, configJson: String, url: String): Long {
        Log.d(TAG, "getServerDelay: Starting temporary Xray instance")
        
        var tempProcess: Process? = null
        try {
            // Find a random free port to avoid conflict with running VPN
            val freePort = try {
                val socket = java.net.ServerSocket(0)
                val port = socket.localPort
                socket.close()
                port
            } catch (e: Exception) {
                10806 // Fallback
            }
            
            // Parse the config
            val json = JSONObject(configJson)
            var inbounds = json.optJSONArray("inbounds")
            if (inbounds == null) {
                inbounds = JSONArray()
                json.put("inbounds", inbounds)
            }
            
            var hasSocks = false
            
            // Update existing SOCKS inbound if present
            for (i in 0 until inbounds.length()) {
                val inbound = inbounds.getJSONObject(i)
                if (inbound.optString("protocol") == "socks") {
                    inbound.put("port", freePort) // Force use free port
                    hasSocks = true
                    break
                }
            }
            
            if (!hasSocks) {
                // Inject SOCKS inbound
                val socksInbound = JSONObject()
                socksInbound.put("tag", "socks")
                socksInbound.put("port", freePort)
                socksInbound.put("listen", "127.0.0.1")
                socksInbound.put("protocol", "socks")
                val settings = JSONObject()
                settings.put("auth", "noauth")
                settings.put("udp", true)
                socksInbound.put("settings", settings)
                inbounds.put(socksInbound)
                Log.d(TAG, "getServerDelay: Injected SOCKS inbound on port $freePort")
            }
            
            Log.d(TAG, "getServerDelay: Using SOCKS port $freePort")
            
            // Write temp config file
            val tempConfigFile = File(context.filesDir, "temp_delay_config.json")
            tempConfigFile.writeText(json.toString())
            
            // Copy assets
            Utilities.copyAssets(context)
            
            // Start Xray process
            val xrayExecutable = File(context.applicationInfo.nativeLibraryDir, "libxray.so")
            if (!xrayExecutable.exists()) {
                Log.e(TAG, "getServerDelay: Xray executable not found")
                return -1L
            }
            
            val cmd = listOf(
                xrayExecutable.absolutePath,
                "-config", tempConfigFile.absolutePath
            )
            
            val pb = ProcessBuilder(cmd)
            pb.directory(context.filesDir)
            val env = pb.environment()
            env["XRAY_LOCATION_ASSET"] = Utilities.getUserAssetsPath(context)
            
            tempProcess = pb.start()
            
            // Wait a bit for Xray to start
            Thread.sleep(1000)
            
            // Measure delay
            val delay = try {
                val start = System.currentTimeMillis()
                val proxy = java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress("127.0.0.1", freePort))
                val connection = java.net.URL(url).openConnection(proxy) as java.net.HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.requestMethod = "HEAD"
                val responseCode = connection.responseCode
                val end = System.currentTimeMillis()
                connection.disconnect()
                val result = end - start
                Log.d(TAG, "getServerDelay: Success! Response code: $responseCode, Delay: ${result}ms")
                result
            } catch (e: Exception) {
                Log.e(TAG, "getServerDelay: Failed to measure delay", e)
                -1L
            }
            
            // Stop temp process
            tempProcess?.destroy()
            tempConfigFile.delete()
            
            return delay
            
        } catch (e: Exception) {
            Log.e(TAG, "getServerDelay: Error starting temp Xray", e)
            tempProcess?.destroy()
            return -1L
        }
    }

    private fun createNotificationChannel(context: Context, appName: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "XRAY_SERVICE_CHANNEL"
            val channelName = "$appName Background Service"
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_DEFAULT)
            channel.lightColor = Color.BLUE
            channel.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
            return channelId
        }
        return ""
    }
}
