package com.trackingWorld.tracking.tracking_world

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import android.Manifest

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
    }

    init {
        Log.d(TAG, "BootReceiver initialized")
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "=== BOOT RECEIVER TRIGGERED ===")
        Log.d(TAG, "Received action: ${intent.action}")
        Log.d(TAG, "Package name: ${context.packageName}")
        Log.d(TAG, "Android version: ${Build.VERSION.SDK_INT}")
        
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                Log.d(TAG, "✅ BOOT_COMPLETED received")
                startAppAfterBoot(context, "BOOT_COMPLETED")
            }
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                Log.d(TAG, "✅ LOCKED_BOOT_COMPLETED received (Direct Boot)")
                startAppAfterBoot(context, "LOCKED_BOOT_COMPLETED")
            }
            "android.intent.action.QUICKBOOT_POWERON" -> {
                Log.d(TAG, "✅ QUICKBOOT_POWERON received")
                startAppAfterBoot(context, "QUICKBOOT_POWERON")
            }
            "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                Log.d(TAG, "✅ HTC QUICKBOOT_POWERON received")
                startAppAfterBoot(context, "HTC_QUICKBOOT_POWERON")
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.d(TAG, "✅ MY_PACKAGE_REPLACED received")
                startAppAfterBoot(context, "PACKAGE_REPLACED")
            }
            Intent.ACTION_PACKAGE_REPLACED -> {
                Log.d(TAG, "✅ PACKAGE_REPLACED received")
                if (intent.dataString?.contains(context.packageName) == true) {
                    startAppAfterBoot(context, "PACKAGE_REPLACED")
                }
            }
            Intent.ACTION_POWER_CONNECTED -> {
                Log.d(TAG, "✅ POWER_CONNECTED received")
                startAppAfterBoot(context, "POWER_CONNECTED")
            }
            "com.trackingWorld.tracking.DELAYED_RESTART" -> {
                Log.d(TAG, "✅ DELAYED_RESTART received")
                startAppAfterBoot(context, "DELAYED_RESTART")
            }
            else -> {
                Log.d(TAG, "⚠️ Unknown action received: ${intent.action}")
            }
        }
    }

    private fun startAppAfterBoot(context: Context, trigger: String) {
        try {
            Log.d(TAG, "=== STARTING APP AFTER BOOT ===")
            Log.d(TAG, "Trigger: $trigger")
            
            // Step 1: Check and request critical permissions
            if (!hasRequiredPermissions(context)) {
                Log.e(TAG, "❌ Missing required permissions - app may not start properly")
                // Continue anyway - services can still start with limited functionality
            } else {
                Log.d(TAG, "✅ All required permissions available")
            }

            // Step 2: Initialize IMEI immediately (IMEI ONLY)
            try {
                val imeiManager = ImeiManager.getInstance(context)
                val deviceId = imeiManager.getDeviceIdentifier()
                
                if (deviceId != "unknown" && deviceId.isNotEmpty()) {
                    Log.d(TAG, "✅ IMEI secured on boot: $deviceId")
                } else {
                    Log.e(TAG, "❌ IMEI not available on boot - check permissions")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error securing IMEI on boot: ${e.message}")
            }

            // Step 3: Start the Kotlin BackgroundService immediately
            val kotlinServiceIntent = Intent(context, BackgroundService::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                putExtra("started_by", trigger)
                putExtra("auto_started", true)
            }
            
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Log.d(TAG, "Starting foreground service (Android 8+)")
                    context.startForegroundService(kotlinServiceIntent)
                } else {
                    Log.d(TAG, "Starting regular service (Android 7-)")
                    context.startService(kotlinServiceIntent)
                }
                Log.d(TAG, "✅ Kotlin BackgroundService started successfully")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to start Kotlin BackgroundService: ${e.message}")
            }

            // Step 4: Start the main activity (this will initialize Flutter services)
            val activityIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                putExtra("auto_started", true)
                putExtra("started_by", trigger)
            }
            
            if (activityIntent != null) {
                try {
                    Log.d(TAG, "Starting main activity")
                    context.startActivity(activityIntent)
                    Log.d(TAG, "✅ Main activity started successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Failed to start main activity: ${e.message}")
                }
            } else {
                Log.e(TAG, "❌ Failed to get launch intent for package")
            }

            // Step 5: Additional service startup verification
            scheduleServiceVerification(context)

            Log.d(TAG, "✅ Boot startup sequence completed")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Critical error in boot startup: ${e.message}", e)
            e.printStackTrace()
            
            // Emergency fallback - try to start just the service
            try {
                val emergencyIntent = Intent(context, BackgroundService::class.java)
                context.startService(emergencyIntent)
                Log.d(TAG, "✅ Emergency service start successful")
            } catch (emergencyException: Exception) {
                Log.e(TAG, "❌ Emergency service start failed: ${emergencyException.message}")
            }
        }
    }

    private fun hasRequiredPermissions(context: Context): Boolean {
        val requiredPermissions = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,  // Critical for IMEI
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        
        // Add background location for Android 10+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            requiredPermissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }

        val missingPermissions = requiredPermissions.filter {
            ActivityCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            Log.w(TAG, "Missing permissions: ${missingPermissions.joinToString()}")
            
            // Special check for READ_PHONE_STATE since it's critical for IMEI
            if (missingPermissions.contains(Manifest.permission.READ_PHONE_STATE)) {
                Log.e(TAG, "❌ CRITICAL: READ_PHONE_STATE permission missing - IMEI will not be available")
            }
            
            return false
        }

        return true
    }

    private fun scheduleServiceVerification(context: Context) {
        // Schedule a verification check after 30 seconds
        val verificationIntent = Intent(context, ServiceVerificationReceiver::class.java)
        verificationIntent.action = "com.trackingWorld.tracking.VERIFY_SERVICE"
        
        try {
            // Use a simple handler to delay the verification
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                context.sendBroadcast(verificationIntent)
            }, 30000) // 30 seconds delay
            
            Log.d(TAG, "✅ Service verification scheduled")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error scheduling service verification: ${e.message}")
        }
    }
}

// Additional receiver for service verification
class ServiceVerificationReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "ServiceVerification"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.trackingWorld.tracking.VERIFY_SERVICE") {
            Log.d(TAG, "=== VERIFYING SERVICES ===")
            
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val runningServices = activityManager.getRunningServices(Integer.MAX_VALUE)
            
            var kotlinServiceRunning = false
            
            for (service in runningServices) {
                if (service.service.className == "com.trackingWorld.tracking.tracking_world.BackgroundService") {
                    kotlinServiceRunning = true
                    break
                }
            }
            
            Log.d(TAG, "Kotlin BackgroundService running: $kotlinServiceRunning")
            
            if (!kotlinServiceRunning) {
                Log.w(TAG, "⚠️ Service not running - attempting restart")
                
                val serviceIntent = Intent(context, BackgroundService::class.java)
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                    Log.d(TAG, "✅ Service restart attempted")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Service restart failed: ${e.message}")
                }
            } else {
                Log.d(TAG, "✅ Service verification passed")
                
                // Also verify IMEI is available
                try {
                    val imeiManager = ImeiManager.getInstance(context)
                    val imei = imeiManager.getDeviceIdentifier()
                    
                    if (imei != "unknown" && imei.isNotEmpty()) {
                        Log.d(TAG, "✅ IMEI verification passed")
                    } else {
                        Log.e(TAG, "❌ IMEI verification failed - no valid IMEI")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error verifying IMEI: ${e.message}")
                }
            }
        }
    }
}