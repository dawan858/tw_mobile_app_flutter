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
                Log.d(TAG, "✅ Time set to: $currentTime - waking up app")
                startApp(context, "time_set")
            }
            "android.intent.action.TIMEZONE_CHANGED" -> {
                val timeZone = TimeZone.getDefault().id
                Log.d(TAG, "✅ Timezone changed to: $timeZone - waking up app")
                startApp(context, "timezone_changed")
            }
        }
    }

    private fun startApp(context: Context, trigger: String) {
        try {
            Log.d(TAG, "=== STARTING APP FROM TIME CHANGE ===")
            Log.d(TAG, "Trigger: $trigger")
            
            // Start the background service
            val serviceIntent = Intent(context, BackgroundService::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                putExtra("started_by", trigger)
                putExtra("auto_started", true)
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.d(TAG, "✅ Background service started")

            // Start the main activity
            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                putExtra("auto_started", true)
                putExtra("started_by", trigger)
            }
            
            if (launchIntent != null) {
                context.startActivity(launchIntent)
                Log.d(TAG, "✅ Main activity started")
            } else {
                Log.e(TAG, "❌ Failed to get launch intent")
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error starting app from time change", e)
            e.printStackTrace()
        }
    }
} 