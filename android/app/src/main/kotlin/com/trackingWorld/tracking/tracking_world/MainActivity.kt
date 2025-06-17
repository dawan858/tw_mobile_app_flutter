package com.trackingWorld.tracking.tracking_world

import android.content.Context
import android.os.Build
import android.telephony.TelephonyManager
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import android.content.Intent
import android.util.Log
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.app.Activity
import android.app.ActivityManager
import android.net.Uri
import android.provider.Settings
import android.os.PowerManager
import android.annotation.SuppressLint
import java.util.*
import com.trackingWorld.CarPowerPlugin

class MainActivity: FlutterActivity() {
    private val CHANNEL = "com.trackingWorld.tracking/launch"
    private val DEVICE_CHANNEL = "com.trackingWorld.tracking/device_info"
    private val SERVICE_CHANNEL = "com.trackingWorld.tracking/service"
    private val PERMISSION_REQUEST_CODE = 123
    private val BATTERY_OPTIMIZATION_REQUEST_CODE = 124
    private val BACKGROUND_LOCATION_REQUEST_CODE = 125
    private var pendingImeiResult: MethodChannel.Result? = null
    private var flutterEngine: FlutterEngine? = null
    private var autoStartTracking = false
    private var startedBy = ""
    private val SATELLITE_CHANNEL = "com.trackingWorld.tracking/satellite"
    private var carPowerPlugin: CarPowerPlugin? = null

    private val REQUIRED_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        arrayOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        )
    } else {
        arrayOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        this.flutterEngine = flutterEngine
        
        // Initialize Car Power Plugin
        carPowerPlugin = CarPowerPlugin(this, flutterEngine.dartExecutor.binaryMessenger)
        
        // IMEI Channel - IMEI ONLY, NO FALLBACKS
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, DEVICE_CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "getImei" -> {
                    getImeiOnly(result)
                }
                "validateImei" -> {
                    validateImeiOnly(result)
                }
                "getImeiInfo" -> {
                    getImeiDebugInfo(result)
                }
                "forceRefreshImei" -> {
                    forceRefreshImeiOnly(result)
                }
                "requestBatteryOptimization" -> {
                    requestBatteryOptimizationDisable(result)
                }
                "checkBatteryOptimization" -> {
                    result.success(isBatteryOptimizationDisabled())
                }
                else -> result.notImplemented()
            }
        }

        // Launch Channel
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "getLaunchDetails" -> {
                    result.success(mapOf(
                        "autoStart" to autoStartTracking,
                        "startedBy" to startedBy
                    ))
                }
                else -> result.notImplemented()
            }
        }

        // Service Channel
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, SERVICE_CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "startService" -> {
                    try {
                        val serviceIntent = Intent(this, BackgroundService::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(serviceIntent)
                        } else {
                            startService(serviceIntent)
                        }
                        result.success(true)
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Error starting service", e)
                        result.error("SERVICE_ERROR", "Failed to start service", e.message)
                    }
                }
                "stopService" -> {
                    try {
                        stopService(Intent(this, BackgroundService::class.java))
                        result.success(true)
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Error stopping service", e)
                        result.error("SERVICE_ERROR", "Failed to stop service", e.message)
                    }
                }
                "isServiceRunning" -> {
                    result.success(isServiceRunning(BackgroundService::class.java))
                }
                else -> result.notImplemented()
            }
        }

        // Satellite Channel
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, SATELLITE_CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "getSatelliteData" -> {
                    val totalSatellites = BackgroundService.totalSatellites
                    val connectedSatellites = BackgroundService.connectedSatellites
                    result.success(mapOf(
                        "totalSatellites" to totalSatellites,
                        "connectedSatellites" to connectedSatellites
                    ))
                }
                else -> result.notImplemented()
            }
        }
    }

    // IMEI ONLY - NO FALLBACK METHODS
    private fun getImeiOnly(result: MethodChannel.Result) {
        Log.d("MainActivity", "=== GETTING IMEI ONLY (NO FALLBACKS) ===")
        
        try {
            val imeiManager = ImeiManager.getInstance(this)
            val imei = imeiManager.getDeviceIdentifier()
            
            if (imei != "unknown" && imei.isNotEmpty()) {
                Log.d("MainActivity", "✅ Successfully got IMEI")
                result.success(imei)
            } else {
                Log.e("MainActivity", "❌ IMEI not available")
                result.error("IMEI_UNAVAILABLE", "IMEI not available - check permissions and device support", null)
            }
            
        } catch (e: Exception) {
            Log.e("MainActivity", "❌ Error getting IMEI", e)
            result.error("IMEI_ERROR", "Error getting IMEI: ${e.message}", null)
        }
    }

    private fun validateImeiOnly(result: MethodChannel.Result) {
        Log.d("MainActivity", "=== VALIDATING IMEI ONLY ===")
        
        try {
            val imeiManager = ImeiManager.getInstance(this)
            val imei = imeiManager.validateAndRefreshImei()
            
            if (imei != "unknown" && imei.isNotEmpty()) {
                Log.d("MainActivity", "✅ IMEI validated successfully")
                result.success(imei)
            } else {
                Log.e("MainActivity", "❌ IMEI validation failed - no IMEI available")
                result.error("IMEI_VALIDATION_FAILED", "No valid IMEI available", null)
            }
            
        } catch (e: Exception) {
            Log.e("MainActivity", "❌ Error validating IMEI", e)
            result.error("VALIDATION_ERROR", "Failed to validate IMEI: ${e.message}", null)
        }
    }

    private fun getImeiDebugInfo(result: MethodChannel.Result) {
        try {
            val imeiManager = ImeiManager.getInstance(this)
            val info = imeiManager.getImeiInfo()
            
            Log.d("MainActivity", "IMEI Debug Info: $info")
            result.success(info)
            
        } catch (e: Exception) {
            Log.e("MainActivity", "Error getting IMEI info", e)
            result.error("INFO_ERROR", "Failed to get IMEI info", e.message)
        }
    }

    private fun forceRefreshImeiOnly(result: MethodChannel.Result) {
        Log.d("MainActivity", "=== FORCE REFRESHING IMEI ONLY ===")
        
        try {
            val imeiManager = ImeiManager.getInstance(this)
            val refreshedImei = imeiManager.forceRefreshImei()
            
            if (refreshedImei != "unknown" && refreshedImei.isNotEmpty()) {
                Log.d("MainActivity", "✅ Force refresh successful")
                result.success(refreshedImei)
            } else {
                Log.e("MainActivity", "❌ Force refresh failed - no IMEI available")
                result.error("REFRESH_FAILED", "Failed to refresh IMEI - no IMEI available", null)
            }
            
        } catch (e: Exception) {
            Log.e("MainActivity", "❌ Error force refreshing IMEI", e)
            result.error("REFRESH_ERROR", "Failed to force refresh IMEI: ${e.message}", null)
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        
        // Check each permission
        for (permission in REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) 
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(permission)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            Log.d("MainActivity", "Requesting permissions: ${permissionsToRequest.joinToString()}")
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        } else {
            Log.d("MainActivity", "All permissions already granted")
            // All permissions granted, check background location for Android 10+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                checkBackgroundLocationPermission()
            }
        }
    }

    private fun checkBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) 
                != PackageManager.PERMISSION_GRANTED) {
                
                // Send message to Flutter instead of showing AlertDialog
                flutterEngine?.dartExecutor?.binaryMessenger?.let { messenger ->
                    MethodChannel(messenger, DEVICE_CHANNEL).invokeMethod("onBackgroundLocationRequired", null)
                }
                
                // Request permission directly
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                    BACKGROUND_LOCATION_REQUEST_CODE
                )
            }
        }
    }

    private fun requestBatteryOptimizationDisable(result: MethodChannel.Result) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                try {
                    startActivityForResult(intent, BATTERY_OPTIMIZATION_REQUEST_CODE)
                    result.success(true)
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error requesting battery optimization disable", e)
                    result.error("BATTERY_OPTIMIZATION_ERROR", "Failed to request battery optimization disable", e.message)
                }
            } else {
                result.success(true) // Already disabled
            }
        } else {
            result.success(true) // Not needed for older versions
        }
    }

    private fun isBatteryOptimizationDisabled(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.isIgnoringBatteryOptimizations(packageName)
        } else {
            true // Not applicable for older versions
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        when (requestCode) {
            PERMISSION_REQUEST_CODE -> {
                var allGranted = true
                var phoneStateGranted = false
                
                for (i in permissions.indices) {
                    if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                        allGranted = false
                        Log.w("MainActivity", "Permission denied: ${permissions[i]}")
                    } else {
                        if (permissions[i] == Manifest.permission.READ_PHONE_STATE) {
                            phoneStateGranted = true
                        }
                    }
                }
                
                if (phoneStateGranted) {
                    Log.d("MainActivity", "✅ READ_PHONE_STATE permission granted - can attempt IMEI access")
                } else {
                    Log.e("MainActivity", "❌ READ_PHONE_STATE permission denied - IMEI will not be available")
                }
                
                if (allGranted) {
                    // Check background location for Android 10+
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        checkBackgroundLocationPermission()
                    }
                    
                    flutterEngine?.dartExecutor?.binaryMessenger?.let { messenger ->
                        MethodChannel(messenger, DEVICE_CHANNEL).invokeMethod("onPermissionGranted", null)
                    }
                } else {
                    // Send message to Flutter to handle permission denial
                    flutterEngine?.dartExecutor?.binaryMessenger?.let { messenger ->
                        MethodChannel(messenger, DEVICE_CHANNEL).invokeMethod("onPermissionDenied", null)
                    }
                }
                
                // Always try to collect IMEI after permission result (will fail gracefully if no permission)
                ensureImeiIsCollectedOnly()
            }
            
            BACKGROUND_LOCATION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d("MainActivity", "Background location permission granted")
                    flutterEngine?.dartExecutor?.binaryMessenger?.let { messenger ->
                        MethodChannel(messenger, DEVICE_CHANNEL).invokeMethod("onBackgroundLocationGranted", null)
                    }
                } else {
                    Log.w("MainActivity", "Background location permission denied")
                    flutterEngine?.dartExecutor?.binaryMessenger?.let { messenger ->
                        MethodChannel(messenger, DEVICE_CHANNEL).invokeMethod("onBackgroundLocationDenied", null)
                    }
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        when (requestCode) {
            BATTERY_OPTIMIZATION_REQUEST_CODE -> {
                if (isBatteryOptimizationDisabled()) {
                    Log.d("MainActivity", "Battery optimization disabled successfully")
                    flutterEngine?.dartExecutor?.binaryMessenger?.let { messenger ->
                        MethodChannel(messenger, DEVICE_CHANNEL).invokeMethod("onBatteryOptimizationDisabled", null)
                    }
                } else {
                    Log.w("MainActivity", "Battery optimization still enabled")
                    flutterEngine?.dartExecutor?.binaryMessenger?.let { messenger ->
                        MethodChannel(messenger, DEVICE_CHANNEL).invokeMethod("onBatteryOptimizationStillEnabled", null)
                    }
                }
            }
        }
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("MainActivity", "MainActivity created")

        checkAndRequestPermissions()
        handleIntent(intent)
        
        // Try to collect IMEI immediately (will fail gracefully if no permission)
        ensureImeiIsCollectedOnly()
        
        // Start the background service immediately
        try {
            val serviceIntent = Intent(this, BackgroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Log.d("MainActivity", "Background service started")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error starting background service", e)
        }
        
        // Check battery optimization status and notify Flutter
        if (!isBatteryOptimizationDisabled()) {
            Log.w("MainActivity", "Battery optimization is enabled - app may be killed")
        }
    }

    // IMEI ONLY - NO FALLBACKS OR EMERGENCY IDs
    private fun ensureImeiIsCollectedOnly() {
        Log.d("MainActivity", "=== ENSURING IMEI IS COLLECTED (IMEI ONLY) ===")
        
        try {
            val imeiManager = ImeiManager.getInstance(this)
            val imei = imeiManager.getDeviceIdentifier()
            
            if (imei != "unknown" && imei.isNotEmpty()) {
                Log.d("MainActivity", "✅ IMEI collected successfully")
                
                // Notify Flutter about the IMEI
                flutterEngine?.dartExecutor?.binaryMessenger?.let { messenger ->
                    MethodChannel(messenger, DEVICE_CHANNEL).invokeMethod("onImeiCollected", mapOf(
                        "imei" to imei,
                        "timestamp" to System.currentTimeMillis(),
                        "success" to true
                    ))
                }
            } else {
                Log.e("MainActivity", "❌ IMEI not available")
                
                // Notify Flutter that IMEI is not available
                flutterEngine?.dartExecutor?.binaryMessenger?.let { messenger ->
                    MethodChannel(messenger, DEVICE_CHANNEL).invokeMethod("onImeiCollected", mapOf(
                        "imei" to null,
                        "timestamp" to System.currentTimeMillis(),
                        "success" to false,
                        "error" to "IMEI not available - check permissions and device support"
                    ))
                }
            }
            
        } catch (e: Exception) {
            Log.e("MainActivity", "❌ Critical error collecting IMEI", e)
            
            // Notify Flutter about the error
            flutterEngine?.dartExecutor?.binaryMessenger?.let { messenger ->
                MethodChannel(messenger, DEVICE_CHANNEL).invokeMethod("onImeiCollected", mapOf(
                    "imei" to null,
                    "timestamp" to System.currentTimeMillis(),
                    "success" to false,
                    "error" to "Error collecting IMEI: ${e.message}"
                ))
            }
        }
    }

    private fun handleIntent(intent: Intent) {
        autoStartTracking = intent.getBooleanExtra(IgnitionReceiver.EXTRA_AUTO_START, false)
        startedBy = intent.getStringExtra(IgnitionReceiver.EXTRA_STARTED_BY) ?: ""
        
        if (autoStartTracking) {
            Log.d("MainActivity", "App started by ignition: $startedBy")
        }
    }
}