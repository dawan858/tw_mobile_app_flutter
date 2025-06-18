package com.trackingworld.tracking.trackingworld

import android.content.Intent
import android.content.IntentFilter
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

class MainActivity : FlutterActivity() {
    private val CHANNEL = "com.example.tracking_world/service"
    private val DEVICE_INFO_CHANNEL = "com.example.tracking_world/device_info"
    private val TAG = "MainActivity"
    private var terminationReceiver: AppTerminationReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "MainActivity onCreate")
        registerTerminationReceiver()
        checkAndRequestPermissions()
        startTrackingService()
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

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "startService" -> {
                    Log.d(TAG, "Starting GPS tracking service from Flutter")
                    startTrackingService()
                    result.success(null)
                }
                "stopService" -> {
                    Log.d(TAG, "Stopping GPS tracking service from Flutter")
                    val serviceIntent = Intent(this, GpsTrackingService::class.java)
                    stopService(serviceIntent)
                    result.success(null)
                }
                else -> {
                    result.notImplemented()
                }
            }
        }

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, DEVICE_INFO_CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "getImei" -> {
                    try {
                        val telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            result.success(telephonyManager.imei)
                        } else {
                            @Suppress("DEPRECATION")
                            result.success(telephonyManager.deviceId)
                        }
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
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "MainActivity onDestroy")
        // Don't unregister the receiver here as we want it to survive app termination
    }
} 