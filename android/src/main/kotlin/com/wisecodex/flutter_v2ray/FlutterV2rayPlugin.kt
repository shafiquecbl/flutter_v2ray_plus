package com.wisecodex.flutter_v2ray

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import androidx.core.app.ActivityCompat
import com.wisecodex.flutter_v2ray.xray.core.XrayCoreManager
import com.wisecodex.flutter_v2ray.xray.dto.XrayConfig
import com.wisecodex.flutter_v2ray.xray.service.XrayVPNService
import com.wisecodex.flutter_v2ray.xray.utils.AppConfigs
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.ArrayList
import java.util.concurrent.Executors

/**
 * Main entry point for the Flutter V2ray plugin on Android.
 *
 * Responsibilities:
 * - Receiving commands from Flutter (start, stop, get delay, etc.)
 * - Managing permissions (VPN, Notifications)
 * - Starting the XrayVPNService to run the VPN or Proxy
 * - Sending status updates and traffic statistics back to Flutter via EventChannel
 *
 * This class acts as the bridge between Flutter (Dart) and Android (Kotlin)
 * using MethodChannels for commands and EventChannel for status streaming.
 */
class FlutterV2rayPlugin : FlutterPlugin, ActivityAware, PluginRegistry.ActivityResultListener, MethodChannel.MethodCallHandler {

    // MARK: - Properties

    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var vpnControlMethod: MethodChannel
    private lateinit var vpnStatusEvent: EventChannel
    private lateinit var context: Context
    
    private var vpnStatusSink: EventChannel.EventSink? = null
    private var activity: Activity? = null
    private var xrayReceiver: BroadcastReceiver? = null
    private var pendingResult: MethodChannel.Result? = null

    // MARK: - Flutter Plugin Lifecycle

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        
        vpnControlMethod = MethodChannel(binding.binaryMessenger, METHOD_CHANNEL_NAME)
        vpnStatusEvent = EventChannel(binding.binaryMessenger, EVENT_CHANNEL_NAME)

        vpnControlMethod.setMethodCallHandler(this)
        vpnStatusEvent.setStreamHandler(createEventStreamHandler())
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        vpnControlMethod.setMethodCallHandler(null)
        vpnStatusEvent.setStreamHandler(null)
        executor.shutdown()
    }

    private fun createEventStreamHandler() = object : EventChannel.StreamHandler {
        override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
            vpnStatusSink = events
            registerReceiver()
        }

        override fun onCancel(arguments: Any?) {
            vpnStatusSink = null
            unregisterReceiver()
        }
    }

    // MARK: - Method Call Handling


    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "startVless" -> handleStartVless(call, result)
            "stopVless" -> handleStopVless(result)
            "initializeVless" -> handleInitialize(call, result)
            "getServerDelay" -> handleGetServerDelay(call, result)
            "getConnectedServerDelay" -> handleGetConnectedServerDelay(call, result)
            "getCoreVersion" -> handleGetCoreVersion(result)
            "requestPermission" -> handleRequestPermission(result)
            "updateAutoDisconnectTime" -> handleUpdateAutoDisconnectTime(call, result)
            "getRemainingAutoDisconnectTime" -> handleGetRemainingAutoDisconnectTime(result)
            "cancelAutoDisconnect" -> handleCancelAutoDisconnect(result)
            "wasAutoDisconnected" -> handleWasAutoDisconnected(result)
            "clearAutoDisconnectFlag" -> handleClearAutoDisconnectFlag(result)
            else -> result.notImplemented()
        }
    }

    // MARK: - Method Handlers

    private fun handleStartVless(call: MethodCall, result: MethodChannel.Result) {
        val config = call.argument<String>("config")
        val proxyOnly = call.argument<Boolean>("proxy_only") ?: false
        val showDisconnectButton = call.argument<Boolean>("showNotificationDisconnectButton") ?: true

        if (config == null) {
            result.error("INVALID_CONFIG", "Config cannot be null", null)
            return
        }

        val xrayConfig = buildXrayConfig(call, config).apply {
            SHOW_NOTIFICATION_DISCONNECT_BUTTON = showDisconnectButton
        }

        startVpnService(xrayConfig, proxyOnly)
        result.success(null)
    }

    private fun buildXrayConfig(call: MethodCall, config: String) = XrayConfig().apply {
        REMARK = call.argument("remark") ?: ""
        V2RAY_FULL_JSON_CONFIG = call.argument("config") ?: ""
        BLOCKED_APPS = call.argument<ArrayList<String>>("blocked_apps") ?: ArrayList()
        BYPASS_SUBNETS = call.argument<ArrayList<String>>("bypass_subnets") ?: ArrayList()
        DNS_SERVERS = call.argument<ArrayList<String>>("dns_servers")
        NOTIFICATION_DISCONNECT_BUTTON_NAME = call.argument("notificationDisconnectButtonName") ?: DEFAULT_DISCONNECT_BUTTON_TEXT
        
        // Parse auto-disconnect configuration
        val autoDisconnect = call.argument<Map<String, Any?>>("auto_disconnect")
        if (autoDisconnect != null) {
            AUTO_DISCONNECT_DURATION = (autoDisconnect["duration"] as? Int) ?: 0
            AUTO_DISCONNECT_SHOW_IN_NOTIFICATION = (autoDisconnect["showRemainingTimeInNotification"] as? Boolean) ?: true
            AUTO_DISCONNECT_TIME_FORMAT = (autoDisconnect["timeFormat"] as? Int) ?: 0
            AUTO_DISCONNECT_ON_EXPIRE = (autoDisconnect["onExpire"] as? Int) ?: 1
            AUTO_DISCONNECT_EXPIRED_MESSAGE = (autoDisconnect["expiredNotificationMessage"] as? String) 
                ?: "Free time expired - VPN disconnected"
        }
        
        if (AppConfigs.NOTIFICATION_ICON_RESOURCE_NAME.isNotEmpty() && 
            AppConfigs.NOTIFICATION_ICON_RESOURCE_TYPE.isNotEmpty()) {
            NOTIFICATION_ICON_RESOURCE_NAME = AppConfigs.NOTIFICATION_ICON_RESOURCE_NAME
            NOTIFICATION_ICON_RESOURCE_TYPE = AppConfigs.NOTIFICATION_ICON_RESOURCE_TYPE
            APPLICATION_ICON = context.resources.getIdentifier(
                AppConfigs.NOTIFICATION_ICON_RESOURCE_NAME,
                AppConfigs.NOTIFICATION_ICON_RESOURCE_TYPE,
                context.packageName
            )
        }
    }
    private fun extractServerAddress(call: MethodCall, config: XrayConfig) {
        runCatching {
            val jsonConfig = org.json.JSONObject(config.V2RAY_FULL_JSON_CONFIG)
            val outbounds = jsonConfig.optJSONArray("outbounds")
            val firstOutbound = outbounds?.getJSONObject(0)
            val settings = firstOutbound?.optJSONObject("settings")
            val vnext = settings?.optJSONArray("vnext")
            val server = vnext?.getJSONObject(0)
            
            config.CONNECTED_V2RAY_SERVER_ADDRESS = server?.optString("address", "") ?: ""
            config.CONNECTED_V2RAY_SERVER_PORT = server?.optInt("port", 0)?.toString() ?: "0"
        }.onFailure {
            android.util.Log.d(TAG, "Could not parse server address from config")
        }
    }

    private fun startVpnService(config: XrayConfig, proxyOnly: Boolean) {
        Intent(context, XrayVPNService::class.java).apply {
            putExtra("COMMAND", AppConfigs.V2RAY_SERVICE_COMMANDS.START_SERVICE)
            putExtra("V2RAY_CONFIG", config)
            putExtra("PROXY_ONLY", proxyOnly)
        }.also { intent ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private fun handleStopVless(result: MethodChannel.Result) {
        context?.let { ctx ->
            val intent = Intent(ctx, XrayVPNService::class.java).apply {
                putExtra("COMMAND", AppConfigs.V2RAY_SERVICE_COMMANDS.STOP_SERVICE)
            }
            try {
                // Command-based stop is preferred for graceful cleanup
                ctx.startService(intent)
                android.util.Log.d("FlutterV2ray", "STOP_SERVICE command sent via startService")
            } catch (e: Exception) {
                // Fallback to stopService if background restrictions apply (Android 12+)
                android.util.Log.w("FlutterV2ray", "Failed to startService with STOP command, falling back to stopService", e)
                ctx.stopService(intent)
            }
        }
        result.success(null)
    }

    private fun handleInitialize(call: MethodCall, result: MethodChannel.Result) {
        call.argument<String>("notificationIconResourceName")?.let { name ->
            call.argument<String>("notificationIconResourceType")?.let { type ->
                AppConfigs.NOTIFICATION_ICON_RESOURCE_NAME = name
                AppConfigs.NOTIFICATION_ICON_RESOURCE_TYPE = type
            }
        }
        result.success(null)
    }

    private fun handleGetServerDelay(call: MethodCall, result: MethodChannel.Result) {
        val configJson = call.argument<String>("config")
        val url = call.argument<String>("url") ?: DEFAULT_TEST_URL
        
        if (configJson == null) {
            result.error("INVALID_CONFIG", "Config is null", null)
            return
        }
        
        activity?.let { currentActivity ->
            executor.execute {
                val delay = XrayCoreManager.getServerDelay(currentActivity, configJson, url)
                currentActivity.runOnUiThread {
                    result.success(delay)
                }
            }
        } ?: result.error("NO_ACTIVITY", "Activity is null", null)
    }

    private fun handleGetConnectedServerDelay(call: MethodCall, result: MethodChannel.Result) {
        val url = call.argument<String>("url") ?: DEFAULT_TEST_URL
        
        activity?.let { currentActivity ->
            executor.execute {
                val delay = XrayCoreManager.getConnectedV2rayServerDelay(currentActivity, url)
                currentActivity.runOnUiThread {
                    result.success(delay)
                }
            }
        } ?: result.error("NO_ACTIVITY", "Activity is null", null)
    }

    private fun handleGetCoreVersion(result: MethodChannel.Result) {
        executor.execute {
            runCatching {
                val xrayExecutable = File(context.applicationInfo.nativeLibraryDir, XRAY_EXECUTABLE_NAME)
                if (xrayExecutable.exists()) {
                    Runtime.getRuntime().exec(arrayOf(xrayExecutable.absolutePath, "-version")).inputStream
                        .bufferedReader()
                        .use { it.readLine() ?: "Unknown version" }
                } else {
                    "Xray not found"
                }
            }.onSuccess {
                result.success(it)
            }.onFailure {
                result.success("Error: ${it.message}")
            }
        }
    }

    private fun handleRequestPermission(result: MethodChannel.Result) {
        activity?.let { currentActivity ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ActivityCompat.checkSelfPermission(currentActivity, Manifest.permission.POST_NOTIFICATIONS) 
                    != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(
                        currentActivity,
                        arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                        REQUEST_CODE_POST_NOTIFICATIONS
                    )
                }
            }
            
            VpnService.prepare(currentActivity)?.let { intent ->
                pendingResult = result
                currentActivity.startActivityForResult(intent, REQUEST_CODE_VPN_PERMISSION)
            } ?: result.success(true)
        } ?: result.error("NO_ACTIVITY", "Activity is null", null)
    }

    // MARK: - Auto-Disconnect Method Handlers

    private fun handleUpdateAutoDisconnectTime(call: MethodCall, result: MethodChannel.Result) {
        val additionalSeconds = call.argument<Int>("additional_seconds") ?: 0
        val newTime = XrayCoreManager.updateAutoDisconnectTime(additionalSeconds)
        result.success(newTime)
    }

    private fun handleGetRemainingAutoDisconnectTime(result: MethodChannel.Result) {
        val remaining = XrayCoreManager.getRemainingAutoDisconnectTime()
        result.success(remaining)
    }

    private fun handleCancelAutoDisconnect(result: MethodChannel.Result) {
        XrayCoreManager.cancelAutoDisconnect()
        result.success(null)
    }

    private fun handleWasAutoDisconnected(result: MethodChannel.Result) {
        val wasExpired = context?.let { XrayCoreManager.wasAutoDisconnected(it) } ?: false
        result.success(wasExpired)
    }

    private fun handleClearAutoDisconnectFlag(result: MethodChannel.Result) {
        context?.let { XrayCoreManager.clearAutoDisconnectFlag(it) }
        result.success(null)
    }

    // MARK: - Broadcast Receiver

    private fun registerReceiver() {
        activity ?: return
        
        if (xrayReceiver == null) {
            xrayReceiver = createBroadcastReceiver()
        }
        
        val filter = IntentFilter(AppConfigs.V2RAY_CONNECTION_INFO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity?.registerReceiver(xrayReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            activity?.registerReceiver(xrayReceiver, filter)
        }
    }

    private fun createBroadcastReceiver() = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null || vpnStatusSink == null) return

            val state = intent.getSerializableExtra("STATE") as? AppConfigs.V2RAY_STATES
            val stateName = when (state) {
                AppConfigs.V2RAY_STATES.V2RAY_CONNECTED -> "CONNECTED"
                AppConfigs.V2RAY_STATES.V2RAY_CONNECTING -> "CONNECTING"
                AppConfigs.V2RAY_STATES.V2RAY_AUTO_DISCONNECTED -> "AUTO_DISCONNECTED"
                else -> "DISCONNECTED"
            }

            val data = arrayListOf(
                intent.getStringExtra("DURATION") ?: "0",
                intent.getLongExtra("UPLOAD_SPEED", 0).toString(),
                intent.getLongExtra("DOWNLOAD_SPEED", 0).toString(),
                intent.getLongExtra("UPLOAD_TRAFFIC", 0).toString(),
                intent.getLongExtra("DOWNLOAD_TRAFFIC", 0).toString(),
                stateName,
                intent.getStringExtra("REMAINING_TIME") // Can be null if auto-disconnect not active
            )

            vpnStatusSink?.success(data)
        }
    }

    private fun unregisterReceiver() {
        activity?.let { currentActivity ->
            xrayReceiver?.let { receiver ->
                runCatching {
                    currentActivity.unregisterReceiver(receiver)
                }
                xrayReceiver = null
            }
        }
    }

    // MARK: - Activity Lifecycle

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        binding.addActivityResultListener(this)
        vpnStatusSink?.let { registerReceiver() }
    }

    override fun onDetachedFromActivityForConfigChanges() {}

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
        binding.addActivityResultListener(this)
        vpnStatusSink?.let { registerReceiver() }
    }

    override fun onDetachedFromActivity() {
        unregisterReceiver()
        activity = null
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode == REQUEST_CODE_VPN_PERMISSION) {
            pendingResult?.success(resultCode == Activity.RESULT_OK)
            pendingResult = null
            return true
        }
        return false
    }

    // MARK: - Companion Object

    companion object {
        private const val TAG = "FlutterV2rayPlugin"
        
        // Channel Names
        private const val METHOD_CHANNEL_NAME = "flutter_v2ray"
        private const val EVENT_CHANNEL_NAME = "flutter_v2ray/status"
        
        // Request Codes
        private const val REQUEST_CODE_VPN_PERMISSION = 24
        private const val REQUEST_CODE_POST_NOTIFICATIONS = 1
        
        // Default Values
        private const val DEFAULT_TEST_URL = "https://www.google.com"
        private const val DEFAULT_DISCONNECT_BUTTON_TEXT = "Disconnect"
        private const val XRAY_EXECUTABLE_NAME = "libxray.so"
    }
}
