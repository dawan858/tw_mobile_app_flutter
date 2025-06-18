package com.trackingWorld.tracking.tracking_world

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
                    Log.d(TAG, "✅ Bluetooth turned ON - waking up app")
                    startApp(context, "bluetooth_on")
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

    private fun startApp(context: Context, trigger: String) {
        try {
            Log.d(TAG, "=== STARTING APP FROM BLUETOOTH ===")
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
            Log.e(TAG, "❌ Error starting app from bluetooth", e)
            e.printStackTrace()
        }
    }
} 