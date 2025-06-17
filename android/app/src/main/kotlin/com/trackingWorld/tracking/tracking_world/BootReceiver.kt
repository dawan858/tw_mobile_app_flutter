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
                startApp(context, "BOOT_COMPLETED")
            }
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                Log.d(TAG, "✅ LOCKED_BOOT_COMPLETED received (Direct Boot)")
                startApp(context, "LOCKED_BOOT_COMPLETED")
            }
            "android.intent.action.QUICKBOOT_POWERON" -> {
                Log.d(TAG, "✅ QUICKBOOT_POWERON received")
                startApp(context, "QUICKBOOT_POWERON")
            }
            "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                Log.d(TAG, "✅ HTC QUICKBOOT_POWERON received")
                startApp(context, "HTC_QUICKBOOT_POWERON")
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.d(TAG, "✅ MY_PACKAGE_REPLACED received")
                startApp(context, "PACKAGE_REPLACED")
            }
            Intent.ACTION_PACKAGE_REPLACED -> {
                Log.d(TAG, "✅ PACKAGE_REPLACED received")
                if (intent.dataString?.contains(context.packageName) == true) {
                    startApp(context, "PACKAGE_REPLACED")
                }
            }
            Intent.ACTION_POWER_CONNECTED -> {
                Log.d(TAG, "✅ POWER_CONNECTED received")
                startApp(context, "POWER_CONNECTED")
            }
            else -> {
                Log.d(TAG, "⚠️ Unknown action received: ${intent.action}")
            }
        }
    }

    private fun startApp(context: Context, trigger: String) {
        try {
            Log.d(TAG, "=== STARTING APP AFTER BOOT ===")
            Log.d(TAG, "Trigger: $trigger")
            
            // Step 1: Check permissions (but don't block if missing)
            if (!hasRequiredPermissions(context)) {
                Log.w(TAG, "⚠️ Missing some permissions - continuing anyway")
            } else {
                Log.d(TAG, "✅ All required permissions available")
            }

            // Step 2: Start the BackgroundService immediately (CRITICAL)
            val serviceIntent = Intent(context, BackgroundService::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                putExtra("started_by", trigger)
                putExtra("auto_started", true)
            }
            
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Log.d(TAG, "Starting foreground service (Android 8+)")
                    context.startForegroundService(serviceIntent)
                } else {
                    Log.d(TAG, "Starting regular service (Android 7-)")
                    context.startService(serviceIntent)
                }
                Log.d(TAG, "✅ BackgroundService started successfully")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to start BackgroundService: ${e.message}")
                
                // Emergency fallback - try again with minimal intent
                try {
                    val emergencyIntent = Intent(context, BackgroundService::class.java)
                    context.startService(emergencyIntent)
                    Log.d(TAG, "✅ Emergency service start successful")
                } catch (emergencyException: Exception) {
                    Log.e(TAG, "❌ Emergency service start failed: ${emergencyException.message}")
                }
            }

            // Step 3: Start the main activity
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

            Log.d(TAG, "✅ Boot startup sequence completed")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Critical error in boot startup: ${e.message}", e)
            e.printStackTrace()
        }
    }

    private fun hasRequiredPermissions(context: Context): Boolean {
        val requiredPermissions = mutableListOf(
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
            return false
        }

        return true
    }
}