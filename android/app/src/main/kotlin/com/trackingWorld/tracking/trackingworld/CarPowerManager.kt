package com.trackingworld.tracking.trackingworld

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
    private var sleepStateCallback: ((Boolean) -> Unit)? = null
    private var isSleeping = false
    private var igStatus = 0 // Initialize to 0 (ACC off)

    companion object {
        private const val TAG = "CarPowerManager"
        private const val STATE_SUSPEND_ENTER = 1
        private const val STATE_SUSPEND_EXIT = 2
        private const val STATE_SHUTDOWN_ENTER = 3
        private const val STATE_SHUTDOWN_EXIT = 4
    }

    fun setSleepStateCallback(callback: (Boolean) -> Unit) {
        sleepStateCallback = callback
    }

    fun getCurrentSleepState(): Boolean {
        return isSleeping
    }

    fun getCurrentIgStatus(): Int {
        return igStatus
    }

    fun initialize() {
        try {
            car = Car.createCar(context, object : android.content.ServiceConnection {
                override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
                    try {
                        carPowerManager = car?.getCarManager(Car.POWER_SERVICE) as bw.car.power.CarPowerManager
                        
                        connect()
                        setupPowerStateListener()
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
                Log.d(TAG, "Power state changed: $state")
                
                when (state) {
                    STATE_SUSPEND_ENTER -> {
                        Log.d(TAG, "Entering sleep state")
                        isSleeping = true
                        sleepStateCallback?.invoke(true)
                    }
                    STATE_SUSPEND_EXIT -> {
                        Log.d(TAG, "Exiting sleep state")
                        isSleeping = false
                        sleepStateCallback?.invoke(false)
                    }
                    STATE_SHUTDOWN_ENTER -> {
                        Log.d(TAG, "Entering deep sleep state")
                        isSleeping = true
                        sleepStateCallback?.invoke(true)
                    }
                    STATE_SHUTDOWN_EXIT -> {
                        Log.d(TAG, "Exiting deep sleep state")
                        isSleeping = false
                        sleepStateCallback?.invoke(false)
                    }
                }

                // Handle ACC state and update igStatus
                val isAccOn = interpretPowerState(state)
                if (isAccOn != currentAccState) {
                    val oldStatus = igStatus
                    igStatus = if (isAccOn) 1 else 0
                    currentAccState = isAccOn
                    lastValidState = isAccOn
                    
                    Log.d(TAG, "ACC state changed from $oldStatus to $igStatus")
                    accStateCallback?.invoke(isAccOn)
                }
            }
        }
        
        try {
            carPowerManager?.registerPowerStateListener(powerStateListener)
            Log.d(TAG, "Power state listener registered successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register power state listener", e)
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

    fun getCurrentAccState(): Boolean {
        return currentAccState
    }

    fun setAccStateCallback(callback: (Boolean) -> Unit) {
        accStateCallback = callback
    }

    fun connect() {
        if (!isConnected) {
            try {
                car?.connect()
                isConnected = true
                Log.d(TAG, "Connected to car power manager")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to car power manager", e)
            }
        }
    }

    fun disconnect() {
        if (isConnected) {
            try {
                carPowerManager?.unregisterPowerStateListener(powerStateListener)
                car?.disconnect()
                isConnected = false
                Log.d(TAG, "Disconnected from car power manager")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to disconnect from car power manager", e)
            }
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
}