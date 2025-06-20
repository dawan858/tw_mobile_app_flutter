package com.example.twtracking

import android.content.Context
import android.content.Intent
import android.os.Build
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
        private const val STATE_ACC_ON = 1
        private const val STATE_ACC_OFF = 0
        private const val STATE_ENGINE_ON = 2
        private const val STATE_FULL_POWER = 3
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
            Log.d(TAG, "=== INITIALIZING CAR POWER MANAGER (PRIMARY AUTO-START) ===")
            
            car = Car.createCar(context, object : android.content.ServiceConnection {
                override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
                    try {
                        Log.d(TAG, "Car service connected")
                        carPowerManager = car?.getCarManager(Car.POWER_SERVICE) as bw.car.power.CarPowerManager
                        
                        connect()
                        setupPowerStateListener()
                        
                        // Get initial state and trigger auto-start if needed
                        val initialState = getCurrentAccState()
                        handleInitialPowerState(initialState)
                        
                    } catch (e: CarNotConnectedException) {
                        Log.e(TAG, "Failed to get car power manager", e)
                    }
                }

                override fun onServiceDisconnected(name: android.content.ComponentName?) {
                    Log.w(TAG, "Car service disconnected")
                    disconnect()
                }
            })
            
            // Ensure car connection is established
            car?.connect()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize CarPowerManager", e)
        }
    }

    private fun handleInitialPowerState(isAccOn: Boolean) {
        Log.d(TAG, "=== HANDLING INITIAL POWER STATE ===")
        Log.d(TAG, "Initial ACC state: $isAccOn")
        
        if (isAccOn) {
            Log.d(TAG, "🚗 ACC is ON - Auto-starting background service")
            startBackgroundServiceOnly("car_power_initial_acc_on")
        } else {
            Log.d(TAG, "🚗 ACC is OFF - Checking for sleep state")
            checkAndHandleSleepState()
        }
    }

    private fun setupPowerStateListener() {
        powerStateListener = object : CarPowerManager.CarPowerStateListener {
            override fun onPowerStateChanged(state: Int) {
                Log.d(TAG, "=== CAR POWER STATE CHANGED ===")
                Log.d(TAG, "New power state: $state")
                
                handlePowerStateChange(state)
            }
        }
        
        try {
            carPowerManager?.registerPowerStateListener(powerStateListener)
            Log.d(TAG, "✅ Power state listener registered successfully")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to register power state listener", e)
        }
    }

    private fun handlePowerStateChange(state: Int) {
        Log.d(TAG, "=== PROCESSING POWER STATE CHANGE ===")
        Log.d(TAG, "State: $state")
        
        when (state) {
            STATE_SUSPEND_ENTER -> {
                Log.d(TAG, "🚗 ENTERING SLEEP STATE")
                isSleeping = true
                igStatus = 0 // ACC OFF during sleep
                currentAccState = false
                
                // Store sleep state
                storeSleepState(true)
                
                // Notify callbacks
                sleepStateCallback?.invoke(true)
                accStateCallback?.invoke(false)
                
                // Start background service in sleep mode
                startBackgroundServiceOnly("car_power_sleep_enter")
            }
            
            STATE_SUSPEND_EXIT -> {
                Log.d(TAG, "🚗 EXITING SLEEP STATE - AVN WAKE UP")
                isSleeping = false
                igStatus = 1 // ACC ON when waking up
                currentAccState = true
                
                // Store wake state
                storeSleepState(false)
                
                // Notify callbacks
                sleepStateCallback?.invoke(false)
                accStateCallback?.invoke(true)
                
                // This is the CRITICAL auto-start moment - AVN just woke up
                startBackgroundServiceOnly("car_power_wake_up")
            }
            
            STATE_SHUTDOWN_ENTER -> {
                Log.d(TAG, "🚗 ENTERING DEEP SLEEP STATE")
                isSleeping = true
                igStatus = 0
                currentAccState = false
                
                storeSleepState(true)
                sleepStateCallback?.invoke(true)
                accStateCallback?.invoke(false)
                
                startBackgroundServiceOnly("car_power_shutdown_enter")
            }
            
            STATE_SHUTDOWN_EXIT -> {
                Log.d(TAG, "🚗 EXITING DEEP SLEEP STATE - CRITICAL WAKE UP")
                isSleeping = false
                igStatus = 1
                currentAccState = true
                
                storeSleepState(false)
                sleepStateCallback?.invoke(false)
                accStateCallback?.invoke(true)
                
                // MOST IMPORTANT: Deep sleep wake-up auto-start
                startBackgroundServiceOnly("car_power_shutdown_exit")
            }
            
            STATE_ACC_ON -> {
                Log.d(TAG, "🚗 ACC TURNED ON")
                if (!currentAccState) {
                    igStatus = 1
                    currentAccState = true
                    accStateCallback?.invoke(true)
                    
                    // Auto-start on ACC ON
                    startBackgroundServiceOnly("car_power_acc_on")
                }
            }
            
            STATE_ACC_OFF -> {
                Log.d(TAG, "🚗 ACC TURNED OFF")
                if (currentAccState) {
                    igStatus = 0
                    currentAccState = false
                    accStateCallback?.invoke(false)
                    
                    // Don't stop service on ACC OFF, just update state
                    Log.d(TAG, "ACC OFF - Service continues in background")
                }
            }
            
            STATE_ENGINE_ON -> {
                Log.d(TAG, "🚗 ENGINE ON (ACC also ON)")
                igStatus = 1
                currentAccState = true
                accStateCallback?.invoke(true)
                
                startBackgroundServiceOnly("car_power_engine_on")
            }
            
            STATE_FULL_POWER -> {
                Log.d(TAG, "🚗 FULL POWER ON")
                igStatus = 1
                currentAccState = true
                accStateCallback?.invoke(true)
                
                startBackgroundServiceOnly("car_power_full_power")
            }
            
            else -> {
                Log.w(TAG, "⚠️ Unknown power state: $state")
                // Try to interpret as ACC state
                val isAccOn = interpretPowerState(state)
                if (isAccOn != currentAccState) {
                    val oldStatus = igStatus
                    igStatus = if (isAccOn) 1 else 0
                    currentAccState = isAccOn
                    
                    Log.d(TAG, "Power state interpreted - ACC changed from $oldStatus to $igStatus")
                    accStateCallback?.invoke(isAccOn)
                    
                    if (isAccOn) {
                        startBackgroundServiceOnly("car_power_unknown_acc_on")
                    }
                }
            }
        }
        
        // Update last state tracking
        lastValidState = currentAccState
        stateChangeCount++
        lastStateChangeTime = System.currentTimeMillis()
    }

    private fun startBackgroundServiceOnly(trigger: String) {
        try {
            Log.d(TAG, "=== STARTING BACKGROUND SERVICE ONLY ===")
            Log.d(TAG, "Trigger: $trigger")
            Log.d(TAG, "ACC State: $igStatus")
            Log.d(TAG, "Sleep State: $isSleeping")
            
            val serviceIntent = Intent(context, BackgroundService::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                putExtra("started_by", trigger)
                putExtra("auto_started", true)
                putExtra("background_only", true) // CRITICAL: Background only
                putExtra("car_power_triggered", true) // Mark as car power triggered
                putExtra("current_ig_status", igStatus)
                putExtra("is_sleeping", isSleeping)
                
                // Add wake-up specific flags
                if (trigger.contains("wake_up") || trigger.contains("shutdown_exit")) {
                    putExtra("wake_up_from_sleep", true)
                    putExtra("background_only", true)
                }
                
                // Add sleep specific flags
                if (trigger.contains("sleep") || trigger.contains("shutdown_enter")) {
                    putExtra("sleep_keep_alive", true)
                    putExtra("background_only", true)
                }
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            
            Log.d(TAG, "✅ Background service started successfully (NO UI)")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error starting background service", e)
        }
    }

    private fun checkAndHandleSleepState() {
        try {
            Log.d(TAG, "Checking for existing sleep state...")
            
            val prefs = context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
            val wasSleeping = prefs.getBoolean("flutter.is_sleeping", false)
            
            if (wasSleeping) {
                Log.d(TAG, "🚗 Found interrupted sleep state - resuming sleep mode")
                isSleeping = true
                startBackgroundServiceOnly("car_power_resume_sleep")
            } else {
                Log.d(TAG, "No sleep state detected")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error checking sleep state", e)
        }
    }

    private fun storeSleepState(isSleeping: Boolean) {
        try {
            val prefs = context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
            prefs.edit().apply {
                putBoolean("flutter.is_sleeping", isSleeping)
                putLong("flutter.sleep_state_timestamp", System.currentTimeMillis())
                putInt("current_ig_status", igStatus)
                apply()
            }
            Log.d(TAG, "Stored sleep state: $isSleeping, igStatus: $igStatus")
        } catch (e: Exception) {
            Log.e(TAG, "Error storing sleep state", e)
        }
    }

    private fun interpretPowerState(state: Int): Boolean {
        // Based on your reference code and common automotive implementations
        return when (state) {
            0 -> false  // ACC OFF
            1 -> true   // ACC ON
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
                Log.d(TAG, "✅ Connected to car power manager")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to connect to car power manager", e)
            }
        }
    }

    fun disconnect() {
        if (isConnected) {
            try {
                carPowerManager?.unregisterPowerStateListener(powerStateListener)
                car?.disconnect()
                isConnected = false
                Log.d(TAG, "✅ Disconnected from car power manager")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to disconnect from car power manager", e)
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

    // Method to manually trigger auto-start for testing
    fun triggerManualAutoStart() {
        Log.d(TAG, "Manual auto-start trigger")
        startBackgroundServiceOnly("manual_trigger_test")
    }
}