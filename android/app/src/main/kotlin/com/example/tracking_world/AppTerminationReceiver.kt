package com.example.twtracking
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class AppTerminationReceiver : BroadcastReceiver() {
    private val TAG = "AppTerminationReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Received broadcast: ${intent.action}")
        
        // Start ONLY the tracking service - NO UI launch
        val serviceIntent = Intent(context, BackgroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
        Log.d(TAG, "Restarting tracking service in background only")
        
        // DO NOT launch the main activity - keep it in background
        // The service will show a notification instead
    }
} 