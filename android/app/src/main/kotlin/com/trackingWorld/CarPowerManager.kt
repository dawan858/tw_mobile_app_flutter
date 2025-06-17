package com.trackingWorld

import android.content.Context
import android.util.Log
import bw.car.Car
import bw.car.CarNotConnectedException
import bw.car.power.CarPowerManager
import bw.car.power.ICarPowerStateListener

class CarPowerManager(private val context: Context) {
    private var car: Car? = null
    private var carPowerManager: bw.car.power.CarPowerManager? = null
    private var powerStateListener: CarPowerManager.CarPowerStateListener? = null
    private var isConnected = false
    private var accStateCallback: ((Boolean) -> Unit)? = null

    fun initialize() {
        try {
            car = Car.createCar(context, object : android.content.ServiceConnection {
                override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
                    try {
                        carPowerManager = car?.getCarManager(Car.POWER_SERVICE) as bw.car.power.CarPowerManager
                        setupPowerStateListener()
                        connect()
                    } catch (e: CarNotConnectedException) {
                        Log.e(TAG, "Failed to get car power manager", e)
                    }
                }

                override fun onServiceDisconnected(name: android.content.ComponentName?) {
                    disconnect()
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize CarPowerManager", e)
        }
    }

    private fun setupPowerStateListener() {
        powerStateListener = object : CarPowerManager.CarPowerStateListener {
            override fun onPowerStateChanged(state: Int) {
                val isAccOn = state == 1
                Log.d(TAG, "ACC state changed: $isAccOn")
                accStateCallback?.invoke(isAccOn)
            }
        }
    }

    fun setAccStateCallback(callback: (Boolean) -> Unit) {
        accStateCallback = callback
    }

    fun connect() {
        try {
            carPowerManager?.let { manager ->
                if (!isConnected) {
                    manager.registerPowerStateListener(powerStateListener)
                    isConnected = true
                    Log.d(TAG, "Connected to car power state monitoring")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to car power state monitoring", e)
        }
    }

    fun disconnect() {
        try {
            carPowerManager?.let { manager ->
                if (isConnected) {
                    manager.unregisterPowerStateListener(powerStateListener)
                    isConnected = false
                    Log.d(TAG, "Disconnected from car power state monitoring")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disconnect from car power state monitoring", e)
        }
    }

    fun getCurrentAccState(): Boolean {
        return try {
            carPowerManager?.powerState == 1
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get current ACC state", e)
            false
        }
    }

    companion object {
        private const val TAG = "CarPowerManager"
    }
} 