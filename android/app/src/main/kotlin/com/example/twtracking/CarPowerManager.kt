package com.example.twtracking

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import bw.car.Car
import bw.car.CarNotConnectedException
// Removed conflicting import - using fully qualified name instead
import java.util.concurrent.Executor

class CarPowerManager private constructor(private val context: Context) {
    companion object {
        private const val TAG = "CarPowerManager"
        
        // Singleton instance
        @Volatile
        private var INSTANCE: CarPowerManager? = null
        
        fun getInstance(context: Context): CarPowerManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CarPowerManager(context.applicationContext).also { INSTANCE = it }
            }
        }
        
        // Power state constants
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

    // Single Car instance managed by singleton
    @Volatile
    private var car: Car? = null
    
    // Single CarPowerManager instance derived from car
    @Volatile
    private var carPowerManager: bw.car.power.CarPowerManager? = null
    
    private var powerStateListener: bw.car.power.CarPowerManager.CarPowerStateListener? = null
    private var isConnected = false
    private var isInitialized = false
    private var isCarCreated = false
    private var accStateCallback: ((Boolean) -> Unit)? = null
    private var sleepStateCallback: ((Boolean) -> Unit)? = null
    private var ignitionLogCallback: ((String, String, String) -> Unit)? = null
    private var currentAccState = false
    private var currentIgStatus = 0 // Default to 0 (ACC OFF)
    private val mainHandler = Handler(Looper.getMainLooper())


    fun initialize() {
        if (isInitialized) {
            Log.d(TAG, "CarPowerManager already initialized")
            return
        }

        synchronized(this) {
            if (isInitialized) return // Double-check locking
            
            try {
                Log.d(TAG, "=== INITIALIZING CAR POWER MANAGER (SINGLE INSTANCE) ===")
                
                // Create Car instance only once
                if (car == null && !isCarCreated) {
                    createCarInstance()
                }
                
                // If car creation failed, try fallback
                if (car == null) {
                    Log.w(TAG, "Car instance is null - using fallback mode")
                    tryFallbackInitialization()
                    return
                }
                
                // Connect to car service if not already connected
                if (!isConnected) {
                    connectToCarService()
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize CarPowerManager", e)
                tryFallbackInitialization()
            }
        }
    }
    
    private fun createCarInstance() {
        try {
            // Check if car service is available
            val packageManager = context.packageManager
            val carServiceAvailable = packageManager.hasSystemFeature("android.hardware.type.automotive")
            val isBwicDevice = android.os.Build.MANUFACTURER.contains("BWIC", ignoreCase = true)
            val isAutomotiveDevice = carServiceAvailable || isBwicDevice
            
            if (!isAutomotiveDevice) {
                Log.w(TAG, "Car service not available")
                isCarCreated = true // Mark as attempted
                return
            }
            
            Log.d(TAG, "Creating single Car instance...")
            car = Car.createCar(context, createServiceConnection())
            isCarCreated = true
            Log.d(TAG, "Car instance created successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create Car instance", e)
            isCarCreated = true // Mark as attempted even on failure
        }
    }
    
    private fun createServiceConnection(): android.content.ServiceConnection {
        return object : android.content.ServiceConnection {
            override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
                try {
                    Log.d(TAG, "Car service connected successfully")
                    
                    // Get CarPowerManager from existing car instance
                    if (carPowerManager == null) {
                        carPowerManager = car?.getCarManager(Car.POWER_SERVICE) as? bw.car.power.CarPowerManager
                        Log.d(TAG, "CarPowerManager instance obtained from Car")
                    }
                    
                    if (carPowerManager != null) {
                        Log.d(TAG, "Car power manager obtained successfully")
                        isConnected = true
                        
                        mainHandler.post {
                            setupPowerStateListener()
                        }
                    } else {
                        Log.e(TAG, "Failed to get car power manager - null returned")
                        tryFallbackInitialization()
                    }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Error getting car power manager", e)
                    tryFallbackInitialization()
                }
            }

            override fun onServiceDisconnected(name: android.content.ComponentName?) {
                Log.w(TAG, "Car service disconnected")
                disconnect()
            }
        }
    }
    
    private fun connectToCarService() {
        try {
            Log.d(TAG, "Connecting to car service...")
            car?.connect()
            
            // Set a timeout for initialization
            mainHandler.postDelayed({
                if (!isInitialized) {
                    Log.w(TAG, "CarPowerManager initialization timeout - using fallback mode")
                    tryGetInitialState()
                }
            }, 3000)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to car service", e)
            tryFallbackInitialization()
        }
    }

    private fun setupPowerStateListener() {
        try {
            Log.d(TAG, "Setting up power state listener...")
            
            powerStateListener = object : bw.car.power.CarPowerManager.CarPowerStateListener {
                override fun onPowerStateChanged(state: Int) {
                    try {
                        val timestamp = System.currentTimeMillis()
                        handlePowerStateChange(state)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in onPowerStateChanged", e)
                    }
                }
            }
            
            carPowerManager?.registerPowerStateListener(powerStateListener)
            Log.d(TAG, "Power state listener registered successfully")
            
            tryGetInitialState()
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup power state listener", e)
            tryGetInitialState()
        }
    }

    private fun tryGetInitialState() {
        try {
            Log.d(TAG, "Getting initial power state...")
            
            if (carPowerManager != null) {
                val initialState = carPowerManager?.getPowerState() ?: POWER_STATE_OFF
                val isAccOn = isPowerStateAccOn(initialState)
                currentIgStatus = if (isAccOn) 1 else 0
                currentAccState = isAccOn
                
                saveIgStatusToPrefs(currentIgStatus)
                
                Log.d(TAG, "Initial state detected: state=$initialState, ACC ON=$isAccOn, igStatus=$currentIgStatus")
                
                mainHandler.post {
                    accStateCallback?.invoke(currentAccState)
                }
                
            } else {
                Log.w(TAG, "carPowerManager is null, using detected state or default")
                
                val isAccOn = detectBwicIgnition()
                currentIgStatus = if (isAccOn) 1 else 0
                currentAccState = isAccOn
                
                saveIgStatusToPrefs(currentIgStatus)
                
                Log.d(TAG, "Fallback state detected: ACC ON=$isAccOn, igStatus=$currentIgStatus")
                
                mainHandler.post {
                    accStateCallback?.invoke(currentAccState)
                }
            }
            
            isInitialized = true
            Log.d(TAG, "CarPowerManager initialization completed. Final igStatus: $currentIgStatus")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get initial power state", e)
            
            val isAccOn = detectBwicIgnition()
            currentIgStatus = if (isAccOn) 1 else 0
            currentAccState = isAccOn
            
            saveIgStatusToPrefs(currentIgStatus)
            
            mainHandler.post {
                accStateCallback?.invoke(currentAccState)
            }
            
            isInitialized = true
            Log.d(TAG, "CarPowerManager initialization completed with exception fallback. Final igStatus: $currentIgStatus")
        }
    }

    private fun tryFallbackInitialization() {
        try {
            Log.d(TAG, "Trying fallback initialization...")
            
            val isBwicDevice = android.os.Build.MANUFACTURER.contains("BWIC", ignoreCase = true)
            
            if (isBwicDevice) {
                Log.d(TAG, "Using BWIC car framework for BWIC device")
                
                try {
                    // Only create new Car instance if current one is null
                    if (car == null) {
                        Log.d(TAG, "Creating fallback Car instance for BWIC")
                        car = Car.createCar(context, object : android.content.ServiceConnection {
                            override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
                                try {
                                    Log.d(TAG, "BWIC car service connected in fallback")
                                    
                                    // Use existing carPowerManager or create new one
                                    if (carPowerManager == null) {
                                        carPowerManager = car?.getCarManager(Car.POWER_SERVICE) as? bw.car.power.CarPowerManager
                                        Log.d(TAG, "BWIC CarPowerManager instance created")
                                    }
                                    
                                    if (carPowerManager != null) {
                                        Log.d(TAG, "BWIC car power manager obtained successfully")
                                        isConnected = true
                                        
                                        val powerState = carPowerManager?.getPowerState() ?: POWER_STATE_OFF
                                        val isAccOn = isPowerStateAccOn(powerState)
                                        currentAccState = isAccOn
                                        currentIgStatus = if (isAccOn) 1 else 0
                                        
                                        Log.d(TAG, "BWIC fallback - power state: $powerState, ACC ON: $isAccOn, igStatus: $currentIgStatus")
                                        
                                        setupPowerStateListener()
                                        
                                    } else {
                                        Log.e(TAG, "BWIC car power manager is null")
                                        useCustomBwicDetection()
                                    }
                                    
                                } catch (e: Exception) {
                                    Log.e(TAG, "Exception in BWIC fallback: ${e.message}")
                                    useCustomBwicDetection()
                                }
                            }

                            override fun onServiceDisconnected(name: android.content.ComponentName?) {
                                Log.e(TAG, "BWIC car service disconnected")
                            }
                        })
                        
                        car?.connect()
                    } else {
                        Log.d(TAG, "Using existing Car instance for BWIC fallback")
                        // Try to get CarPowerManager from existing car
                        if (carPowerManager == null) {
                            carPowerManager = car?.getCarManager(Car.POWER_SERVICE) as? bw.car.power.CarPowerManager
                        }
                        useCustomBwicDetection()
                    }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to use BWIC car framework: ${e.message}")
                    useCustomBwicDetection()
                }
                
            } else {
                Log.d(TAG, "Using default fallback for non-BWIC device")
                currentAccState = false
                
                mainHandler.post {
                    accStateCallback?.invoke(false)
                }
            }
            
            isInitialized = true
            Log.d(TAG, "Fallback initialization completed. Final igStatus: $currentIgStatus")
            
        } catch (e: Exception) {
            Log.e(TAG, "Fallback initialization also failed", e)
            isInitialized = true
        }
    }

    private fun useCustomBwicDetection() {
        Log.d(TAG, "Using custom BWIC detection as fallback")
        val isIgnitionOn = detectBwicIgnition()
        currentAccState = isIgnitionOn
        currentIgStatus = if (isIgnitionOn) 1 else 0
        
        Log.d(TAG, "Custom BWIC detection - ACC ON: $isIgnitionOn, igStatus: $currentIgStatus")
        
        mainHandler.post {
            accStateCallback?.invoke(isIgnitionOn)
        }
    }

    private fun isPowerStateAccOn(state: Int): Boolean {
        val result = (state == POWER_STATE_ON || state == POWER_STATE_ON_DISP_OFF)
        
        Log.d(TAG, "Power state analysis: state=$state (${getPowerStateName(state)}), isAccOn=$result")
        
        try {
            val message = "Power state analysis: state=$state (${getPowerStateName(state)}), isAccOn=$result"
            val details = "Power state: $state, ACC Status: $result"
            val logType = if (result) "power_state_on" else "power_state_off"
            
            logToIgnitionLogs(message, details, logType)
        } catch (e: Exception) {
            Log.e(TAG, "Error logging to ignition logs: ${e.message}")
        }
        
        return result
    }

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

    // Public methods - simplified and clean
    fun getCurrentAccState(): Boolean = currentAccState

    fun getCurrentIgStatus(): Int = currentIgStatus

    fun isProperlyInitialized(): Boolean = isInitialized && isConnected && carPowerManager != null

    fun setAccStateCallback(callback: (Boolean) -> Unit) {
        accStateCallback = callback
    }

    fun setSleepStateCallback(callback: (Boolean) -> Unit) {
        sleepStateCallback = callback
    }

    fun setIgnitionLogCallback(callback: (String, String, String) -> Unit) {
        ignitionLogCallback = callback
    }

    fun getCurrentSleepState(): Boolean = false

    fun getCurrentPowerStatus(): Map<String, Any> = mapOf(
        "isConnected" to isConnected,
        "currentAccState" to currentAccState,
        "currentIgStatus" to currentIgStatus
    )

    fun getDetailedStatus(): Map<String, Any> = mapOf(
        "isInitialized" to isInitialized,
        "isConnected" to isConnected,
        "carPowerManagerExists" to (carPowerManager != null),
        "currentAccState" to currentAccState,
        "currentIgStatus" to currentIgStatus,
        "hasAccCallback" to (accStateCallback != null),
        "hasSleepCallback" to (sleepStateCallback != null),
        "hasPowerStateListener" to (powerStateListener != null)
    )


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
        
        return statesInfo
    }

    // Debug and testing methods
    fun testCurrentPowerState() {
        Log.d(TAG, "Testing current power state:")
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
            } catch (e: Exception) {
                Log.e(TAG, "   - Error getting power state: ${e.message}")
            }
        }
    }

    fun debugPowerStates() {
        Log.d(TAG, "Debugging power states:")
        
        val testStates = listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13)
        
        for (state in testStates) {
            val isAccOn = isPowerStateAccOn(state)
            val stateName = getPowerStateName(state)
            Log.d(TAG, "   - State $state ($stateName): ACC ON = $isAccOn")
        }
        
        if (carPowerManager != null) {
            try {
                val actualState = carPowerManager?.getPowerState() ?: -1
                val actualStateName = getPowerStateName(actualState)
                val actualIsAccOn = isPowerStateAccOn(actualState)
                
                Log.d(TAG, "   - Actual state: $actualState ($actualStateName)")
                Log.d(TAG, "   - Actual ACC ON: $actualIsAccOn")
                Log.d(TAG, "   - Current igStatus: $currentIgStatus")
            } catch (e: Exception) {
                Log.e(TAG, "   - Error getting actual state: ${e.message}")
            }
        }
    }

    fun testSpecificPowerState(testState: Int) {
        Log.d(TAG, "Testing specific power state: $testState")
        
        val stateName = getPowerStateName(testState)
        val isAccOn = isPowerStateAccOn(testState)
        
        currentIgStatus = if (isAccOn) 1 else 0
        currentAccState = isAccOn
        
        mainHandler.post {
            accStateCallback?.invoke(isAccOn)
        }
    }

    fun forceUpdateIgStatus() {
        if (carPowerManager != null && isConnected) {
            try {
                val currentState = carPowerManager?.getPowerState() ?: POWER_STATE_OFF
                val isAccOn = isPowerStateAccOn(currentState)
                currentIgStatus = if (isAccOn) 1 else 0
                currentAccState = isAccOn
                
                mainHandler.post {
                    accStateCallback?.invoke(isAccOn)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error getting current power state for force update", e)
                initialize()
            }
        } else {
            initialize()
        }
    }

    // Simulation methods for testing
    fun simulateAccStateChange(isAccOn: Boolean) {
        val newIgStatus = if (isAccOn) 1 else 0
        currentIgStatus = newIgStatus
        currentAccState = isAccOn
        
        mainHandler.post {
            accStateCallback?.invoke(isAccOn)
        }
    }


    // Private helper methods
    private fun logToIgnitionLogs(message: String, details: String, logType: String) {
        try {
            ignitionLogCallback?.invoke(message, details, logType)
        } catch (e: Exception) {
            Log.e(TAG, "Error in ignition log callback: ${e.message}")
        }
    }

    private fun detectBwicIgnition(): Boolean {
        try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            val isPlugged = batteryManager.isCharging
            
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            val isScreenOn = powerManager.isInteractive
            
            val usbManager = context.getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
            val deviceList = usbManager.deviceList
            val hasUsbDevices = deviceList.isNotEmpty()
            
            val ignitionIndicators = mutableListOf<Boolean>()
            
            if (isScreenOn) ignitionIndicators.add(true)
            if (hasUsbDevices) ignitionIndicators.add(true)
            
            val isIgnitionOn = ignitionIndicators.isNotEmpty() && ignitionIndicators.count { it } >= ignitionIndicators.size / 2
            
            return isIgnitionOn
            
        } catch (e: Exception) {
            Log.e(TAG, "Exception in BWIC ignition detection: ${e.message}")
            return false
        }
    }

    private fun saveIgStatusToPrefs(igStatus: Int) {
        try {
            val prefs = context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
            prefs.edit().apply {
                putInt("current_ig_status", igStatus)
                putLong("ig_status_timestamp", System.currentTimeMillis())
                apply()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save igStatus to prefs: ${e.message}")
        }
    }


    private fun handlePowerStateChange(state: Int) {
        val isAccOn = isPowerStateAccOn(state)
        val newIgStatus = if (isAccOn) 1 else 0
        val oldIgStatus = currentIgStatus

        Log.d(TAG, "Power state changed (State: $state, ACC: $isAccOn)")

        if (newIgStatus != oldIgStatus) {
            currentIgStatus = newIgStatus
            currentAccState = isAccOn
            saveIgStatusToPrefs(newIgStatus)
            
            mainHandler.post {
                accStateCallback?.invoke(isAccOn)
            }
        }
    }

    // Connection management
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
        synchronized(this) {
            try {
                powerStateListener?.let { listener ->
                    try {
                        carPowerManager?.unregisterPowerStateListener(listener)
                        Log.d(TAG, "Power state listener unregistered")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error unregistering power state listener", e)
                    }
                }
                
                car?.disconnect()
                
                // Reset all instances
                car = null
                carPowerManager = null
                powerStateListener = null
                isConnected = false
                isInitialized = false
                isCarCreated = false
                
                Log.d(TAG, "CarPowerManager disconnected and instances reset")
            } catch (e: Exception) {
                Log.e(TAG, "Error disconnecting CarPowerManager", e)
            }
        }
    }

    fun cleanup() {
        synchronized(this) {
            try {
                Log.d(TAG, "Starting CarPowerManager cleanup...")
                disconnect()
                Log.d(TAG, "CarPowerManager cleanup completed")
            } catch (e: Exception) {
                Log.e(TAG, "Error during cleanup", e)
            }
        }
    }
}