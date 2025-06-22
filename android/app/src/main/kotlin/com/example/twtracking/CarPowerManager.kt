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
            // FORCE BASIC LOGS FOR AVN DEBUGGING
            Log.i("CarPowerManager", "INFO: CarPowerManager.initialize() called")
            Log.w("CarPowerManager", "WARNING: CarPowerManager initialization starting")
            Log.e("CarPowerManager", "ERROR: This is a test error log from CarPowerManager")
            
            // Add ERROR level logs for device info since only ERROR logs show
            Log.e("CarPowerManager", "ERROR: Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            Log.e("CarPowerManager", "ERROR: Android Version: ${android.os.Build.VERSION.SDK_INT}")
            Log.e("CarPowerManager", "ERROR: Context: ${context.javaClass.simpleName}")
            
            Log.d(TAG, "=== INITIALIZING CAR POWER MANAGER ===")
            Log.d(TAG, "   - Context: ${context.javaClass.simpleName}")
            Log.d(TAG, "   - Thread: ${Thread.currentThread().name}")
            Log.d(TAG, "   - Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            Log.d(TAG, "   - Android Version: ${android.os.Build.VERSION.SDK_INT}")
            
            // Reset state
            isInitialized = false
            isConnected = false
            
            // Check if car service is available
            val packageManager = context.packageManager
            val carServiceAvailable = packageManager.hasSystemFeature("android.hardware.type.automotive")
            Log.d(TAG, "   - Car service available: $carServiceAvailable")
            Log.e("CarPowerManager", "ERROR: Car service available: $carServiceAvailable")
            
            // NEW: Check if this is a BWIC A100 device
            val isBwicA100 = android.os.Build.MANUFACTURER.contains("BWIC", ignoreCase = true) && 
                            android.os.Build.MODEL.contains("A100", ignoreCase = true)
            Log.e("CarPowerManager", "ERROR: Is BWIC A100 device: $isBwicA100")
            
            // NEW: Check device type - BWIC A100 should be treated as automotive even if not detected
            val isAutomotiveDevice = carServiceAvailable || isBwicA100
            Log.d(TAG, "   - Is Automotive Device: $isAutomotiveDevice")
            Log.e("CarPowerManager", "ERROR: Is Automotive Device: $isAutomotiveDevice")
            Log.d(TAG, "   - Expected igStatus on this device: ${if (isAutomotiveDevice) "0 or 1 (depending on ACC)" else "0 (no car ignition)"}")
            Log.e("CarPowerManager", "ERROR: Expected igStatus on this device: ${if (isAutomotiveDevice) "0 or 1 (depending on ACC)" else "0 (no car ignition)"}")
            
            if (!isAutomotiveDevice) {
                Log.w(TAG, "⚠️ Car service not available on this device - using fallback mode")
                Log.e("CarPowerManager", "ERROR: Car service not available - using fallback mode")
                Log.w(TAG, "   - This is expected for non-automotive devices like Infinix phones")
                Log.e("CarPowerManager", "ERROR: This is expected for non-automotive devices")
                Log.w(TAG, "   - igStatus will be 0 (ACC OFF) on this device")
                Log.e("CarPowerManager", "ERROR: igStatus will be 0 (ACC OFF) on this device")
                tryFallbackInitialization()
                return
            }
            
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
                            tryFallbackInitialization()
                        }
                        
                    } catch (e: CarNotConnectedException) {
                        Log.e(TAG, "❌ CarNotConnectedException getting car power manager", e)
                        tryFallbackInitialization()
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Unexpected error getting car power manager", e)
                        Log.e(TAG, "   - Exception type: ${e.javaClass.simpleName}")
                        Log.e(TAG, "   - Exception message: ${e.message}")
                        tryFallbackInitialization()
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
            }, 3000) // Reduced timeout to 3 seconds
            
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
                    val newIgStatus = if (isAccOn) 1 else 0
                    
                    Log.e("CarPowerManager", "ERROR: 🚗 POWER STATE CHANGED:")
                    Log.e("CarPowerManager", "ERROR:    - Raw State: $state")
                    Log.e("CarPowerManager", "ERROR:    - State Name: ${getPowerStateName(state)}")
                    Log.e("CarPowerManager", "ERROR:    - ACC ON: $isAccOn")
                    Log.e("CarPowerManager", "ERROR:    - igStatus: $oldIgStatus → $newIgStatus")
                    Log.e("CarPowerManager", "ERROR:    - Timestamp: ${System.currentTimeMillis()}")
                    Log.e("CarPowerManager", "ERROR:    - Thread: ${Thread.currentThread().name}")
                    
                    // Only update if there's an actual change to prevent fluctuations
                    if (newIgStatus != oldIgStatus) {
                        currentIgStatus = newIgStatus
                        currentAccState = isAccOn
                        
                        // Save to SharedPreferences for persistence
                        saveIgStatusToPrefs(newIgStatus)
                        
                        Log.e("CarPowerManager", "ERROR: ✅ igStatus UPDATED: $oldIgStatus → $newIgStatus")
                        
                        // Trigger callback on main thread
                        mainHandler.post {
                            accStateCallback?.invoke(isAccOn)
                            Log.e("CarPowerManager", "ERROR: ✅ ACC state callback invoked with: $isAccOn")
                        }
                    } else {
                        Log.e("CarPowerManager", "ERROR: ℹ️ igStatus unchanged: $oldIgStatus (no fluctuation)")
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
                val newIgStatus = if (isAccOn) 1 else 0
                
                // Load saved state from SharedPreferences
                val savedIgStatus = loadIgStatusFromPrefs()
                
                // Use the saved state if it's different from current detection
                if (savedIgStatus != -1 && savedIgStatus != newIgStatus) {
                    Log.e("CarPowerManager", "ERROR: 🔄 Using saved igStatus: $savedIgStatus (detected: $newIgStatus)")
                    currentIgStatus = savedIgStatus
                    currentAccState = (savedIgStatus == 1)
                } else {
                    currentIgStatus = newIgStatus
                    currentAccState = isAccOn
                    // Save the detected state
                    saveIgStatusToPrefs(newIgStatus)
                }
                
                Log.e("CarPowerManager", "ERROR: 🚗 INITIAL STATE DETECTED:")
                Log.e("CarPowerManager", "ERROR:    - Initial state: $initialState")
                Log.e("CarPowerManager", "ERROR:    - State Name: ${getPowerStateName(initialState)}")
                Log.e("CarPowerManager", "ERROR:    - ACC ON: $isAccOn")
                Log.e("CarPowerManager", "ERROR:    - igStatus: $currentIgStatus")
                Log.e("CarPowerManager", "ERROR:    - Timestamp: ${System.currentTimeMillis()}")
                
                // Trigger initial callback on main thread
                mainHandler.post {
                    accStateCallback?.invoke(currentAccState)
                    Log.e("CarPowerManager", "ERROR: ✅ Initial ACC state callback invoked with: $currentAccState")
                }
                
            } else {
                Log.w(TAG, "⚠️ carPowerManager is null, using saved state")
                val savedIgStatus = loadIgStatusFromPrefs()
                currentIgStatus = savedIgStatus
                currentAccState = (savedIgStatus == 1)
                
                mainHandler.post {
                    accStateCallback?.invoke(currentAccState)
                    Log.e("CarPowerManager", "ERROR: ✅ Saved state callback invoked with: $currentAccState")
                }
            }
            
            isInitialized = true
            Log.e("CarPowerManager", "ERROR: ✅ CarPowerManager initialization completed. Final igStatus: $currentIgStatus")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to get initial power state", e)
            
            // Use saved state as fallback
            val savedIgStatus = loadIgStatusFromPrefs()
            currentIgStatus = savedIgStatus
            currentAccState = (savedIgStatus == 1)
            
            mainHandler.post {
                accStateCallback?.invoke(currentAccState)
                Log.e("CarPowerManager", "ERROR: ✅ Fallback state callback invoked with: $currentAccState")
            }
            
            isInitialized = true
            Log.e("CarPowerManager", "ERROR: ✅ CarPowerManager initialization completed with fallback. Final igStatus: $currentIgStatus")
        }
    }

    private fun tryFallbackInitialization() {
        try {
            Log.d(TAG, "🔄 Trying fallback initialization...")
            Log.e("CarPowerManager", "ERROR: Starting fallback initialization for BWIC A100")
            
            // Check if this is a BWIC A100 device
            val isBwicA100 = android.os.Build.MANUFACTURER.contains("BWIC", ignoreCase = true) && 
                            android.os.Build.MODEL.contains("A100", ignoreCase = true)
            
            Log.e("CarPowerManager", "ERROR: Is BWIC A100 device: $isBwicA100")
            
            if (isBwicA100) {
                Log.e("CarPowerManager", "ERROR: Using BWIC car framework for BWIC A100")
                
                // Try to use BWIC car framework directly
                try {
                    car = Car.createCar(context, object : android.content.ServiceConnection {
                        override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
                            try {
                                Log.e("CarPowerManager", "ERROR: BWIC car service connected in fallback")
                                
                                // Get car power manager using BWIC framework
                                carPowerManager = car?.getCarManager(Car.POWER_SERVICE) as bw.car.power.CarPowerManager
                                
                                if (carPowerManager != null) {
                                    Log.e("CarPowerManager", "ERROR: BWIC car power manager obtained successfully")
                                    isConnected = true
                                    
                                    // Get current power state from BWIC car framework
                                    val powerState = carPowerManager?.getPowerState() ?: POWER_STATE_OFF
                                    val isAccOn = isPowerStateAccOn(powerState)
                                    currentAccState = isAccOn
                                    currentIgStatus = if (isAccOn) 1 else 0
                                    
                                    Log.e("CarPowerManager", "ERROR: BWIC fallback - power state: $powerState, ACC ON: $isAccOn, igStatus: $currentIgStatus")
                                    
                                    // Setup power state listener
                                    setupPowerStateListener()
                                    
                                } else {
                                    Log.e("CarPowerManager", "ERROR: BWIC car power manager is null")
                                    useCustomBwicDetection()
                                }
                                
                            } catch (e: Exception) {
                                Log.e("CarPowerManager", "ERROR: Exception in BWIC fallback: ${e.message}")
                                useCustomBwicDetection()
                            }
                        }

                        override fun onServiceDisconnected(name: android.content.ComponentName?) {
                            Log.e("CarPowerManager", "ERROR: BWIC car service disconnected")
                        }
                    })
                    
                    car?.connect()
                    
                } catch (e: Exception) {
                    Log.e("CarPowerManager", "ERROR: Failed to use BWIC car framework: ${e.message}")
                    useCustomBwicDetection()
                }
                
            } else {
                // Use default fallback for other devices
                Log.e("CarPowerManager", "ERROR: Using default fallback for non-BWIC device")
                currentAccState = false
                // Don't override currentIgStatus - preserve the actual detected state
                Log.e("CarPowerManager", "ERROR: Non-BWIC device fallback, but preserving current igStatus: $currentIgStatus")
                
                mainHandler.post {
                    accStateCallback?.invoke(false)
                    Log.e("CarPowerManager", "ERROR: Default fallback callback invoked with: false")
                }
            }
            
            isInitialized = true
            Log.e("CarPowerManager", "ERROR: Fallback initialization completed. Final igStatus: $currentIgStatus")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Fallback initialization also failed", e)
            Log.e("CarPowerManager", "ERROR: Fallback initialization failed: ${e.message}")
            isInitialized = true
        }
    }

    // NEW METHOD: Use custom BWIC detection as last resort
    private fun useCustomBwicDetection() {
        Log.e("CarPowerManager", "ERROR: Using custom BWIC detection as fallback")
        val isIgnitionOn = detectBwicA100Ignition()
        currentAccState = isIgnitionOn
        currentIgStatus = if (isIgnitionOn) 1 else 0
        
        Log.e("CarPowerManager", "ERROR: Custom BWIC detection - ACC ON: $isIgnitionOn, igStatus: $currentIgStatus")
        
        mainHandler.post {
            accStateCallback?.invoke(isIgnitionOn)
            Log.e("CarPowerManager", "ERROR: Custom BWIC detection callback invoked with: $isIgnitionOn")
        }
    }

    // UPDATED METHOD: Power state detection matching the working BWIC sample code
    private fun isPowerStateAccOn(state: Int): Boolean {
        // Based on the working BWIC sample code: boolean acc_on = i == 1;
        // Power state 1 means ACC ON, anything else means ACC OFF
        val result = (state == POWER_STATE_ON)
        
        Log.e("CarPowerManager", "ERROR: Power state analysis: state=$state, isAccOn=$result")
        Log.e("CarPowerManager", "ERROR: Using BWIC sample logic: state == 1 (POWER_STATE_ON)")
        Log.e("CarPowerManager", "ERROR: Result: $result")
        
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

    // NEW METHOD: Debug power states to understand AVN behavior
    fun debugPowerStates() {
        try {
            Log.d(TAG, "🧪 DEBUGGING POWER STATES:")
            Log.d(TAG, "   - Testing all possible power states...")
            
            val testStates = listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13)
            
            for (state in testStates) {
                val isAccOn = isPowerStateAccOn(state)
                val stateName = getPowerStateName(state)
                Log.d(TAG, "   - State $state ($stateName): ACC ON = $isAccOn")
            }
            
            Log.d(TAG, "   - Current actual state from carPowerManager:")
            if (carPowerManager != null) {
                try {
                    val actualState = carPowerManager?.getPowerState() ?: -1
                    val actualStateName = getPowerStateName(actualState)
                    val actualIsAccOn = isPowerStateAccOn(actualState)
                    
                    Log.d(TAG, "   - Actual state: $actualState ($actualStateName)")
                    Log.d(TAG, "   - Actual ACC ON: $actualIsAccOn")
                    Log.d(TAG, "   - Current igStatus: $currentIgStatus")
                    Log.d(TAG, "   - Current ACC state: $currentAccState")
                } catch (e: Exception) {
                    Log.e(TAG, "   - Error getting actual state: ${e.message}")
                }
            } else {
                Log.w(TAG, "   - carPowerManager is null")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error debugging power states", e)
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

    // NEW METHOD: Simulate ACC state change for testing
    fun simulateAccStateChange(isAccOn: Boolean) {
        try {
            val newIgStatus = if (isAccOn) 1 else 0
            val oldStatus = currentIgStatus
            
            Log.d(TAG, "🧪 SIMULATING ACC STATE CHANGE:")
            Log.d(TAG, "   - Old igStatus: $oldStatus")
            Log.d(TAG, "   - New igStatus: $newIgStatus")
            Log.d(TAG, "   - ACC ON: $isAccOn")
            Log.d(TAG, "   - Timestamp: ${System.currentTimeMillis()}")
            
            currentIgStatus = newIgStatus
            currentAccState = isAccOn
            
            // Trigger callback on main thread
            mainHandler.post {
                accStateCallback?.invoke(isAccOn)
                Log.d(TAG, "✅ Simulated ACC state callback invoked with: $isAccOn")
            }
            
            Log.d(TAG, "✅ ACC state simulation completed")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error simulating ACC state change", e)
        }
    }

    // NEW METHOD: Test specific power state for debugging
    fun testSpecificPowerState(testState: Int) {
        try {
            Log.d(TAG, "🧪 TESTING SPECIFIC POWER STATE: $testState")
            
            val stateName = getPowerStateName(testState)
            val isAccOn = isPowerStateAccOn(testState)
            
            Log.d(TAG, "   - Test state: $testState")
            Log.d(TAG, "   - State name: $stateName")
            Log.d(TAG, "   - ACC ON: $isAccOn")
            Log.d(TAG, "   - Would set igStatus to: ${if (isAccOn) 1 else 0}")
            
            // Simulate this state change
            val oldIgStatus = currentIgStatus
            currentIgStatus = if (isAccOn) 1 else 0
            currentAccState = isAccOn
            
            Log.d(TAG, "   - Simulated igStatus change: $oldIgStatus → $currentIgStatus")
            
            // Trigger callback on main thread
            mainHandler.post {
                accStateCallback?.invoke(isAccOn)
                Log.d(TAG, "✅ Test power state callback invoked with: $isAccOn")
            }
            
            Log.d(TAG, "✅ Specific power state test completed")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error testing specific power state", e)
        }
    }

    // NEW METHOD: Get all possible power states for AVN debugging
    fun getAllPowerStatesInfo(): Map<String, Any> {
        val statesInfo = mutableMapOf<String, Any>()
        
        for (state in 0..13) {
            val stateName = getPowerStateName(state)
            val isAccOn = isPowerStateAccOn(state)
            statesInfo["state_$state"] = mapOf(
                "name" to stateName,
                "isAccOn" to isAccOn,
                "igStatus" to if (isAccOn) 1 else 0
            )
        }
        
        Log.d(TAG, "📋 ALL POWER STATES INFO:")
        statesInfo.forEach { (key, value) ->
            Log.d(TAG, "   - $key: $value")
        }
        
        return statesInfo
    }

    // NEW METHOD: Custom ignition detection for BWIC A100 AVN
    private fun detectBwicA100Ignition(): Boolean {
        try {
            Log.e("CarPowerManager", "ERROR: Detecting BWIC A100 ignition state...")
            
            // Method 1: Check if device is connected to power (USB/charging)
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            val isPlugged = batteryManager.isCharging
            Log.e("CarPowerManager", "ERROR: Battery charging: $isPlugged")
            
            // Method 2: Check if screen is on (indicates ACC ON)
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            val isScreenOn = powerManager.isInteractive
            Log.e("CarPowerManager", "ERROR: Screen interactive: $isScreenOn")
            
            // Method 3: Check if we're in a vehicle context (GPS accuracy, movement patterns)
            // This would require location data, but we can use a simple heuristic
            
            // Method 4: Check for specific BWIC A100 system properties
            val systemProperties = try {
                val c = Class.forName("android.os.SystemProperties")
                val get = c.getMethod("get", String::class.java, String::class.java)
                val ignitionProperty = get.invoke(c, "ro.bwic.ignition", "0") as String
                Log.e("CarPowerManager", "ERROR: BWIC ignition property: $ignitionProperty")
                ignitionProperty == "1"
            } catch (e: Exception) {
                Log.e("CarPowerManager", "ERROR: Could not read BWIC system property: ${e.message}")
                false
            }
            
            // Method 5: Check for USB connection (often indicates ACC ON in vehicles)
            val usbManager = context.getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
            val deviceList = usbManager.deviceList
            val hasUsbDevices = deviceList.isNotEmpty()
            Log.e("CarPowerManager", "ERROR: USB devices connected: $hasUsbDevices")
            
            // Combine multiple indicators for better accuracy
            val ignitionIndicators = mutableListOf<Boolean>()
            
            // Screen on is a strong indicator of ACC ON
            if (isScreenOn) {
                ignitionIndicators.add(true)
                Log.e("CarPowerManager", "ERROR: Screen on - likely ACC ON")
            }
            
            // USB connection is another indicator
            if (hasUsbDevices) {
                ignitionIndicators.add(true)
                Log.e("CarPowerManager", "ERROR: USB connected - likely ACC ON")
            }
            
            // System property if available
            if (systemProperties) {
                ignitionIndicators.add(true)
                Log.e("CarPowerManager", "ERROR: System property indicates ACC ON")
            }
            
            // Determine final ignition state
            val isIgnitionOn = ignitionIndicators.isNotEmpty() && ignitionIndicators.count { it } >= ignitionIndicators.size / 2
            
            Log.e("CarPowerManager", "ERROR: BWIC A100 ignition detection result: $isIgnitionOn")
            Log.e("CarPowerManager", "ERROR: Indicators: screen=$isScreenOn, usb=$hasUsbDevices, system=$systemProperties")
            
            return isIgnitionOn
            
        } catch (e: Exception) {
            Log.e("CarPowerManager", "ERROR: Exception in BWIC A100 ignition detection: ${e.message}")
            return false
        }
    }

    // NEW METHOD: Test BWIC A100 ignition detection using BWIC car framework
    fun testBwicA100IgnitionDetection() {
        try {
            Log.e("CarPowerManager", "ERROR: === TESTING BWIC A100 IGNITION DETECTION ===")
            
            if (carPowerManager != null && isConnected) {
                Log.e("CarPowerManager", "ERROR: Using BWIC car framework for testing")
                
                // Get current power state from BWIC car framework
                val powerState = carPowerManager?.getPowerState() ?: POWER_STATE_OFF
                val isAccOn = isPowerStateAccOn(powerState)
                val oldIgStatus = currentIgStatus
                currentIgStatus = if (isAccOn) 1 else 0
                currentAccState = isAccOn
                
                Log.e("CarPowerManager", "ERROR: BWIC framework test - power state: $powerState")
                Log.e("CarPowerManager", "ERROR: BWIC framework test - ACC ON: $isAccOn")
                Log.e("CarPowerManager", "ERROR: BWIC framework test - igStatus: $oldIgStatus → $currentIgStatus")
                
                // Trigger callback on main thread
                mainHandler.post {
                    accStateCallback?.invoke(isAccOn)
                    Log.e("CarPowerManager", "ERROR: BWIC framework test callback invoked with: $isAccOn")
                }
                
            } else {
                Log.e("CarPowerManager", "ERROR: BWIC car framework not available, using custom detection")
                
                // Fall back to custom detection
                val isIgnitionOn = detectBwicA100Ignition()
                val oldIgStatus = currentIgStatus
                currentIgStatus = if (isIgnitionOn) 1 else 0
                currentAccState = isIgnitionOn
                
                Log.e("CarPowerManager", "ERROR: Custom detection test - ACC ON: $isIgnitionOn")
                Log.e("CarPowerManager", "ERROR: Custom detection test - igStatus: $oldIgStatus → $currentIgStatus")
                
                // Trigger callback on main thread
                mainHandler.post {
                    accStateCallback?.invoke(isIgnitionOn)
                    Log.e("CarPowerManager", "ERROR: Custom detection test callback invoked with: $isIgnitionOn")
                }
            }
            
            Log.e("CarPowerManager", "ERROR: BWIC A100 ignition detection test completed")
            
        } catch (e: Exception) {
            Log.e("CarPowerManager", "ERROR: Exception in BWIC A100 ignition detection test: ${e.message}")
        }
    }

    // NEW METHOD: Save igStatus to SharedPreferences for persistence
    private fun saveIgStatusToPrefs(igStatus: Int) {
        try {
            val prefs = context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
            prefs.edit().apply {
                putInt("current_ig_status", igStatus)
                putLong("ig_status_timestamp", System.currentTimeMillis())
                apply()
            }
            Log.e("CarPowerManager", "ERROR: ✅ igStatus saved to prefs: $igStatus")
        } catch (e: Exception) {
            Log.e("CarPowerManager", "ERROR: ❌ Failed to save igStatus to prefs: ${e.message}")
        }
    }

    // NEW METHOD: Load igStatus from SharedPreferences
    private fun loadIgStatusFromPrefs(): Int {
        try {
            val prefs = context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
            val savedIgStatus = prefs.getInt("current_ig_status", -1)
            val timestamp = prefs.getLong("ig_status_timestamp", 0)
            
            if (savedIgStatus != -1) {
                Log.e("CarPowerManager", "ERROR: 📥 Loaded igStatus from prefs: $savedIgStatus (timestamp: $timestamp)")
                return savedIgStatus
            } else {
                Log.e("CarPowerManager", "ERROR: 📥 No saved igStatus found in prefs")
                return 0 // Default to ACC OFF
            }
        } catch (e: Exception) {
            Log.e("CarPowerManager", "ERROR: ❌ Failed to load igStatus from prefs: ${e.message}")
            return 0 // Default to ACC OFF
        }
    }
}