package com.trackingWorld

import android.content.Context
import android.util.Log
import bw.car.Car
import bw.car.CarNotConnectedException
import bw.car.power.CarPowerManager

class CarPowerManager(private val context: Context) {
    private var car: Car? = null
    private var carPowerManager: bw.car.power.CarPowerManager? = null
    private var powerStateListener: CarPowerManager.CarPowerStateListener? = null
    private var isConnected = false
    private var accStateCallback: ((Boolean) -> Unit)? = null
    private var currentAccState = false
    private var lastValidState = false
    private var stateChangeCount = 0
    private var lastStateChangeTime = 0L

    fun initialize() {
        try {
            car = Car.createCar(context, object : android.content.ServiceConnection {
                override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
                    try {
                        carPowerManager = car?.getCarManager(Car.POWER_SERVICE) as bw.car.power.CarPowerManager
                        setupPowerStateListener()
                        connect()
                        // Get initial state
                        getCurrentAccState()
                    } catch (e: CarNotConnectedException) {
                        Log.e(TAG, "Failed to get car power manager", e)
                    }
                }

                override fun onServiceDisconnected(name: android.content.ComponentName?) {
                    disconnect()
                }
            })
            
            // Ensure car connection is established
            car?.connect()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize CarPowerManager", e)
        }
    }

    private fun setupPowerStateListener() {
        powerStateListener = object : CarPowerManager.CarPowerStateListener {
            override fun onPowerStateChanged(state: Int) {
                Log.d(TAG, "Raw power state received: $state")
                
                // Filter out rapid fluctuations
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastStateChangeTime < 2000) { // Ignore changes within 2 seconds
                    stateChangeCount++
                    if (stateChangeCount > 3) {
                        Log.d(TAG, "Ignoring rapid state changes, count: $stateChangeCount")
                        return
                    }
                } else {
                    stateChangeCount = 0
                }
                lastStateChangeTime = currentTime
                
                val isAccOn = interpretPowerState(state)
                
                // Only notify if state actually changed
                if (isAccOn != currentAccState) {
                    currentAccState = isAccOn
                    lastValidState = isAccOn
                    
                    Log.d(TAG, "ACC state changed: $isAccOn (from state: $state)")
                    accStateCallback?.invoke(isAccOn)
                }
            }
        }
    }

    private fun interpretPowerState(state: Int): Boolean {
        // Based on your reference code and common automotive implementations
        return when (state) {
            1 -> true   // ACC ON
            0 -> false  // ACC OFF
            2 -> true   // Engine ON (also means ACC is ON)
            3 -> true   // Full power ON
            else -> {
                Log.w(TAG, "Unknown power state: $state, using last valid state: $lastValidState")
                lastValidState // Return last known valid state for unknown values
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
                    powerStateListener?.let { listener ->
                        manager.registerPowerStateListener(listener)
                        isConnected = true
                        Log.d(TAG, "Connected to car power state monitoring")
                    }
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
                    powerStateListener?.let { listener ->
                        manager.unregisterPowerStateListener(listener)
                        isConnected = false
                        Log.d(TAG, "Disconnected from car power state monitoring")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disconnect from car power state monitoring", e)
        }
    }

    fun getCurrentAccState(): Boolean {
        return try {
            val rawState = carPowerManager?.powerState ?: 0
            val accState = interpretPowerState(rawState)
            currentAccState = accState
            Log.d(TAG, "Current ACC state: $accState (raw: $rawState)")
            accState
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get current ACC state", e)
            currentAccState
        }
    }

    fun cleanup() {
        try {
            disconnect()
            car?.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }

    companion object {
        private const val TAG = "CarPowerManager"
    }
}