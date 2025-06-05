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
import android.content.DialogInterface
import androidx.appcompat.app.AlertDialog
import android.app.ActivityManager

class MainActivity: FlutterActivity() {
    private val CHANNEL = "com.trackingWorld.tracking/launch"
    private val DEVICE_CHANNEL = "com.trackingWorld.tracking/device_info"
    private val SERVICE_CHANNEL = "com.trackingWorld.tracking/service"
    private val PERMISSION_REQUEST_CODE = 123
    private var pendingImeiResult: MethodChannel.Result? = null
    private var flutterEngine: FlutterEngine? = null
    private var autoStartTracking = false
    private var startedBy = ""
    private val SATELLITE_CHANNEL = "com.trackingWorld.tracking/satellite"

    private val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        this.flutterEngine = flutterEngine
        
        // IMEI Channel
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, DEVICE_CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "getImei" -> {
                    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_PHONE_STATE), PERMISSION_REQUEST_CODE)
                        pendingImeiResult = result
                        return@setMethodCallHandler
                    }
                    getImei(result)
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
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        }
    }

    private fun checkAndRequestPermission(): Boolean {
        for (permission in REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) 
                != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }
        return true
    }

    private fun getImei(result: MethodChannel.Result) {
        try {
            val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            
            // For Android 9 (API 28), we need to use getImei(0) for the first SIM slot
            val imei = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                telephonyManager.getImei(0) // Get IMEI for first SIM slot
            } else {
                @Suppress("DEPRECATION")
                telephonyManager.deviceId
            }
            
            if (!imei.isNullOrEmpty()) {
                Log.d("MainActivity", "Got IMEI: $imei")
                result.success(imei)
            } else {
                Log.e("MainActivity", "IMEI is null or empty")
                result.error("IMEI_UNAVAILABLE", "Could not get IMEI", null)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error getting IMEI", e)
            result.error("IMEI_ERROR", "Error getting IMEI: ${e.message}", null)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            var allGranted = true
            for (result in grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false
                    break
                }
            }
            
            if (allGranted) {
                // All permissions granted
                pendingImeiResult?.let { result ->
                    getImei(result)
                    pendingImeiResult = null
                }
                flutterEngine?.dartExecutor?.binaryMessenger?.let { messenger ->
                    MethodChannel(messenger, DEVICE_CHANNEL).invokeMethod("onPermissionGranted", null)
                }
            } else {
                // Some permissions were denied
                showPermissionDeniedDialog()
                pendingImeiResult?.error("PERMISSION_DENIED", "Required permissions not granted", null)
                pendingImeiResult = null
                flutterEngine?.dartExecutor?.binaryMessenger?.let { messenger ->
                    MethodChannel(messenger, DEVICE_CHANNEL).invokeMethod("onPermissionDenied", null)
                }
            }
        }
    }

    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permissions Required")
            .setMessage("This app requires all permissions to function properly. Please grant all permissions in settings.")
            .setPositiveButton("Open Settings") { dialog: DialogInterface, _: Int ->
                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                val uri = android.net.Uri.fromParts("package", packageName, null)
                intent.data = uri
                startActivity(intent)
                finish()
            }
            .setNegativeButton("Exit") { dialog: DialogInterface, _: Int ->
                finish()
            }
            .setCancelable(false)
            .show()
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
        checkAndRequestPermissions()
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        autoStartTracking = intent.getBooleanExtra(IgnitionReceiver.EXTRA_AUTO_START, false)
        startedBy = intent.getStringExtra(IgnitionReceiver.EXTRA_STARTED_BY) ?: ""
        
        if (autoStartTracking) {
            Log.d("MainActivity", "App started by ignition: $startedBy")
        }
    }
} 