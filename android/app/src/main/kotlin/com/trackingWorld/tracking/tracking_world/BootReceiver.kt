package com.trackingWorld.tracking.tracking_world

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.os.SystemClock

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
        private const val DELAYED_START_ACTION = "com.trackingWorld.tracking.DELAYED_START"
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
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_PACKAGE_REPLACED,
            Intent.ACTION_POWER_CONNECTED,
            DELAYED_START_ACTION -> {
                Log.d(TAG, "✅ ${intent.action} received")
                startApp(context, intent.action ?: "unknown")
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
                
                // Schedule a delayed start attempt
                scheduleDelayedStart(context)
            }

            // Step 3: Schedule periodic health checks
            schedulePeriodicHealthCheck(context)

            Log.d(TAG, "✅ Boot startup sequence completed")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Critical error in boot startup: ${e.message}", e)
            e.printStackTrace()
            
            // Schedule a delayed start attempt
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

    private fun schedulePeriodicHealthCheck(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, BootReceiver::class.java).apply {
                action = "com.trackingWorld.tracking.HEALTH_CHECK"
            }
            
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                888,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            // Check every 15 minutes
            val interval = 15 * 60 * 1000L
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setRepeating(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + interval,
                    interval,
                    pendingIntent
                )
            } else {
                alarmManager.setRepeating(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + interval,
                    interval,
                    pendingIntent
                )
            }
            
            Log.d(TAG, "✅ Periodic health check scheduled every 15 minutes")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to schedule health check: ${e.message}")
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