package com.trackingworld.tracking.trackingworld

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class ServiceRestartReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "ServiceRestartReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Received action: ${intent.action}")
        
        when (intent.action) {
            "com.trackingWorld.tracking.RESTART_SERVICE" -> {
                Log.d(TAG, "Restarting BackgroundService...")
                startBackgroundService(context)
            }
            Intent.ACTION_BOOT_COMPLETED -> {
                Log.d(TAG, "Boot completed, starting services...")
                startBackgroundService(context)
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.d(TAG, "Package replaced, starting services...")
                startBackgroundService(context)
            }
        }
    }

    private fun startBackgroundService(context: Context) {
        try {
            val serviceIntent = Intent(context, BackgroundService::class.java)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            
            Log.d(TAG, "BackgroundService started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start BackgroundService: ${e.message}")
        }
    }
}