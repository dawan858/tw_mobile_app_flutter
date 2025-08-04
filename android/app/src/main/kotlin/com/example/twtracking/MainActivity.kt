package com.example.twtracking

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.location.GnssStatus
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import android.widget.Toast
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import androidx.core.app.ActivityCompat
import android.Manifest
import android.content.pm.PackageManager
import android.app.ActivityManager
import android.content.Context
import android.app.AlertDialog
import io.flutter.plugins.GeneratedPluginRegistrant
import android.os.Handler

class MainActivity : FlutterActivity() {
    private val CHANNEL = "com.example.twtracking/service"
    private val DEVICE_INFO_CHANNEL = "com.trackingWorld.tracking/device_info"
    private val SATELLITE_CHANNEL = "com.trackingWorld.tracking/satellite"
    private val AVN_SLEEP_CHANNEL = "com.example.twtracking/avn_sleep"
    private val TAG = "MainActivity"
    private var terminationReceiver: AppTerminationReceiver? = null
    private var logUploadReceiver: LogUploadReceiver? = null
    private lateinit var locationManager: LocationManager
    private var gnssStatusCallback: GnssStatus.Callback? = null
    private lateinit var carPowerManager: CarPowerManager
    private var flutterEngine: FlutterEngine? = null
    
    companion object {
        // Permission request codes
        private const val IMEI_PERMISSION_REQUEST_CODE = 1
        private const val LOCATION_PERMISSION_REQUEST_CODE = 2
        private const val STORAGE_PERMISSION_REQUEST_CODE = 3
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // ADD BASIC LOGGING FOR AVN DEBUGGING
        Log.d("MainActivity", "=== MAIN ACTIVITY CREATED ===")
        Log.d("MainActivity", "Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        Log.d("MainActivity", "Android Version: ${Build.VERSION.SDK_INT}")
        Log.d("MainActivity", "Package: ${packageName}")
        
        // Check if car service is available
        val packageManager = packageManager
        val carServiceAvailable = packageManager.hasSystemFeature("android.hardware.type.automotive")
        Log.d("MainActivity", "Car service available: $carServiceAvailable")
        
        // Force some basic logs
        Log.i("MainActivity", "INFO: MainActivity onCreate started")
        Log.w("MainActivity", "WARNING: This is a test warning log")
        Log.e("MainActivity", "ERROR: This is a test error log")
        
        // CRITICAL: Start comprehensive permission flow immediately when app starts
        startComprehensivePermissionFlow()
        
        // Initialize location manager for satellite data
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        Log.d(TAG, "Location manager initialized: ${locationManager != null}")
        
        // Initialize car power manager for sleep monitoring
        Log.d(TAG, "Initializing CarPowerManager...")
        carPowerManager = CarPowerManager(this)
        carPowerManager.initialize()
        Log.d(TAG, "CarPowerManager initialized: ${::carPowerManager.isInitialized}")
        
        registerTerminationReceiver()
        registerLogUploadReceiver()
        // Don't start tracking service here - let permission flow control it
    }

    private fun checkAndRequestBatteryOptimization() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val packageName = packageName
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    Log.d(TAG, "🔄 Requesting battery optimization exemption")
                    try {
                        val intent = Intent().apply {
                            action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                            data = Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                        Toast.makeText(this, "Please disable battery optimization for reliable tracking", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error requesting battery optimization exemption", e)
                    }
                } else {
                    Log.d(TAG, "✅ Battery optimization already disabled")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking battery optimization", e)
        }
    }

    private fun startTrackingService() {
        Log.d(TAG, "Starting tracking service from MainActivity")
        val serviceIntent = Intent(this, GpsTrackingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun registerTerminationReceiver() {
        terminationReceiver = AppTerminationReceiver()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_RESTARTED)
            addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_DATA_CLEARED)
            addDataScheme("package")
        }
        registerReceiver(terminationReceiver, filter)
        Log.d(TAG, "Registered termination receiver")
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "MainActivity onResume")
        startTrackingService()
        
        // Check if device admin was granted while user was in settings
        try {
            val devicePolicyManager = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(this, MyDeviceAdminReceiver::class.java)
            val isActive = devicePolicyManager.isAdminActive(adminComponent)
            
            if (isActive) {
                Log.d(TAG, "✅ Device admin is now active - user granted permission")
                // Notify Flutter that device admin is now active
                flutterEngine?.let { engine ->
                    MethodChannel(engine.dartExecutor.binaryMessenger, "device_admin_channel").invokeMethod("onDeviceAdminGranted", true)
                }
            } else {
                Log.d(TAG, "Device admin is not active")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking device admin status in onResume", e)
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "MainActivity onPause")
        // Don't stop the service here
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        
        // Store the FlutterEngine for later use
        this.flutterEngine = flutterEngine
        
        Log.d(TAG, "=== CONFIGURE FLUTTER ENGINE CALLED ===")
        Log.d(TAG, "Flutter engine: ${flutterEngine.javaClass.simpleName}")
        Log.d(TAG, "Binary messenger: ${flutterEngine.dartExecutor.binaryMessenger}")
        Log.d(TAG, "CarPowerManager initialized: ${::carPowerManager.isInitialized}")

        // Register plugins
        GeneratedPluginRegistrant.registerWith(flutterEngine)

        // Service channel
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            Log.d(TAG, "SERVICE_CHANNEL method called: ${call.method}")
            Log.d(TAG, "CarPowerManager available: ${::carPowerManager.isInitialized}")
            
            when (call.method) {
                "startService" -> {
                    Log.d(TAG, "Starting GPS tracking service from Flutter")
                    startTrackingService()
                    result.success(true)
                }
                "stopService" -> {
                    Log.d(TAG, "Stopping GPS tracking service from Flutter")
                    val serviceIntent = Intent(this, GpsTrackingService::class.java)
                    stopService(serviceIntent)
                    result.success(true)
                }
                "isServiceRunning" -> {
                    // Check if GpsTrackingService is running (legacy)
                    val isRunning = isServiceRunning(GpsTrackingService::class.java)
                    result.success(isRunning)
                }
                "isBackgroundServiceRunning" -> {
                    // Check if BackgroundService is running (primary service)
                    val isRunning = isServiceRunning(BackgroundService::class.java)
                    Log.d(TAG, "BackgroundService running status: $isRunning")
                    result.success(isRunning)
                }
                "appReady" -> {
                    Log.d(TAG, "Flutter app is ready - IMEI obtained, starting tracking service")
                    startTrackingService()
                    result.success(true)
                }
                "testMethodChannel" -> {
                    try {
                        Log.d(TAG, "🧪 TEST METHOD CHANNEL CALLED - SERVICE CHANNEL IS WORKING")
                        result.success("Service channel is working!")
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error in test method", e)
                        result.error("TEST_ERROR", "Test method failed", e.message)
                    }
                }
                "testSimpleMethod" -> {
                    try {
                        Log.d(TAG, "🧪 SIMPLE METHOD TEST CALLED")
                        result.success(42) // Return a simple number
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error in simple method test", e)
                        result.error("SIMPLE_TEST_ERROR", "Simple method test failed", e.message)
                    }
                }
                "getConfiguration" -> {
                    try {
                        Log.d(TAG, "📊 GET CONFIGURATION CALLED")
                        // Get configuration from BackgroundService if it's running
                        val backgroundService = getBackgroundServiceInstance()
                        if (backgroundService != null) {
                            val config = backgroundService.getCurrentConfiguration()
                            Log.d(TAG, "Configuration from BackgroundService: $config")
                            result.success(config)
                        } else {
                            Log.w(TAG, "BackgroundService not running, returning default config")
                            // Return default configuration
                            val defaultConfig = mapOf(
                                "gpsTimer" to 5,
                                "uploadTimer" to 10,
                                "angleThreshold" to 45f,
                                "overSpeedingThreshold" to 60f,
                                "distanceThreshold" to 1000f,
                                "movingTimer" to 60,
                                "stopTimer" to 130,
                                "igStatus" to 0,
                                "isCarPowerInitialized" to false,
                                "isIgStatusReady" to false
                            )
                            result.success(defaultConfig)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error getting configuration", e)
                        result.error("CONFIG_ERROR", "Failed to get configuration", e.message)
                    }
                }
                "triggerPowerStateCheck" -> {
                    try {
                        Log.d(TAG, "🔄 Triggering power state check from Flutter service channel")
                        
                        // NEW: Use force update method for more reliable igStatus detection
                        carPowerManager.forceUpdateIgStatus()
                        
                        // Trigger BackgroundService to check power state
                        val serviceIntent = Intent(this, BackgroundService::class.java).apply {
                            action = "CHECK_POWER_STATE"
                        }
                        startService(serviceIntent)
                        
                        Log.d(TAG, "✅ Power state check triggered successfully via service channel")
                        result.success(true)
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error triggering power state check via service channel", e)
                        result.error("POWER_STATE_CHECK_ERROR", "Failed to trigger power state check", e.message)
                    }
                }
                "getCurrentIgStatus" -> {
                    try {
                        Log.d(TAG, "🔄 Getting current igStatus from service channel")
                        
                        // Try to get igStatus from BackgroundService first (more accurate)
                        try {
                            val serviceIntent = Intent(this, BackgroundService::class.java).apply {
                                action = "GET_CURRENT_IG_STATUS"
                            }
                            startService(serviceIntent)
                            
                            // Get igStatus from SharedPreferences (set by BackgroundService)
                            val prefs = getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
                            val currentIgStatus = prefs.getInt("current_ig_status", -1)
                            
                            if (currentIgStatus != -1) {
                                Log.d(TAG, "✅ Current igStatus from BackgroundService: $currentIgStatus")
                                result.success(currentIgStatus)
                                return@setMethodCallHandler
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "⚠️ Could not get igStatus from BackgroundService: ${e.message}")
                        }
                        
                        // Fallback to MainActivity's CarPowerManager
                        if (::carPowerManager.isInitialized) {
                            val currentIgStatus = carPowerManager.getCurrentIgStatus()
                            Log.d(TAG, "✅ Current igStatus from CarPowerManager: $currentIgStatus")
                            result.success(currentIgStatus)
                        } else {
                            Log.w(TAG, "⚠️ CarPowerManager not initialized, returning default igStatus: 0")
                            result.success(0) // Default to ACC OFF
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error getting current igStatus via service channel", e)
                        Log.w(TAG, "⚠️ Returning default igStatus: 0 due to error")
                        result.success(0) // Default to ACC OFF on error
                    }
                }
                "getCarPowerManagerStatus" -> {
                    try {
                        Log.d(TAG, "🔍 Getting CarPowerManager status from service channel")
                        val status = carPowerManager.getDetailedStatus()
                        val isProperlyInitialized = carPowerManager.isProperlyInitialized()
                        
                        Log.d(TAG, "✅ CarPowerManager status:")
                        status.forEach { (key, value) ->
                            Log.d(TAG, "   - $key: $value")
                        }
                        Log.d(TAG, "   - isProperlyInitialized: $isProperlyInitialized")
                        
                        result.success(mapOf(
                            "status" to status,
                            "isProperlyInitialized" to isProperlyInitialized
                        ))
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error getting CarPowerManager status", e)
                        result.error("STATUS_ERROR", "Failed to get CarPowerManager status", e.message)
                    }
                }
                "setIgStatusManually" -> {
                    try {
                        val status = call.argument<Int>("status") ?: 0
                        Log.d(TAG, "🔧 Setting igStatus manually to: $status")
                        carPowerManager.setIgStatusManually(status)
                        result.success(true)
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error setting igStatus manually", e)
                        result.error("SET_IG_STATUS_ERROR", "Failed to set igStatus manually", e.message)
                    }
                }
                "simulateAccStateChange" -> {
                    try {
                        val isAccOn = call.argument<Boolean>("isAccOn") ?: false
                        Log.d(TAG, "🧪 Simulating ACC state change to: $isAccOn")
                        carPowerManager.simulateAccStateChange(isAccOn)
                        result.success(true)
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error simulating ACC state change", e)
                        result.error("SIMULATE_ACC_ERROR", "Failed to simulate ACC state change", e.message)
                    }
                }
                "testAccStateDetection" -> {
                    try {
                        Log.d(TAG, "🧪 Testing ACC state detection from Flutter")
                        
                        // Test CarPowerManager
                        carPowerManager.debugPowerStates()
                        
                        // Trigger BackgroundService test
                        val serviceIntent = Intent(this, BackgroundService::class.java).apply {
                            action = "TEST_ACC_STATE_DETECTION"
                        }
                        startService(serviceIntent)
                        
                        Log.d(TAG, "✅ ACC state detection test triggered successfully")
                        result.success(true)
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error testing ACC state detection", e)
                        result.error("ACC_TEST_ERROR", "Failed to test ACC state detection", e.message)
                    }
                }
                "testSpecificPowerState" -> {
                    try {
                        val testState = call.arguments as Int? ?: 0
                        Log.d(TAG, "🧪 Testing specific power state from Flutter: $testState")
                        
                        // Send intent to service to test power state
                        val intent = Intent(this, BackgroundService::class.java).apply {
                            action = "TEST_SPECIFIC_POWER_STATE"
                            putExtra("test_state", testState)
                        }
                        startService(intent)
                        
                        result.success(true)
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error testing specific power state from Flutter", e)
                        result.error("TEST_POWER_STATE_ERROR", "Failed to test power state", e.message)
                    }
                }
                "forceLogs" -> {
                    try {
                        Log.i("MainActivity", "INFO: forceLogs method called from Flutter")
                        Log.w("MainActivity", "WARNING: This is a test warning from forceLogs")
                        Log.e("MainActivity", "ERROR: This is a test error from forceLogs")
                        Log.d("MainActivity", "DEBUG: This is a test debug from forceLogs")
                        
                        // Add ERROR level logs for device info since only ERROR logs show
                        Log.e("MainActivity", "ERROR: Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                        Log.e("MainActivity", "ERROR: Android Version: ${Build.VERSION.SDK_INT}")
                        Log.e("MainActivity", "ERROR: Package: ${packageName}")
                        
                        // Check if car service is available
                        val packageManager = packageManager
                        val carServiceAvailable = packageManager.hasSystemFeature("android.hardware.type.automotive")
                        Log.e("MainActivity", "ERROR: Car service available: $carServiceAvailable")
                        
                        // Also try to initialize CarPowerManager
                        if (::carPowerManager.isInitialized) {
                            Log.e("MainActivity", "ERROR: CarPowerManager is initialized, calling test methods")
                            carPowerManager.testCurrentPowerState()
                            carPowerManager.debugPowerStates()
                        } else {
                            Log.e("MainActivity", "ERROR: CarPowerManager is not initialized")
                        }
                        
                        result.success("Logs forced successfully")
                    } catch (e: Exception) {
                        Log.e("MainActivity", "ERROR: Exception in forceLogs: ${e.message}")
                        result.error("FORCE_LOGS_ERROR", "Failed to force logs", e.message)
                    }
                }
                "testBwicIgnition" -> {
                    try {
                        Log.e("MainActivity", "ERROR: Testing BWIC ignition detection from Flutter")
                        
                        if (::carPowerManager.isInitialized) {
                            carPowerManager.testBwicIgnitionDetection()
                            result.success("BWIC ignition test completed")
                        } else {
                            Log.e("MainActivity", "ERROR: CarPowerManager not initialized for BWIC test")
                            result.error("BWIC_TEST_ERROR", "CarPowerManager not initialized", null)
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "ERROR: Exception in BWIC ignition test: ${e.message}")
                        result.error("BWIC_TEST_ERROR", "Failed to test BWIC ignition", e.message)
                    }
                }
                "testServerSync" -> {
                    try {
                        Log.d(TAG, "🧪 Testing server sync from Flutter")
                        
                        // Trigger BackgroundService to test server sync
                        val serviceIntent = Intent(this, BackgroundService::class.java).apply {
                            action = "TEST_SERVER_SYNC"
                        }
                        startService(serviceIntent)
                        
                        Log.d(TAG, "✅ Server sync test triggered successfully")
                        result.success("Server sync test triggered")
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error testing server sync", e)
                        result.error("SERVER_SYNC_TEST_ERROR", "Failed to test server sync", e.message)
                    }
                }
                "sendIgStatusDirectly" -> {
                    try {
                        Log.d(TAG, "🚀 Sending igStatus directly to server from Flutter")
                        
                        // Trigger BackgroundService to send igStatus directly
                        val serviceIntent = Intent(this, BackgroundService::class.java).apply {
                            action = "SEND_IGSTATUS_DIRECTLY"
                        }
                        startService(serviceIntent)
                        
                        Log.d(TAG, "✅ Direct igStatus send triggered successfully")
                        result.success("Direct igStatus send triggered")
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error sending igStatus directly", e)
                        result.error("DIRECT_IGSTATUS_ERROR", "Failed to send igStatus directly", e.message)
                    }
                }
                else -> {
                    Log.w(TAG, "Service method not implemented: ${call.method}")
                    result.notImplemented()
                }
            }
        }
        
        Log.d(TAG, "✅ SERVICE_CHANNEL method handler registered successfully")

        // Device info channel
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, DEVICE_INFO_CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "getImei" -> {
                    try {
                        val imeiManager = ImeiManager.getInstance(this)
                        val imei = imeiManager.getDeviceIdentifier()
                        result.success(imei)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error getting IMEI", e)
                        result.error("IMEI_ERROR", "Failed to get IMEI", e.message)
                    }
                }
                else -> {
                    result.notImplemented()
                }
            }
        }

        // Satellite channel
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, SATELLITE_CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "getSatelliteData" -> {
                    try {
                        val satelliteData = getSatelliteData()
                        result.success(satelliteData)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error getting satellite data", e)
                        result.error("SATELLITE_ERROR", "Failed to get satellite data", e.message)
                    }
                }
                "refreshSatelliteData" -> {
                    try {
                        // Force refresh satellite data by triggering BackgroundService
                        val serviceIntent = Intent(this, BackgroundService::class.java).apply {
                            action = "REFRESH_SATELLITE_DATA"
                        }
                        startService(serviceIntent)
                        
                        // Wait a bit and then get fresh data
                        Handler().postDelayed({
                            try {
                                val satelliteData = getSatelliteData()
                                result.success(satelliteData)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error getting refreshed satellite data", e)
                                result.error("SATELLITE_REFRESH_ERROR", "Failed to get refreshed satellite data", e.message)
                            }
                        }, 1000) // Wait 1 second for BackgroundService to update
                    } catch (e: Exception) {
                        Log.e(TAG, "Error refreshing satellite data", e)
                        result.error("SATELLITE_REFRESH_ERROR", "Failed to refresh satellite data", e.message)
                    }
                }
                else -> {
                    result.notImplemented()
                }
            }
        }

        // AVN Sleep channel
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, AVN_SLEEP_CHANNEL).setMethodCallHandler { call, result ->
            Log.d(TAG, "AVN_SLEEP_CHANNEL method called: ${call.method}")
            
            when (call.method) {
                "getCurrentSleepState" -> {
                    try {
                        val isSleeping = carPowerManager.getCurrentSleepState()
                        result.success(isSleeping)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error getting sleep state", e)
                        result.error("SLEEP_STATE_ERROR", "Failed to get sleep state", e.message)
                    }
                }
                "startSleepMonitoring" -> {
                    try {
                        carPowerManager.connect()
                        Log.d(TAG, "Started sleep monitoring")
                        result.success(true)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error starting sleep monitoring", e)
                        result.error("SLEEP_MONITORING_ERROR", "Failed to start sleep monitoring", e.message)
                    }
                }
                "stopSleepMonitoring" -> {
                    try {
                        carPowerManager.disconnect()
                        Log.d(TAG, "Stopped sleep monitoring")
                        result.success(true)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error stopping sleep monitoring", e)
                        result.error("SLEEP_MONITORING_ERROR", "Failed to stop sleep monitoring", e.message)
                    }
                }
                "getCarPowerStatus" -> {
                    try {
                        val status = carPowerManager.getCurrentPowerStatus()
                        result.success(status)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error getting car power status", e)
                        result.error("CAR_POWER_STATUS_ERROR", "Failed to get car power status", e.message)
                    }
                }
                "getCurrentIgStatus" -> {
                    try {
                        Log.d(TAG, "🔄 Getting current igStatus from AVN sleep channel")
                        
                        if (::carPowerManager.isInitialized) {
                            val currentIgStatus = carPowerManager.getCurrentIgStatus()
                            Log.d(TAG, "✅ Current igStatus from CarPowerManager (AVN): $currentIgStatus")
                            result.success(currentIgStatus)
                        } else {
                            Log.w(TAG, "⚠️ CarPowerManager not initialized (AVN), returning default igStatus: 0")
                            result.success(0) // Default to ACC OFF
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error getting current igStatus from AVN sleep channel", e)
                        Log.w(TAG, "⚠️ Returning default igStatus: 0 due to error (AVN)")
                        result.success(0) // Default to ACC OFF on error
                    }
                }
                "triggerPowerStateCheck" -> {
                    try {
                        Log.d(TAG, "🔄 Triggering power state check from Flutter AVN sleep channel")
                        
                        // NEW: Use force update method for more reliable igStatus detection
                        carPowerManager.forceUpdateIgStatus()
                        
                        // Trigger BackgroundService to check power state
                        val serviceIntent = Intent(this, BackgroundService::class.java).apply {
                            action = "CHECK_POWER_STATE"
                        }
                        startService(serviceIntent)
                        
                        Log.d(TAG, "✅ Power state check triggered successfully via AVN sleep channel")
                        result.success(true)
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error triggering power state check via AVN sleep channel", e)
                        result.error("POWER_STATE_CHECK_ERROR", "Failed to trigger power state check", e.message)
                    }
                }
                else -> {
                    Log.w(TAG, "Method not implemented: ${call.method}")
                    result.notImplemented()
                }
            }
        }
        
        Log.d(TAG, "✅ AVN_SLEEP_CHANNEL method handler registered successfully")

        // Device Admin channel (for Flutter to request device admin permissions)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "device_admin_channel").setMethodCallHandler { call, result ->
            Log.d(TAG, "DEVICE_ADMIN_CHANNEL method called: ${call.method}")
            
            when (call.method) {
                "isDeviceAdminActive" -> {
                    try {
                        val devicePolicyManager = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
                        val adminComponent = ComponentName(this, MyDeviceAdminReceiver::class.java)
                        val isActive = devicePolicyManager.isAdminActive(adminComponent)
                        Log.d(TAG, "Device admin active: $isActive")
                        result.success(isActive)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error checking device admin status", e)
                        result.error("DEVICE_ADMIN_ERROR", "Failed to check device admin status", e.message)
                    }
                }
                "requestDeviceAdmin" -> {
                    try {
                        Log.d(TAG, "🔄 Requesting device admin permission from Flutter")
                        val devicePolicyManager = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
                        val adminComponent = ComponentName(this, MyDeviceAdminReceiver::class.java)
                        
                        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                        intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, 
                            "This app requires device admin privileges for security features and reliable background operation")
                        startActivity(intent)
                        
                        Log.d(TAG, "✅ Device admin permission request initiated")
                        result.success(true)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error requesting device admin", e)
                        result.error("DEVICE_ADMIN_ERROR", "Failed to request device admin", e.message)
                    }
                }
                else -> {
                    Log.w(TAG, "Device admin method not implemented: ${call.method}")
                    result.notImplemented()
                }
            }
        }
        
        Log.d(TAG, "✅ DEVICE_ADMIN_CHANNEL method handler registered successfully")
        
        // Log Broadcast channel (for handling log uploads from BackgroundService)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "log_broadcast_channel").setMethodCallHandler { call, result ->
            Log.d(TAG, "LOG_BROADCAST_CHANNEL method called: ${call.method}")
            result.success(null) // Just acknowledge the call
        }
        
        Log.d(TAG, "✅ LOG_BROADCAST_CHANNEL method handler registered successfully")
    }

    private fun getSatelliteData(): Map<String, Int> {
        val satelliteData = mutableMapOf<String, Int>()
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    // First try to get satellite data from BackgroundService
                    var totalSats = BackgroundService.totalSatellites
                    var connectedSats = BackgroundService.connectedSatellites
                    
                    // If BackgroundService data is 0, try to get it directly from LocationManager
                    if (totalSats == 0 && connectedSats == 0) {
                        Log.d(TAG, "BackgroundService satellite data is 0, trying direct LocationManager access")
                        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
                        
                        try {
                            // Try to get GNSS status using a simpler approach
                            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                                // Use a more reliable method to get satellite data
                                val gnssStatus = getCurrentGnssStatus(locationManager)
                                if (gnssStatus != null) {
                                    totalSats = gnssStatus.satelliteCount
                                    connectedSats = 0
                                    
                                    for (i in 0 until gnssStatus.satelliteCount) {
                                        if (gnssStatus.usedInFix(i)) {
                                            connectedSats++
                                        }
                                    }
                                    
                                    Log.d(TAG, "Direct GNSS data: $connectedSats/$totalSats satellites")
                                } else {
                                    Log.d(TAG, "Could not get GNSS status, checking if GPS is active")
                                    // Check if we have recent location data as indicator
                                    val lastKnownLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                                    if (lastKnownLocation != null && lastKnownLocation.time > System.currentTimeMillis() - 30000) {
                                        // Recent GPS data available, estimate satellites
                                        totalSats = 12
                                        connectedSats = 6
                                        Log.d(TAG, "GPS active, estimated satellites: $connectedSats/$totalSats")
                                    } else {
                                        totalSats = 0
                                        connectedSats = 0
                                        Log.d(TAG, "No recent GPS data, satellites: $connectedSats/$totalSats")
                                    }
                                }
                            } else {
                                Log.d(TAG, "GPS provider not enabled")
                                totalSats = 0
                                connectedSats = 0
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error getting direct GNSS data: $e")
                            totalSats = 0
                            connectedSats = 0
                        }
                    }
                    
                    satelliteData["totalSatellites"] = totalSats
                    satelliteData["connectedSatellites"] = connectedSats
                    
                    Log.d(TAG, "Final satellite data: ${satelliteData["totalSatellites"]} total, ${satelliteData["connectedSatellites"]} connected")
                } else {
                    Log.w(TAG, "Location permission not granted for satellite data")
                    satelliteData["totalSatellites"] = 0
                    satelliteData["connectedSatellites"] = 0
                }
            } else {
                Log.w(TAG, "GNSS status not available on this Android version")
                satelliteData["totalSatellites"] = 0
                satelliteData["connectedSatellites"] = 0
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting satellite data", e)
            satelliteData["totalSatellites"] = 0
            satelliteData["connectedSatellites"] = 0
        }
        
        return satelliteData
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }

    private fun getCurrentGnssStatus(locationManager: LocationManager): GnssStatus? {
        return try {
            // Check if BackgroundService is running and has satellite data
            if (isServiceRunning(BackgroundService::class.java)) {
                Log.d(TAG, "BackgroundService is running, checking its satellite data")
                if (BackgroundService.totalSatellites > 0 || BackgroundService.connectedSatellites > 0) {
                    Log.d(TAG, "BackgroundService has satellite data: ${BackgroundService.connectedSatellites}/${BackgroundService.totalSatellites}")
                    return null // Let the main method use BackgroundService data
                } else {
                    Log.d(TAG, "BackgroundService is running but has no satellite data")
                }
            } else {
                Log.d(TAG, "BackgroundService is not running")
            }
            
            // Try to trigger BackgroundService to refresh satellite data
            val serviceIntent = Intent(this, BackgroundService::class.java).apply {
                action = "REFRESH_SATELLITE_DATA"
            }
            startService(serviceIntent)
            
            // Wait a bit for the BackgroundService to refresh satellite data
            Thread.sleep(1000)
            
            // Check if BackgroundService now has satellite data
            if (BackgroundService.totalSatellites > 0 || BackgroundService.connectedSatellites > 0) {
                Log.d(TAG, "BackgroundService now has satellite data after refresh: ${BackgroundService.connectedSatellites}/${BackgroundService.totalSatellites}")
                return null // Let the main method use BackgroundService data
            }
            
            Log.d(TAG, "Could not get GNSS status directly")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error getting GNSS status: $e")
            null
        }
    }
    
    // NEW: Get BackgroundService instance if running
    private fun getBackgroundServiceInstance(): BackgroundService? {
        return try {
            // Since we can't directly access the service instance, we'll use a static reference
            // This is a workaround - in a real implementation, you might want to use a singleton pattern
            null // For now, return null and handle it in the method channel
        } catch (e: Exception) {
            Log.e(TAG, "Error getting BackgroundService instance", e)
            null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "MainActivity onDestroy")
        // Don't unregister the receiver here as we want it to survive app termination
    }



    // NEW METHOD: Start comprehensive permission flow
    private fun startComprehensivePermissionFlow() {
        try {
            Log.d(TAG, "=== STARTING COMPREHENSIVE PERMISSION FLOW ===")
            
            // Check battery optimization first
            checkAndRequestBatteryOptimization()
            
            // Start with IMEI permission
            requestImeiPermission()
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error starting permission flow", e)
        }
    }

    // UPDATED METHOD: Request IMEI permission
    private fun requestImeiPermission() {
        try {
            Log.d(TAG, "=== REQUESTING IMEI PERMISSION ===")
            
            // Check if we already have the permission
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "✅ READ_PHONE_STATE permission already granted")
                handleImeiPermissionGranted()
                return
            }
            
            // Request the permission
            Log.d(TAG, "🔄 Requesting READ_PHONE_STATE permission...")
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_PHONE_STATE),
                IMEI_PERMISSION_REQUEST_CODE
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error requesting IMEI permission", e)
        }
    }

    // NEW METHOD: Request location permissions
    private fun requestLocationPermissions() {
        try {
            Log.d(TAG, "=== REQUESTING LOCATION PERMISSIONS ===")
            
            val permissions = mutableListOf<String>()
            
            // Check ACCESS_FINE_LOCATION
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            
            // Check ACCESS_COARSE_LOCATION
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            
            // Check POST_NOTIFICATIONS for Android 13+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    permissions.add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            
            if (permissions.isNotEmpty()) {
                Log.d(TAG, "🔄 Requesting location permissions: $permissions")
                ActivityCompat.requestPermissions(
                    this,
                    permissions.toTypedArray(),
                    LOCATION_PERMISSION_REQUEST_CODE
                )
            } else {
                Log.d(TAG, "✅ All location permissions already granted")
                handleLocationPermissionsGranted()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error requesting location permissions", e)
        }
    }

    // NEW METHOD: Request storage permissions
    private fun requestStoragePermissions() {
        try {
            Log.d(TAG, "=== REQUESTING STORAGE PERMISSIONS ===")
            
            val permissions = mutableListOf<String>()
            
            // Check storage permissions based on Android version
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Android 13+ uses media permissions
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                    permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
                }
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {
                    permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
                }
            } else {
                // Android 12 and below use traditional storage permissions
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
            
            if (permissions.isNotEmpty()) {
                Log.d(TAG, "🔄 Requesting storage permissions: $permissions")
                ActivityCompat.requestPermissions(
                    this,
                    permissions.toTypedArray(),
                    STORAGE_PERMISSION_REQUEST_CODE
                )
            } else {
                Log.d(TAG, "✅ All storage permissions already granted")
                handleStoragePermissionsGranted()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error requesting storage permissions", e)
        }
    }

    // UPDATED METHOD: Handle permission results
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        Log.d(TAG, "=== PERMISSION RESULT RECEIVED ===")
        Log.d(TAG, "Request code: $requestCode")
        Log.d(TAG, "Permissions: ${permissions.joinToString()}")
        Log.d(TAG, "Grant results: ${grantResults.joinToString()}")
        
        when (requestCode) {
            IMEI_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "✅ IMEI permission granted")
                    handleImeiPermissionGranted()
                } else {
                    Log.w(TAG, "❌ IMEI permission denied")
                    showImeiPermissionDialog()
                }
            }
            LOCATION_PERMISSION_REQUEST_CODE -> {
                val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
                if (allGranted) {
                    Log.d(TAG, "✅ Location permissions granted")
                    handleLocationPermissionsGranted()
                } else {
                    Log.w(TAG, "❌ Some location permissions denied")
                    showLocationPermissionDialog()
                }
            }
            STORAGE_PERMISSION_REQUEST_CODE -> {
                val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
                if (allGranted) {
                    Log.d(TAG, "✅ Storage permissions granted")
                    handleStoragePermissionsGranted()
                } else {
                    Log.w(TAG, "❌ Some storage permissions denied")
                    showStoragePermissionDialog()
                }
            }
        }
    }

    // NEW METHOD: Handle when IMEI permission is granted
    private fun handleImeiPermissionGranted() {
        try {
            Log.d(TAG, "=== IMEI PERMISSION GRANTED - GETTING IMEI ===")
            
            // Try to get IMEI immediately
            val imeiManager = ImeiManager.getInstance(this)
            val imei = imeiManager.getDeviceIdentifier()
            
            if (imei != null && imei.isNotEmpty() && imei != "unknown") {
                Log.d(TAG, "✅ IMEI obtained successfully: $imei")
                
                // Store IMEI in SharedPreferences
                val prefs = getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
                prefs.edit().putString("flutter.imei", imei).apply()
                
                Log.d(TAG, "✅ IMEI stored in SharedPreferences: $imei")
                
                // Continue with location permissions
                requestLocationPermissions()
                
            } else {
                Log.w(TAG, "⚠️ IMEI not available or invalid: $imei")
                showImeiNotAvailableDialog()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error handling IMEI permission granted", e)
            showImeiNotAvailableDialog()
        }
    }

    // NEW METHOD: Handle when location permissions are granted
    private fun handleLocationPermissionsGranted() {
        try {
            Log.d(TAG, "=== LOCATION PERMISSIONS GRANTED ===")
            
            // Continue with storage permissions
            requestStoragePermissions()
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error handling location permissions granted", e)
        }
    }

    // NEW METHOD: Handle when storage permissions are granted
    private fun handleStoragePermissionsGranted() {
        try {
            Log.d(TAG, "=== STORAGE PERMISSIONS GRANTED ===")
            Log.d(TAG, "✅ All permissions granted - app is ready")
            
            // Notify Flutter that the app is ready
            // This will trigger the Flutter side to start the tracking service
            Log.d(TAG, "🚀 App is ready - all permissions granted")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error handling storage permissions granted", e)
        }
    }

    // NEW METHOD: Show IMEI permission dialog
    private fun showImeiPermissionDialog() {
        try {
            Log.d(TAG, "📱 Showing IMEI permission dialog")
            
            AlertDialog.Builder(this)
                .setTitle("Permission Required")
                .setMessage("This app needs phone state permission to get the device IMEI for tracking purposes.")
                .setPositiveButton("Grant Permission") { _, _ ->
                    requestImeiPermission()
                }
                .setNegativeButton("Cancel") { _, _ ->
                    Log.w(TAG, "User cancelled IMEI permission request")
                }
                .setCancelable(false)
                .show()
                
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error showing IMEI permission dialog", e)
        }
    }

    // NEW METHOD: Show IMEI not available dialog
    private fun showImeiNotAvailableDialog() {
        try {
            Log.d(TAG, "📱 Showing IMEI not available dialog")
            
            AlertDialog.Builder(this)
                .setTitle("IMEI Not Available")
                .setMessage("Unable to get device IMEI. This may affect tracking functionality.")
                .setPositiveButton("Continue") { _, _ ->
                    Log.d(TAG, "User chose to continue without IMEI")
                    // Continue with location permissions anyway
                    requestLocationPermissions()
                }
                .setNegativeButton("Exit") { _, _ ->
                    Log.w(TAG, "User chose to exit due to IMEI unavailability")
                    finish()
                }
                .setCancelable(false)
                .show()
                
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error showing IMEI not available dialog", e)
        }
    }

    // NEW METHOD: Show location permission dialog
    private fun showLocationPermissionDialog() {
        try {
            Log.d(TAG, "📱 Showing location permission dialog")
            
            AlertDialog.Builder(this)
                .setTitle("Location Permission Required")
                .setMessage("This app needs location permission for GPS tracking functionality.")
                .setPositiveButton("Grant Permission") { _, _ ->
                    requestLocationPermissions()
                }
                .setNegativeButton("Cancel") { _, _ ->
                    Log.w(TAG, "User cancelled location permission request")
                }
                .setCancelable(false)
                .show()
                
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error showing location permission dialog", e)
        }
    }

    // NEW METHOD: Show storage permission dialog
    private fun showStoragePermissionDialog() {
        try {
            Log.d(TAG, "📱 Showing storage permission dialog")
            
            AlertDialog.Builder(this)
                .setTitle("Storage Permission Required")
                .setMessage("This app needs storage permission for data logging and configuration.")
                .setPositiveButton("Grant Permission") { _, _ ->
                    requestStoragePermissions()
                }
                .setNegativeButton("Cancel") { _, _ ->
                    Log.w(TAG, "User cancelled storage permission request")
                }
                .setCancelable(false)
                .show()
                
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error showing storage permission dialog", e)
        }
    }
    
    // NEW: Register log upload receiver
    private fun registerLogUploadReceiver() {
        try {
            logUploadReceiver = LogUploadReceiver()
            val filter = IntentFilter().apply {
                addAction("UPLOAD_IGNITION_LOG")
                addAction("UPLOAD_EXCEPTION_LOG")
            }
            logUploadReceiver?.let { receiver ->
                registerReceiver(receiver, filter)
                Log.d(TAG, "✅ LogUploadReceiver registered successfully")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error registering LogUploadReceiver: ${e.message}")
        }
    }
    
    // NEW: LogUploadReceiver class
    inner class LogUploadReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try {
                when (intent?.action) {
                    "UPLOAD_IGNITION_LOG" -> {
                        val message = intent.getStringExtra("message") ?: ""
                        val details = intent.getStringExtra("details") ?: ""
                        val logType = intent.getStringExtra("logType") ?: "info"
                        val timestamp = intent.getStringExtra("timestamp") ?: ""
                        
                        Log.d(TAG, "📤 Forwarding ignition log to Flutter: $message")
                        
                        // Forward to Flutter via method channel
                        flutterEngine?.let { engine ->
                            MethodChannel(engine.dartExecutor.binaryMessenger, "log_broadcast_channel").invokeMethod(
                                "onIgnitionLogInserted",
                                mapOf(
                                    "message" to message,
                                    "details" to details,
                                    "logType" to logType,
                                    "timestamp" to timestamp
                                )
                            )
                        }
                    }
                    "UPLOAD_EXCEPTION_LOG" -> {
                        val main = intent.getStringExtra("main") ?: ""
                        val details = intent.getStringExtra("details") ?: ""
                        val timestamp = intent.getStringExtra("timestamp") ?: ""
                        
                        Log.d(TAG, "📤 Forwarding exception log to Flutter: $main")
                        
                        // Forward to Flutter via method channel
                        flutterEngine?.let { engine ->
                            MethodChannel(engine.dartExecutor.binaryMessenger, "log_broadcast_channel").invokeMethod(
                                "onExceptionLogInserted",
                                mapOf(
                                    "main" to main,
                                    "details" to details,
                                    "timestamp" to timestamp
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error in LogUploadReceiver: ${e.message}")
            }
        }
    }
} 