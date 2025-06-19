package com.trackingworld.tracking.trackingworld

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.text.SimpleDateFormat
import java.util.*

class TimeChangeReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "TimeChangeReceiver"
    }

       override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "=== TIME CHANGE RECEIVER TRIGGERED ===")
        Log.d(TAG, "Received action: ${intent.action}")
        
        when (intent.action) {
            "android.intent.action.TIME_SET" -> {
                val currentTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                Log.d(TAG, "✅ Time set to: $currentTime - starting background service only")
                startBackgroundServiceOnly(context, "time_set")
            }
            "android.intent.action.TIMEZONE_CHANGED" -> {
                val timeZone = TimeZone.getDefault().id
                Log.d(TAG, "✅ Timezone changed to: $timeZone - starting background service only")
                startBackgroundServiceOnly(context, "timezone_changed")
            }
        }
    }

    private fun startBackgroundServiceOnly(context: Context, trigger: String) {
        try {
            Log.d(TAG, "=== STARTING BACKGROUND SERVICE ONLY ===")
            Log.d(TAG, "Trigger: $trigger")
            
            // Start ONLY the background service - NO UI
            val serviceIntent = Intent(context, BackgroundService::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                putExtra("started_by", trigger)
                putExtra("auto_started", true)
                putExtra("background_only", true) // Key flag for background-only operation
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.d(TAG, "✅ Background service started (NO UI)")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error starting background service", e)
        }
    }
} 