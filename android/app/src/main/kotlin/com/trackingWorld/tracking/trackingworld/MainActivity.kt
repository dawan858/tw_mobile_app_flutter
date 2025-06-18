package com.trackingworld.tracking.trackingworld

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

class MainActivity : FlutterActivity() {
    private val CHANNEL = "com.trackingWorld.tracking/service"
    private val DEVICE_INFO_CHANNEL = "com.trackingWorld.tracking/device_info"
    private val DEVICE_ADMIN_CHANNEL = "device_admin_channel"
    private val SATELLITE_CHANNEL = "com.trackingWorld.tracking/satellite"
    private val AVN_SLEEP_CHANNEL = "com.trackingWorld.tracking/avn_sleep"
    private val TAG = "MainActivity"
    private var terminationReceiver: AppTerminationReceiver? = null
    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    private lateinit var locationManager: LocationManager
    private var gnssStatusCallback: GnssStatus.Callback? = null
    private lateinit var carPowerManager: CarPowerManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "MainActivity onCreate")
        
        // Initialize device admin components
        devicePolicyManager = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, MyDeviceAdminReceiver::class.java)
        
        // Initialize location manager for satellite data
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        
        // Initialize car power manager for sleep monitoring
        carPowerManager = CarPowerManager(this)
        carPowerManager.initialize()
        
        registerTerminationReceiver()
        // Don't start tracking service here - let Flutter control it after IMEI is obtained
    }

    private fun checkAndRequestPermissions() {
        // Request battery optimization exemption
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val packageName = packageName
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
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
            }
        }

        // Check for manufacturer-specific settings
        when {
            isXiaomiDevice() -> {
                showManufacturerSettings("Xiaomi", "Please enable autostart and disable battery optimization in MIUI settings")
            }
            isHuaweiDevice() -> {
                showManufacturerSettings("Huawei", "Please enable autostart and disable battery optimization in EMUI settings")
            }
            isOppoDevice() -> {
                showManufacturerSettings("OPPO", "Please enable autostart and disable battery optimization in ColorOS settings")
            }
            isVivoDevice() -> {
                showManufacturerSettings("VIVO", "Please enable autostart and disable battery optimization in FuntouchOS settings")
            }
            isSamsungDevice() -> {
                showManufacturerSettings("Samsung", "Please disable battery optimization in Device Care settings")
            }
            isInfinixDevice() -> {
                showManufacturerSettings("Infinix", "Please enable autostart and disable battery optimization in XOS settings")
            }
        }
    }

    private fun showManufacturerSettings(manufacturer: String, message: String) {
        try {
            val intent = Intent().apply {
                when (manufacturer) {
                    "Xiaomi" -> {
                        action = "miui.intent.action.OP_AUTO_START"
                        addCategory(Intent.CATEGORY_DEFAULT)
                    }
                    "Huawei" -> {
                        action = "huawei.intent.action.POWERGENIE_APP"
                    }
                    "OPPO" -> {
                        action = "oppo.intent.action.OPPO_COMPONENT_SAFE"
                    }
                    "VIVO" -> {
                        action = "vivo.intent.action.VIVO_COMPONENT_SAFE"
                    }
                    "Samsung" -> {
                        action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                        data = Uri.parse("package:$packageName")
                    }
                    "Infinix" -> {
                        action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                        data = Uri.parse("package:$packageName")
                    }
                }
            }
            startActivity(intent)
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(TAG, "Error opening manufacturer settings", e)
        }
    }

    private fun isXiaomiDevice(): Boolean {
        return Build.MANUFACTURER.lowercase().contains("xiaomi") ||
               Build.MANUFACTURER.lowercase().contains("redmi")
    }

    private fun isHuaweiDevice(): Boolean {
        return Build.MANUFACTURER.lowercase().contains("huawei") ||
               Build.MANUFACTURER.lowercase().contains("honor")
    }

    private fun isOppoDevice(): Boolean {
        return Build.MANUFACTURER.lowercase().contains("oppo") ||
               Build.MANUFACTURER.lowercase().contains("oneplus")
    }

    private fun isVivoDevice(): Boolean {
        return Build.MANUFACTURER.lowercase().contains("vivo")
    }

    private fun isSamsungDevice(): Boolean {
        return Build.MANUFACTURER.lowercase().contains("samsung")
    }

    private fun isInfinixDevice(): Boolean {
        return Build.MANUFACTURER.lowercase().contains("infinix")
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
                else -> {
                    result.notImplemented()
                }
            }
        }

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
                else -> {
                    result.notImplemented()
                }
            }
        }
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
} 