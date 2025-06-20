package com.example.twtracking

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import bw.car.Car
import bw.car.CarNotConnectedException
import bw.car.power.CarPowerManager

class CarPowerManager(private val context: Context) {
    private var car: Car? = null
    private var carPowerManager: bw.car.power.CarPowerManager? = null
    private var powerStateListener: CarPowerManager.CarPowerStateListener? = null
    private var isConnected = false
    private var isInitialized = false
    private var accStateCallback: ((Boolean) -> Unit)? = null
    private var sleepStateCallback: ((Boolean) -> Unit)? = null
    private var currentAccState = false
    private var currentIgStatus = 0 // Default to 0 (ACC OFF)
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "CarPowerManager"
        
        // Enhanced power state constants
        private const val POWER_STATE_OFF = 0
        private const val POWER_STATE_ON = 1
        private const val POWER_STATE_SUSPEND = 2
        private const val POWER_STATE_WAIT_FOR_VHAL = 3
        private const val POWER_STATE_SHUTDOWN_PREPARE = 4
        private const val POWER_STATE_ON_DISP_OFF = 5
        private const val POWER_STATE_SHUTDOWN_POSTPONE = 6
        private const val POWER_STATE_SHUTDOWN_START = 7
        private const val POWER_STATE_SHUTDOWN_ENTER = 8
        private const val POWER_STATE_SHUTDOWN_PREPARE_UPDATE = 9
        private const val POWER_STATE_SUSPEND_EXIT = 10
        private const val POWER_STATE_SUSPEND_ENTER = 11
        private const val POWER_STATE_HIBERNATION_ENTER = 12
        private const val POWER_STATE_HIBERNATION_EXIT = 13
    }

    fun initialize() {
        try {
            Log.d(TAG, "=== INITIALIZING CAR POWER MANAGER ===")
            Log.d(TAG, "   - Context: ${context.javaClass.simpleName}")
            Log.d(TAG, "   - Thread: ${Thread.currentThread().name}")
            
            // Reset state
            isInitialized = false
            isConnected = false
            
            car = Car.createCar(context, object : android.content.ServiceConnection {
                override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
                    try {
                        Log.d(TAG, "🚗 Car service connected successfully")
                        Log.d(TAG, "   - Service name: ${name?.className}")
                        Log.d(TAG, "   - Thread: ${Thread.currentThread().name}")
                        
                        // Get car power manager
                        carPowerManager = car?.getCarManager(Car.POWER_SERVICE) as bw.car.power.CarPowerManager
                        
                        if (carPowerManager != null) {
                            Log.d(TAG, "✅ Car power manager obtained successfully")
                            isConnected = true
                            
                            // Setup power state listener on main thread
                            mainHandler.post {
                                setupPowerStateListener()
                            }
                        } else {
                            Log.e(TAG, "❌ Failed to get car power manager - null returned")
                        }
                        
                    } catch (e: CarNotConnectedException) {
                        Log.e(TAG, "❌ CarNotConnectedException getting car power manager", e)
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Unexpected error getting car power manager", e)
                    }
                }

                override fun onServiceDisconnected(name: android.content.ComponentName?) {
                    Log.w(TAG, "🚗 Car service disconnected")
                    Log.w(TAG, "   - Service name: ${name?.className}")
                    disconnect()
                }
            })
            
            // Connect to car service
            Log.d(TAG, "🔄 Connecting to car service...")
            car?.connect()
            
            // Set a timeout for initialization
            mainHandler.postDelayed({
                if (!isInitialized) {
                    Log.w(TAG, "⚠️ CarPowerManager initialization timeout - using fallback mode")
                    Log.w(TAG, "   - isConnected: $isConnected")
                    Log.w(TAG, "   - carPowerManager: ${carPowerManager != null}")
                    
                    // Try to get initial state anyway
                    tryGetInitialState()
                }
            }, 5000) // 5 second timeout
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize CarPowerManager", e)
            Log.e(TAG, "   - Exception type: ${e.javaClass.simpleName}")
            Log.e(TAG, "   - Exception message: ${e.message}")
            
            // Try fallback initialization
            tryFallbackInitialization()
        }
    }

    private fun setupPowerStateListener() {
        try {
            Log.d(TAG, "🔄 Setting up power state listener...")
            
            powerStateListener = object : CarPowerManager.CarPowerStateListener {
                override fun onPowerStateChanged(state: Int) {
                    val oldIgStatus = currentIgStatus
                    val isAccOn = isPowerStateAccOn(state)
                    currentIgStatus = if (isAccOn) 1 else 0
                    
                    Log.d(TAG, "🚗 POWER STATE CHANGED:")
                    Log.d(TAG, "   - Raw State: $state")
                    Log.d(TAG, "   - State Name: ${getPowerStateName(state)}")
                    Log.d(TAG, "   - ACC ON: $isAccOn")
                    Log.d(TAG, "   - igStatus: $oldIgStatus → $currentIgStatus")
                    Log.d(TAG, "   - Timestamp: ${System.currentTimeMillis()}")
                    Log.d(TAG, "   - Thread: ${Thread.currentThread().name}")
                    
                    currentAccState = isAccOn
                    
                    // Trigger callback on main thread
                    mainHandler.post {
                        accStateCallback?.invoke(isAccOn)
                        Log.d(TAG, "✅ ACC state callback invoked with: $isAccOn")
                    }
                }
            }
            
            carPowerManager?.registerPowerStateListener(powerStateListener)
            Log.d(TAG, "✅ Power state listener registered successfully")
            
            // Get initial state immediately after registering listener
            tryGetInitialState()
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to setup power state listener", e)
            tryGetInitialState()
        }
    }

    private fun tryGetInitialState() {
        try {
            Log.d(TAG, "🔄 Getting initial power state...")
            
            if (carPowerManager != null) {
                val initialState = carPowerManager?.getPowerState() ?: POWER_STATE_OFF
                val isAccOn = isPowerStateAccOn(initialState)
                currentAccState = isAccOn
                currentIgStatus = if (isAccOn) 1 else 0
                
                Log.d(TAG, "🚗 INITIAL STATE DETECTED:")
                Log.d(TAG, "   - Initial state: $initialState")
                Log.d(TAG, "   - State Name: ${getPowerStateName(initialState)}")
                Log.d(TAG, "   - ACC ON: $isAccOn")
                Log.d(TAG, "   - igStatus: $currentIgStatus")
                Log.d(TAG, "   - Timestamp: ${System.currentTimeMillis()}")
                
                // Trigger initial callback on main thread
                mainHandler.post {
                    accStateCallback?.invoke(isAccOn)
                    Log.d(TAG, "✅ Initial ACC state callback invoked with: $isAccOn")
                }
                
            } else {
                Log.w(TAG, "⚠️ carPowerManager is null, using default state")
                currentAccState = false
                currentIgStatus = 0  // Default to 0 (ACC OFF)
                
                mainHandler.post {
                    accStateCallback?.invoke(false)
                    Log.d(TAG, "✅ Default ACC state callback invoked with: false")
                }
            }
            
            isInitialized = true
            Log.d(TAG, "✅ CarPowerManager initialization completed. Final igStatus: $currentIgStatus")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to get initial power state", e)
            
            // Use default state
            currentAccState = false
            currentIgStatus = 0  // Default to 0 (ACC OFF)
            
            mainHandler.post {
                accStateCallback?.invoke(false)
                Log.d(TAG, "✅ Fallback ACC state callback invoked with: false")
            }
            
            isInitialized = true
            Log.d(TAG, "✅ CarPowerManager initialization completed with fallback. Final igStatus: $currentIgStatus")
        }
    }

    private fun tryFallbackInitialization() {
        try {
            Log.d(TAG, "🔄 Trying fallback initialization...")
            
            // Set default state
            currentAccState = false
            currentIgStatus = 0  // Default to 0 (ACC OFF)
            
            mainHandler.post {
                accStateCallback?.invoke(false)
                Log.d(TAG, "✅ Fallback ACC state callback invoked with: false")
            }
            
            isInitialized = true
            Log.d(TAG, "✅ CarPowerManager fallback initialization completed. Final igStatus: $currentIgStatus")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Fallback initialization also failed", e)
            isInitialized = true
        }
    }

    // UPDATED METHOD: More permissive power state detection
    private fun isPowerStateAccOn(state: Int): Boolean {
        val result = when (state) {
            POWER_STATE_ON -> true
            POWER_STATE_ON_DISP_OFF -> true
            POWER_STATE_SUSPEND_EXIT -> true
            POWER_STATE_HIBERNATION_EXIT -> true
            POWER_STATE_WAIT_FOR_VHAL -> true
            POWER_STATE_OFF -> false
            POWER_STATE_SUSPEND -> false
            POWER_STATE_SUSPEND_ENTER -> false
            POWER_STATE_SHUTDOWN_PREPARE -> false
            POWER_STATE_SHUTDOWN_POSTPONE -> false
            POWER_STATE_SHUTDOWN_START -> false
            POWER_STATE_SHUTDOWN_ENTER -> false
            POWER_STATE_SHUTDOWN_PREPARE_UPDATE -> false
            POWER_STATE_HIBERNATION_ENTER -> false
            else -> {
                // NEW: More permissive approach - treat any unknown state as potentially ACC ON
                // This is because different car manufacturers might use different state values
                Log.w(TAG, "⚠️ Unknown power state: $state, treating as ACC ON (permissive mode)")
                true
            }
        }
        
        Log.d(TAG, "🔍 Power state analysis: state=$state, name=${getPowerStateName(state)}, isAccOn=$result")
        return result
    }

    // NEW METHOD: Get power state name for debugging
    private fun getPowerStateName(state: Int): String {
        return when (state) {
            POWER_STATE_OFF -> "POWER_STATE_OFF"
            POWER_STATE_ON -> "POWER_STATE_ON"
            POWER_STATE_SUSPEND -> "POWER_STATE_SUSPEND"
            POWER_STATE_WAIT_FOR_VHAL -> "POWER_STATE_WAIT_FOR_VHAL"
            POWER_STATE_SHUTDOWN_PREPARE -> "POWER_STATE_SHUTDOWN_PREPARE"
            POWER_STATE_ON_DISP_OFF -> "POWER_STATE_ON_DISP_OFF"
            POWER_STATE_SHUTDOWN_POSTPONE -> "POWER_STATE_SHUTDOWN_POSTPONE"
            POWER_STATE_SHUTDOWN_START -> "POWER_STATE_SHUTDOWN_START"
            POWER_STATE_SHUTDOWN_ENTER -> "POWER_STATE_SHUTDOWN_ENTER"
            POWER_STATE_SHUTDOWN_PREPARE_UPDATE -> "POWER_STATE_SHUTDOWN_PREPARE_UPDATE"
            POWER_STATE_SUSPEND_EXIT -> "POWER_STATE_SUSPEND_EXIT"
            POWER_STATE_SUSPEND_ENTER -> "POWER_STATE_SUSPEND_ENTER"
            POWER_STATE_HIBERNATION_ENTER -> "POWER_STATE_HIBERNATION_ENTER"
            POWER_STATE_HIBERNATION_EXIT -> "POWER_STATE_HIBERNATION_EXIT"
            else -> "UNKNOWN_STATE_$state"
        }
    }

    fun getCurrentAccState(): Boolean {
        return currentAccState
    }

    fun setAccStateCallback(callback: (Boolean) -> Unit) {
        accStateCallback = callback
    }

    // NEW METHOD: Set sleep state callback (for compatibility)
    fun setSleepStateCallback(callback: (Boolean) -> Unit) {
        sleepStateCallback = callback
        Log.d(TAG, "Sleep state callback set (not implemented in simplified version)")
    }

    // NEW METHOD: Get current sleep state (for compatibility)
    fun getCurrentSleepState(): Boolean {
        // In simplified version, we don't track sleep state
        // Return false (not sleeping) as default
        Log.d(TAG, "getCurrentSleepState called (returning false in simplified version)")
        return false
    }

    // NEW METHOD: Get current igStatus (for compatibility)
    fun getCurrentIgStatus(): Int {
        return currentIgStatus
    }

    // NEW METHOD: Test and log current power state (for debugging)
    fun testCurrentPowerState() {
        try {
            Log.d(TAG, "🧪 TESTING CURRENT POWER STATE:")
            Log.d(TAG, "   - isConnected: $isConnected")
            Log.d(TAG, "   - carPowerManager: ${carPowerManager != null}")
            Log.d(TAG, "   - currentAccState: $currentAccState")
            Log.d(TAG, "   - currentIgStatus: $currentIgStatus")
            
            if (carPowerManager != null) {
                try {
                    val powerState = carPowerManager?.getPowerState()
                    val stateName = getPowerStateName(powerState ?: -1)
                    val isAccOn = isPowerStateAccOn(powerState ?: -1)
                    
                    Log.d(TAG, "   - Raw power state: $powerState")
                    Log.d(TAG, "   - State name: $stateName")
                    Log.d(TAG, "   - ACC ON: $isAccOn")
                    Log.d(TAG, "   - Should igStatus be: ${if (isAccOn) 1 else 0}")
                } catch (e: Exception) {
                    Log.e(TAG, "   - Error getting power state: ${e.message}")
                }
            } else {
                Log.w(TAG, "   - carPowerManager is null")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error testing power state", e)
        }
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
        try {
            Log.d(TAG, "Disconnecting CarPowerManager")
            
            // Unregister power state listener
            powerStateListener?.let { listener ->
                try {
                    carPowerManager?.unregisterPowerStateListener(listener)
                    Log.d(TAG, "Power state listener unregistered")
                } catch (e: Exception) {
                    Log.e(TAG, "Error unregistering power state listener", e)
                }
            }
            
            // Disconnect car
            car?.disconnect()
            car = null
            carPowerManager = null
            powerStateListener = null
            
            Log.d(TAG, "CarPowerManager disconnected")
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting CarPowerManager", e)
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

    fun getCurrentPowerStatus(): Map<String, Any> {
        return mapOf(
            "isConnected" to isConnected,
            "currentAccState" to currentAccState,
            "currentIgStatus" to currentIgStatus
        )
    }

    // NEW METHOD: Force update igStatus based on current power state
    fun forceUpdateIgStatus() {
        try {
            Log.d(TAG, "🔄 FORCE UPDATING IG STATUS")
            Log.d(TAG, "   - isInitialized: $isInitialized")
            Log.d(TAG, "   - isConnected: $isConnected")
            Log.d(TAG, "   - carPowerManager: ${carPowerManager != null}")
            
            if (carPowerManager != null && isConnected) {
                try {
                    val currentState = carPowerManager?.getPowerState() ?: POWER_STATE_OFF
                    val isAccOn = isPowerStateAccOn(currentState)
                    val oldIgStatus = currentIgStatus
                    currentIgStatus = if (isAccOn) 1 else 0
                    
                    Log.d(TAG, "   - Current state: $currentState")
                    Log.d(TAG, "   - State name: ${getPowerStateName(currentState)}")
                    Log.d(TAG, "   - ACC ON: $isAccOn")
                    Log.d(TAG, "   - igStatus: $oldIgStatus → $currentIgStatus")
                    
                    currentAccState = isAccOn
                    
                    // Trigger callback on main thread
                    mainHandler.post {
                        accStateCallback?.invoke(isAccOn)
                        Log.d(TAG, "✅ Force update callback invoked with: $isAccOn")
                    }
                    
                    Log.d(TAG, "✅ Force update completed successfully")
                    
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error getting current power state for force update", e)
                    Log.e(TAG, "   - Exception type: ${e.javaClass.simpleName}")
                    Log.e(TAG, "   - Exception message: ${e.message}")
                    
                    // Try to reinitialize if there was an error
                    Log.d(TAG, "🔄 Attempting to reinitialize after error...")
                    initialize()
                }
            } else {
                Log.w(TAG, "⚠️ carPowerManager is null or not connected, attempting reinitialization")
                Log.w(TAG, "   - carPowerManager: ${carPowerManager != null}")
                Log.w(TAG, "   - isConnected: $isConnected")
                Log.w(TAG, "   - isInitialized: $isInitialized")
                
                // Try to reinitialize
                initialize()
                
                // Return current status as fallback
                Log.d(TAG, "   - Returning current igStatus as fallback: $currentIgStatus")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in force update igStatus", e)
            Log.e(TAG, "   - Exception type: ${e.javaClass.simpleName}")
            Log.e(TAG, "   - Exception message: ${e.message}")
        }
    }

    // NEW METHOD: Check if CarPowerManager is properly initialized
    fun isProperlyInitialized(): Boolean {
        val isProper = isInitialized && isConnected && carPowerManager != null
        Log.d(TAG, "🔍 CarPowerManager initialization check:")
        Log.d(TAG, "   - isInitialized: $isInitialized")
        Log.d(TAG, "   - isConnected: $isConnected")
        Log.d(TAG, "   - carPowerManager: ${carPowerManager != null}")
        Log.d(TAG, "   - Result: $isProper")
        return isProper
    }

    // NEW METHOD: Get detailed status for debugging
    fun getDetailedStatus(): Map<String, Any> {
        return mapOf(
            "isInitialized" to isInitialized,
            "isConnected" to isConnected,
            "carPowerManagerExists" to (carPowerManager != null),
            "currentAccState" to currentAccState,
            "currentIgStatus" to currentIgStatus,
            "hasAccCallback" to (accStateCallback != null),
            "hasSleepCallback" to (sleepStateCallback != null),
            "hasPowerStateListener" to (powerStateListener != null)
        )
    }

    // NEW METHOD: Manually set igStatus (for debugging)
    fun setIgStatusManually(status: Int) {
        try {
            val oldStatus = currentIgStatus
            currentIgStatus = status
            currentAccState = (status == 1)
            
            Log.d(TAG, "🔧 MANUAL IG STATUS SET:")
            Log.d(TAG, "   - Old igStatus: $oldStatus")
            Log.d(TAG, "   - New igStatus: $status")
            Log.d(TAG, "   - ACC state: $currentAccState")
            Log.d(TAG, "   - Timestamp: ${System.currentTimeMillis()}")
            
            // Trigger callback on main thread
            mainHandler.post {
                accStateCallback?.invoke(currentAccState)
                Log.d(TAG, "✅ Manual igStatus callback invoked with: $currentAccState")
            }
            
            Log.d(TAG, "✅ Manual igStatus set completed")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error setting igStatus manually", e)
        }
    }
}