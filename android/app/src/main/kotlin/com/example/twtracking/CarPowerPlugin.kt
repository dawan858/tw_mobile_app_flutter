package com.example.twtracking

import android.content.Context
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.BinaryMessenger
import android.util.Log

class CarPowerPlugin(
    private val context: Context,
    private val messenger: BinaryMessenger
) : MethodCallHandler {
    private val carPowerManager = CarPowerManager(context)
    private val channel = MethodChannel(messenger, CHANNEL_NAME)

    companion object {
        private const val TAG = "CarPowerPlugin"
        private const val CHANNEL_NAME = "com.trackingWorld.tracking/car_power"
    }

    init {
        Log.d(TAG, "CarPowerPlugin initialized")
        channel.setMethodCallHandler(this)
        
        // Initialize car power manager
        carPowerManager.initialize()
        
        // Set up callback to send updates to Flutter
        carPowerManager.setAccStateCallback { isAccOn ->
            Log.d(TAG, "Sending ACC state to Flutter: $isAccOn")
            try {
                channel.invokeMethod("onAccStateChanged", isAccOn)
            } catch (e: Exception) {
                Log.e(TAG, "Error sending ACC state to Flutter", e)
            }
        }

        // Set up sleep state callback
        carPowerManager.setSleepStateCallback { isSleeping ->
            Log.d(TAG, "Sleep state changed: $isSleeping")
            try {
                channel.invokeMethod("onSleepStateChanged", isSleeping)
            } catch (e: Exception) {
                Log.e(TAG, "Error sending sleep state to Flutter", e)
            }
        }
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        Log.d(TAG, "Method called: ${call.method}")
        
        when (call.method) {
            "getCurrentAccState" -> {
                try {
                    val accState = carPowerManager.getCurrentAccState()
                    Log.d(TAG, "Returning current ACC state: $accState")
                    result.success(accState)
                } catch (e: Exception) {
                    Log.e(TAG, "Error getting current ACC state", e)
                    result.error("GET_STATE_ERROR", "Failed to get ACC state", e.message)
                }
            }
            "startMonitoring" -> {
                try {
                    carPowerManager.connect()
                    Log.d(TAG, "Started ACC monitoring")
                    result.success(true)
                } catch (e: Exception) {
                    Log.e(TAG, "Error starting monitoring", e)
                    result.error("START_ERROR", "Failed to start monitoring", e.message)
                }
            }
            "stopMonitoring" -> {
                try {
                    carPowerManager.disconnect()
                    Log.d(TAG, "Stopped ACC monitoring")
                    result.success(true)
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping monitoring", e)
                    result.error("STOP_ERROR", "Failed to stop monitoring", e.message)
                }
            }
            "getAccState" -> {
                result.success(carPowerManager.getCurrentAccState())
            }
            "getSleepState" -> {
                result.success(carPowerManager.getCurrentSleepState())
            }
            else -> {
                Log.w(TAG, "Method not implemented: ${call.method}")
                result.notImplemented()
            }
        }
    }

    fun cleanup() {
        try {
            carPowerManager.cleanup()
            Log.d(TAG, "CarPowerPlugin cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }
}