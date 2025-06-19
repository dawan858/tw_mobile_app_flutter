package com.trackingworld.tracking.trackingworld

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.app.AlarmManager
import android.app.PendingIntent
import android.os.SystemClock

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
        private const val DELAYED_START_ACTION = "com.trackingWorld.tracking.DELAYED_START"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "=== BOOT RECEIVER TRIGGERED ===")
        Log.d(TAG, "Received action: ${intent.action}")
        
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_PACKAGE_REPLACED,
            Intent.ACTION_POWER_CONNECTED,
            "android.intent.action.TIME_SET",
            "android.intent.action.TIMEZONE_CHANGED",
            "android.bluetooth.adapter.action.STATE_CHANGED",
            DELAYED_START_ACTION -> {
                Log.d(TAG, "✅ Auto-start trigger: ${intent.action}")
                startIndependentBackgroundService(context, intent.action ?: "unknown")
            }
            else -> {
                Log.d(TAG, "⚠️ Unknown action: ${intent.action}")
            }
        }
    }

    /**
     * CRITICAL: Start BackgroundService directly - NO MainActivity dependency
     */
    private fun startIndependentBackgroundService(context: Context, trigger: String) {
        try {
            Log.d(TAG, "=== STARTING INDEPENDENT BACKGROUND SERVICE ===")
            Log.d(TAG, "Trigger: $trigger")
            
            // Start BackgroundService DIRECTLY - it will initialize its own CarPowerManager
            val serviceIntent = Intent(context, BackgroundService::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                putExtra("started_by", trigger)
                putExtra("auto_started", true)
                putExtra("background_only", true) // CRITICAL: No UI launch
                putExtra("independent_start", true) // Mark as independent start
            }
            
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Log.d(TAG, "Starting foreground service (Android 8+)")
                    context.startForegroundService(serviceIntent)
                } else {
                    Log.d(TAG, "Starting regular service (Android 7-)")
                    context.startService(serviceIntent)
                }
                Log.d(TAG, "✅ Independent BackgroundService started successfully")
                Log.d(TAG, "✅ CarPowerManager will be initialized inside the service")
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to start BackgroundService: ${e.message}")
                
                // Schedule a delayed retry
                scheduleDelayedStart(context)
            }

            // IMPORTANT: NO MainActivity startup - pure background operation
            Log.d(TAG, "✅ Pure background startup completed - NO UI launched")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Critical error in independent startup: ${e.message}", e)
            
            // Schedule a delayed retry
            scheduleDelayedStart(context)
        }
    }

    private fun scheduleDelayedStart(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, BootReceiver::class.java).apply {
                action = DELAYED_START_ACTION
            }
            
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                999,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            // Try again after 30 seconds
            val triggerTime = SystemClock.elapsedRealtime() + 30000
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            }
            
            Log.d(TAG, "✅ Delayed start scheduled for 30 seconds")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to schedule delayed start: ${e.message}")
        }
    }
}