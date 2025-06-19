package com.trackingworld.tracking.trackingworld

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BluetoothStateReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BluetoothStateReceiver"
    }

        override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "=== BLUETOOTH STATE RECEIVER TRIGGERED ===")
        Log.d(TAG, "Received action: ${intent.action}")
        
        if (intent.action == "android.bluetooth.adapter.action.STATE_CHANGED") {
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            val previousState = intent.getIntExtra(BluetoothAdapter.EXTRA_PREVIOUS_STATE, BluetoothAdapter.ERROR)
            
            Log.d(TAG, "Bluetooth state changed from $previousState to $state")
            
            when (state) {
                BluetoothAdapter.STATE_ON -> {
                    Log.d(TAG, "✅ Bluetooth turned ON - starting background service only")
                    startBackgroundServiceOnly(context, "bluetooth_on")
                }
                BluetoothAdapter.STATE_OFF -> {
                    Log.d(TAG, "Bluetooth turned OFF")
                }
                BluetoothAdapter.STATE_TURNING_ON -> {
                    Log.d(TAG, "Bluetooth turning ON")
                }
                BluetoothAdapter.STATE_TURNING_OFF -> {
                    Log.d(TAG, "Bluetooth turning OFF")
                }
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
