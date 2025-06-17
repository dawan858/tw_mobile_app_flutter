package com.trackingWorld

import android.content.Context
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.BinaryMessenger

class CarPowerPlugin(
    private val context: Context,
    messenger: BinaryMessenger
) : MethodCallHandler {
    private val channel = MethodChannel(messenger, CHANNEL_NAME)
    private val carPowerManager = CarPowerManager(context)

    init {
        channel.setMethodCallHandler(this)
        carPowerManager.initialize()
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "getCurrentAccState" -> {
                result.success(carPowerManager.getCurrentAccState())
            }
            "startMonitoring" -> {
                carPowerManager.connect()
                result.success(null)
            }
            "stopMonitoring" -> {
                carPowerManager.disconnect()
                result.success(null)
            }
            else -> {
                result.notImplemented()
            }
        }
    }

    fun sendAccStateUpdate(isAccOn: Boolean) {
        channel.invokeMethod("onAccStateChanged", isAccOn)
    }

    companion object {
        private const val CHANNEL_NAME = "com.trackingWorld/car_power"
    }
} 