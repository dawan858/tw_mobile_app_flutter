package com.trackingWorld.tracking.tracking_world

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class IgnitionReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "IgnitionReceiver"
        const val EXTRA_AUTO_START = "auto_start_tracking"
        const val EXTRA_STARTED_BY = "started_by"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Received action: ${intent.action}")
        
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_POWER_CONNECTED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                Log.d(TAG, "Ignition detected, starting app...")
                startApp(context, intent.action ?: "unknown")
            }
            Intent.ACTION_POWER_DISCONNECTED -> {
                Log.d(TAG, "Ignition turned off")
                // Optionally handle power off
            }
        }
    }

    private fun startApp(context: Context, startedBy: String) {
        try {
            // Start the background service first
            val serviceIntent = Intent(context, BackgroundService::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.d(TAG, "Background service started")

            // Start the main activity with auto-start flag
            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                putExtra(EXTRA_AUTO_START, true)
                putExtra(EXTRA_STARTED_BY, startedBy)
            }
            
            if (launchIntent != null) {
                context.startActivity(launchIntent)
                Log.d(TAG, "Main activity started with auto-start flag")
            } else {
                Log.e(TAG, "Failed to get launch intent")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error starting app", e)
            e.printStackTrace()
        }
    }
} 