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
        Log.d(TAG, "Received action: ${intent.action}")
        Log.d(TAG, "Package name: ${context.packageName}")
        
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                Log.d(TAG, "Received BOOT_COMPLETED")
                startApp(context)
            }
            "android.intent.action.QUICKBOOT_POWERON" -> {
                Log.d(TAG, "Received QUICKBOOT_POWERON")
                startApp(context)
            }
            "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                Log.d(TAG, "Received HTC QUICKBOOT_POWERON")
                startApp(context)
            }
            "android.intent.action.MY_PACKAGE_REPLACED" -> {
                Log.d(TAG, "Received MY_PACKAGE_REPLACED")
                startApp(context)
            }
            else -> {
                Log.d(TAG, "Received unknown action: ${intent.action}")
            }
        }
    }

    private fun startApp(context: Context) {
        try {
            Log.d(TAG, "Starting app...")
            
            // Check for required permissions
            if (!hasRequiredPermissions(context)) {
                Log.e(TAG, "Missing required permissions")
                return
            }
            Log.d(TAG, "All permissions granted")

            // Start the background service first
            val serviceIntent = Intent(context, BackgroundService::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Log.d(TAG, "Starting foreground service")
                context.startForegroundService(serviceIntent)
            } else {
                Log.d(TAG, "Starting regular service")
                context.startService(serviceIntent)
            }
            Log.d(TAG, "Background service started")

            // Start the main activity
            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            if (launchIntent != null) {
                Log.d(TAG, "Starting main activity")
                context.startActivity(launchIntent)
                Log.d(TAG, "Main activity started")
            } else {
                Log.e(TAG, "Failed to get launch intent")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error starting app", e)
            e.printStackTrace()
        }
    }

    private fun hasRequiredPermissions(context: Context): Boolean {
        val requiredPermissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.FOREGROUND_SERVICE_LOCATION
        )

        val missingPermissions = requiredPermissions.filter {
            ActivityCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            Log.e(TAG, "Missing permissions: ${missingPermissions.joinToString()}")
            return false
        }

        return true
    }
} 