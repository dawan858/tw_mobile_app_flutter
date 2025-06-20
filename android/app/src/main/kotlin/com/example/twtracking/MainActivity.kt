package com.example.twtracking

import android.app.admin.DevicePolicyManager
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

class MainActivity : FlutterActivity() {
    private val CHANNEL = "com.example.twtracking/service"
    private val DEVICE_INFO_CHANNEL = "com.trackingWorld.tracking/device_info"
    private val DEVICE_ADMIN_CHANNEL = "device_admin_channel"
    private val SATELLITE_CHANNEL = "com.trackingWorld.tracking/satellite"
    private val AVN_SLEEP_CHANNEL = "com.example.twtracking/avn_sleep"
    private val TAG = "MainActivity"
    private var terminationReceiver: AppTerminationReceiver? = null
    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    private lateinit var locationManager: LocationManager
    private var gnssStatusCallback: GnssStatus.Callback? = null
    private lateinit var carPowerManager: CarPowerManager
    
    companion object {
        // Permission request codes
        private const val IMEI_PERMISSION_REQUEST_CODE = 1
        private const val LOCATION_PERMISSION_REQUEST_CODE = 2
        private const val STORAGE_PERMISSION_REQUEST_CODE = 3
        private const val DEVICE_ADMIN_PERMISSION_REQUEST_CODE = 4
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "MainActivity onCreate")
        
        // CRITICAL: Start comprehensive permission flow immediately when app starts
        startComprehensivePermissionFlow()
        
        // Initialize device admin components
        devicePolicyManager = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, MyDeviceAdminReceiver::class.java)
        
        // Initialize location manager for satellite data
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        
        // Initialize car power manager for sleep monitoring
        carPowerManager = CarPowerManager(this)
        carPowerManager.initialize()
        
        registerTerminationReceiver()
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
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "MainActivity onPause")
        // Don't stop the service here
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // Service channel
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            Log.d(TAG, "SERVICE_CHANNEL method called: ${call.method}")
            
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
                    // Check if service is running
                    val isRunning = isServiceRunning(GpsTrackingService::class.java)
                    result.success(isRunning)
                }
                "appReady" -> {
                    Log.d(TAG, "Flutter app is ready - IMEI obtained, starting tracking service")
                    startTrackingService()
                    result.success(true)
                }
                "triggerPowerStateCheck" -> {
                    try {
                        Log.d(TAG, "🔄 Triggering power state check from Flutter service channel")
                        
                        // Test current power state
                        carPowerManager.testCurrentPowerState()
                        
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
                        val currentIgStatus = carPowerManager.getCurrentIgStatus()
                        Log.d(TAG, "✅ Current igStatus: $currentIgStatus")
                        result.success(currentIgStatus)
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error getting current igStatus via service channel", e)
                        result.error("GET_IG_STATUS_ERROR", "Failed to get current igStatus", e.message)
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

        // Device admin channel
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, DEVICE_ADMIN_CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "isDeviceAdminActive" -> {
                    try {
                        val isActive = devicePolicyManager.isAdminActive(adminComponent)
                        result.success(isActive)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error checking device admin status", e)
                        result.error("DEVICE_ADMIN_ERROR", "Failed to check device admin status", e.message)
                    }
                }
                "requestDeviceAdmin" -> {
                    try {
                        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                        intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, 
                            "This app requires device admin privileges for security features")
                        startActivity(intent)
                        result.success(true)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error requesting device admin", e)
                        result.error("DEVICE_ADMIN_ERROR", "Failed to request device admin", e.message)
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
                        val currentIgStatus = carPowerManager.getCurrentIgStatus()
                        result.success(currentIgStatus)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error getting current igStatus", e)
                        result.error("GET_IG_STATUS_ERROR", "Failed to get current igStatus", e.message)
                    }
                }
                "triggerPowerStateCheck" -> {
                    try {
                        Log.d(TAG, "🔄 Triggering power state check from Flutter")
                        
                        // Test current power state
                        carPowerManager.testCurrentPowerState()
                        
                        // Trigger BackgroundService to check power state
                        val serviceIntent = Intent(this, BackgroundService::class.java).apply {
                            action = "CHECK_POWER_STATE"
                        }
                        startService(serviceIntent)
                        
                        Log.d(TAG, "✅ Power state check triggered successfully")
                        result.success(true)
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error triggering power state check", e)
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
    }

    private fun getSatelliteData(): Map<String, Int> {
        val satelliteData = mutableMapOf<String, Int>()
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    // Get satellite data from BackgroundService if available
                    satelliteData["totalSatellites"] = BackgroundService.totalSatellites
                    satelliteData["connectedSatellites"] = BackgroundService.connectedSatellites
                    
                    Log.d(TAG, "Satellite data: ${satelliteData["totalSatellites"]} total, ${satelliteData["connectedSatellites"]} connected")
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

    // NEW METHOD: Request device admin permission
    private fun requestDeviceAdminPermission() {
        try {
            Log.d(TAG, "=== REQUESTING DEVICE ADMIN PERMISSION ===")
            
            // Check if device admin is already active
            if (devicePolicyManager.isAdminActive(adminComponent)) {
                Log.d(TAG, "✅ Device admin already active")
                handleDeviceAdminPermissionGranted()
                return
            }
            
            // Request device admin permission
            Log.d(TAG, "🔄 Requesting device admin permission...")
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, 
                "This app requires device admin privileges for security features and reliable background operation")
            startActivityForResult(intent, DEVICE_ADMIN_PERMISSION_REQUEST_CODE)
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error requesting device admin permission", e)
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

    // NEW METHOD: Handle activity result for device admin
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        when (requestCode) {
            DEVICE_ADMIN_PERMISSION_REQUEST_CODE -> {
                if (resultCode == RESULT_OK) {
                    Log.d(TAG, "✅ Device admin permission granted")
                    handleDeviceAdminPermissionGranted()
                } else {
                    Log.w(TAG, "❌ Device admin permission denied")
                    showDeviceAdminPermissionDialog()
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
                
                // Continue to next permission (Location)
                requestLocationPermissions()
                
            } else {
                Log.e(TAG, "❌ Failed to get valid IMEI: $imei")
                showImeiPermissionDialog()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error getting IMEI after permission granted", e)
            showImeiPermissionDialog()
        }
    }

    // NEW METHOD: Handle when IMEI permission is denied
    private fun showImeiPermissionDialog() {
        try {
            val builder = android.app.AlertDialog.Builder(this)
            builder.setTitle("Permission Required")
                .setMessage("This app requires Phone State permission to get your device's IMEI number, which is essential for GPS tracking functionality. Without this permission, the app cannot function properly.")
                .setPositiveButton("Grant Permission") { _, _ ->
                    // Try requesting permission again
                    requestImeiPermission()
                }
                .setNegativeButton("Open Settings") { _, _ ->
                    // Open app settings
                    val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    intent.data = android.net.Uri.fromParts("package", packageName, null)
                    startActivity(intent)
                }
                .setCancelable(false)
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "Error showing IMEI permission dialog", e)
        }
    }

    // NEW METHOD: Handle when location permissions are granted
    private fun handleLocationPermissionsGranted() {
        try {
            Log.d(TAG, "=== LOCATION PERMISSIONS GRANTED ===")
            
            // Continue to next permission (Storage)
            requestStoragePermissions()
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error handling location permissions granted", e)
        }
    }

    // NEW METHOD: Handle when storage permissions are granted
    private fun handleStoragePermissionsGranted() {
        try {
            Log.d(TAG, "=== STORAGE PERMISSIONS GRANTED ===")
            
            // Continue to next permission (Device Admin)
            requestDeviceAdminPermission()
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error handling storage permissions granted", e)
        }
    }

    // NEW METHOD: Handle when device admin permission is granted
    private fun handleDeviceAdminPermissionGranted() {
        try {
            Log.d(TAG, "=== DEVICE ADMIN PERMISSION GRANTED ===")
            Log.d(TAG, "✅ ALL PERMISSIONS GRANTED - STARTING BACKGROUND SERVICE ===")
            
            // Get IMEI again to ensure we have it
            val imeiManager = ImeiManager.getInstance(this)
            val imei = imeiManager.getDeviceIdentifier()
            
            if (imei != null && imei.isNotEmpty() && imei != "unknown") {
                Log.d(TAG, "✅ Final IMEI check: $imei")
                
                // Store IMEI in SharedPreferences
                val prefs = getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
                prefs.edit().putString("flutter.imei", imei).apply()
                
                // Start background service with all permissions granted
                startBackgroundServiceWithAllPermissions(imei)
                
            } else {
                Log.e(TAG, "❌ IMEI not available after all permissions granted")
                showImeiPermissionDialog()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error handling device admin permission granted", e)
        }
    }

    // NEW METHOD: Start background service with all permissions
    private fun startBackgroundServiceWithAllPermissions(imei: String) {
        try {
            Log.d(TAG, "=== STARTING BACKGROUND SERVICE WITH ALL PERMISSIONS ===")
            Log.d(TAG, "IMEI: $imei")
            
            val serviceIntent = Intent(this, BackgroundService::class.java).apply {
                putExtra("imei_available", true)
                putExtra("imei", imei)
                putExtra("started_by", "main_activity_all_permissions")
                putExtra("auto_started", true)
                putExtra("background_only", false)
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            
            Log.d(TAG, "✅ Background service started with all permissions")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error starting background service with all permissions", e)
        }
    }

    // NEW METHOD: Show dialog explaining location permission importance
    private fun showLocationPermissionDialog() {
        try {
            val builder = AlertDialog.Builder(this)
            builder.setTitle("Location Permission Required")
                .setMessage("This app requires location permissions to track your device's GPS location. Without these permissions, the app cannot function properly.")
                .setPositiveButton("Grant Permission") { _, _ ->
                    // Try requesting permission again
                    requestLocationPermissions()
                }
                .setNegativeButton("Open Settings") { _, _ ->
                    // Open app settings
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    intent.data = Uri.fromParts("package", packageName, null)
                    startActivity(intent)
                }
                .setCancelable(false)
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "Error showing location permission dialog", e)
        }
    }

    // NEW METHOD: Show dialog explaining storage permission importance
    private fun showStoragePermissionDialog() {
        try {
            val builder = AlertDialog.Builder(this)
            builder.setTitle("Storage Permission Required")
                .setMessage("This app requires storage permissions to save location data locally. Without these permissions, the app cannot function properly.")
                .setPositiveButton("Grant Permission") { _, _ ->
                    // Try requesting permission again
                    requestStoragePermissions()
                }
                .setNegativeButton("Open Settings") { _, _ ->
                    // Open app settings
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    intent.data = Uri.fromParts("package", packageName, null)
                    startActivity(intent)
                }
                .setCancelable(false)
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "Error showing storage permission dialog", e)
        }
    }

    // NEW METHOD: Show dialog explaining device admin permission importance
    private fun showDeviceAdminPermissionDialog() {
        try {
            val builder = AlertDialog.Builder(this)
            builder.setTitle("Device Admin Permission Required")
                .setMessage("This app requires device admin privileges for security features and reliable background operation. Without this permission, the app may not work properly.")
                .setPositiveButton("Grant Permission") { _, _ ->
                    // Try requesting permission again
                    requestDeviceAdminPermission()
                }
                .setNegativeButton("Open Settings") { _, _ ->
                    // Open app settings
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    intent.data = Uri.fromParts("package", packageName, null)
                    startActivity(intent)
                }
                .setCancelable(false)
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "Error showing device admin permission dialog", e)
        }
    }
} 