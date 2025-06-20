package com.example.twtracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class SleepWakeUpReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "SleepWakeUpReceiver"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return
        
        when (intent?.action) {
            "com.trackingWorld.tracking.SLEEP_WAKE_UP_CHECK" -> {
                Log.d(TAG, "Sleep wake-up check triggered")
                performWakeUpCheck(context)
            }
        }
    }

    private fun performWakeUpCheck(context: Context) {
        try {
            Log.d(TAG, "=== PERFORMING SLEEP WAKE-UP CHECK ===")
            
            // Check if we're still in sleep state
            val prefs = context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
            val isSleeping = prefs.getBoolean("flutter.is_sleeping", false)
            
            if (isSleeping) {
                Log.d(TAG, "Still in sleep state - checking if we should wake up")
                
                // Check if ACC is back on (indicating AVN wake-up)
                val currentIgStatus = prefs.getInt("current_ig_status", 0)
                if (currentIgStatus == 1) {
                    Log.d(TAG, "ACC is ON - AVN has woken up, starting background service only")
                    
                    // Start ONLY the background service - NO UI launch
                    val serviceIntent = Intent(context, BackgroundService::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        putExtra("wake_up_from_sleep", true)
                        putExtra("background_only", true) // Flag to indicate background-only start
                    }
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                    
                    // Update sleep state
                    prefs.edit().putBoolean("flutter.is_sleeping", false).apply()
                    
                    Log.d(TAG, "✅ Background service started from sleep wake-up")
                    
                } else {
                    Log.d(TAG, "ACC still OFF - continuing sleep mode")
                    
                    // Keep the service alive with minimal activity
                    val serviceIntent = Intent(context, BackgroundService::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        putExtra("sleep_keep_alive", true)
                        putExtra("background_only", true)
                    }
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                }
            } else {
                Log.d(TAG, "Not in sleep state - normal operation")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during wake-up check", e)
        }
    }
} 