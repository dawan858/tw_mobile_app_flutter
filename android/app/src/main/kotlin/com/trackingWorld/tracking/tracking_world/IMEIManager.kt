package com.trackingWorld.tracking.tracking_world

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import android.content.SharedPreferences
import androidx.core.content.ContextCompat
import android.Manifest
import android.annotation.SuppressLint

class ImeiManager private constructor(private val context: Context) {
    
    companion object {
        @Volatile
        private var INSTANCE: ImeiManager? = null
        private const val PREFS_NAME = "FlutterSharedPreferences"
        private const val IMEI_KEY = "flutter.imei"
        
        fun getInstance(context: Context): ImeiManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ImeiManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    private fun hasPhonePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, 
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("HardwareIds")
    fun getDeviceIdentifier(): String {
        Log.d("ImeiManager", "=== ATTEMPTING TO GET IMEI ONLY (NO FALLBACKS) ===")
        Log.d("ImeiManager", "Android Version: ${Build.VERSION.SDK_INT}")
        Log.d("ImeiManager", "Has Phone Permission: ${hasPhonePermission()}")
        
        // Check permission first
        if (!hasPhonePermission()) {
            Log.e("ImeiManager", "❌ READ_PHONE_STATE permission not granted")
            return "unknown"
        }

        // Step 1: Check if we already have a valid stored IMEI
        val storedImei = prefs.getString(IMEI_KEY, null)
        if (!storedImei.isNullOrEmpty() && isValidImei(storedImei)) {
            Log.d("ImeiManager", "✅ Found valid stored IMEI")
            return storedImei
        }

        // Step 2: Try to get fresh IMEI
        val freshImei = getFreshImeiOnly()
        if (!freshImei.isNullOrEmpty() && isValidImei(freshImei)) {
            Log.d("ImeiManager", "✅ Got fresh IMEI")
            saveImei(freshImei)
            return freshImei
        }

        Log.e("ImeiManager", "❌ Could not obtain IMEI")
        return "unknown"
    }

    @SuppressLint("HardwareIds")
    private fun getFreshImeiOnly(): String? {
        Log.d("ImeiManager", "Attempting to get fresh IMEI...")
        
        try {
            return when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                    Log.d("ImeiManager", "Android 10+ detected - IMEI access highly restricted")
                    try {
                        val imei = telephonyManager.getImei(0)
                        if (!imei.isNullOrEmpty()) {
                            Log.d("ImeiManager", "Successfully got IMEI on Android 10+")
                            imei
                        } else {
                            Log.w("ImeiManager", "IMEI is null on Android 10+")
                            null
                        }
                    } catch (e: SecurityException) {
                        Log.e("ImeiManager", "IMEI access denied on Android 10+: ${e.message}")
                        null
                    } catch (e: Exception) {
                        Log.e("ImeiManager", "Error getting IMEI on Android 10+: ${e.message}")
                        null
                    }
                }
                
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                    Log.d("ImeiManager", "Android 8+ detected - using getImei(slot)")
                    try {
                        // Try primary SIM slot first
                        var imei = telephonyManager.getImei(0)
                        if (!imei.isNullOrEmpty()) {
                            Log.d("ImeiManager", "Got IMEI from slot 0")
                            return imei
                        }
                        
                        // Try secondary SIM slot
                        imei = telephonyManager.getImei(1)
                        if (!imei.isNullOrEmpty()) {
                            Log.d("ImeiManager", "Got IMEI from slot 1")
                            return imei
                        }
                        
                        Log.w("ImeiManager", "No IMEI found in either slot")
                        null
                    } catch (e: SecurityException) {
                        Log.e("ImeiManager", "IMEI access denied: ${e.message}")
                        null
                    } catch (e: Exception) {
                        Log.e("ImeiManager", "Error getting IMEI: ${e.message}")
                        null
                    }
                }
                
                else -> {
                    Log.d("ImeiManager", "Android 7 or below - using deprecated deviceId")
                    try {
                        @Suppress("DEPRECATION")
                        val deviceId = telephonyManager.deviceId
                        if (!deviceId.isNullOrEmpty()) {
                            Log.d("ImeiManager", "Got device ID (IMEI)")
                            deviceId
                        } else {
                            Log.w("ImeiManager", "Device ID is null")
                            null
                        }
                    } catch (e: SecurityException) {
                        Log.e("ImeiManager", "Device ID access denied: ${e.message}")
                        null
                    } catch (e: Exception) {
                        Log.e("ImeiManager", "Error getting device ID: ${e.message}")
                        null
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ImeiManager", "Unexpected error getting IMEI: ${e.message}")
            return null
        }
    }

    private fun isValidImei(imei: String): Boolean {
        // Basic IMEI validation - should be 14-15 digits 
        // and should not be "unknown" or contain fallback prefixes
        return imei != "unknown" && 
               imei.matches(Regex("^[0-9]{14,15}$")) && 
               !imei.startsWith("AID_") && 
               !imei.startsWith("SER_") && 
               !imei.startsWith("UUID_") &&
               !imei.contains("EMERGENCY") &&
               !imei.contains("FALLBACK") &&
               !imei.contains("CRITICAL")
    }

    private fun saveImei(imei: String) {
        Log.d("ImeiManager", "Saving IMEI")
        
        prefs.edit().apply {
            putString(IMEI_KEY, imei)
            putLong("flutter.imei_saved_time", System.currentTimeMillis())
            apply()
        }
        
        Log.d("ImeiManager", "IMEI saved successfully")
    }

    fun validateAndRefreshImei(): String {
        Log.d("ImeiManager", "=== VALIDATING AND REFRESHING IMEI ===")
        
        val currentImei = prefs.getString(IMEI_KEY, null)
        Log.d("ImeiManager", "Current stored IMEI: $currentImei")
        
        // If we have a valid IMEI, try to refresh it
        if (!currentImei.isNullOrEmpty() && isValidImei(currentImei)) {
            val freshImei = getFreshImeiOnly()
            if (!freshImei.isNullOrEmpty() && freshImei != currentImei) {
                Log.d("ImeiManager", "Updating IMEI from $currentImei to $freshImei")
                saveImei(freshImei)
                return freshImei
            }
            return currentImei
        }
        
        // If no valid IMEI, try to get a new one
        return getDeviceIdentifier()
    }

    fun forceRefreshImei(): String {
        Log.d("ImeiManager", "=== FORCING IMEI REFRESH ===")
        
        // Clear stored IMEI to force fresh collection
        prefs.edit().remove(IMEI_KEY).apply()
        
        return getDeviceIdentifier()
    }

    fun getImeiInfo(): Map<String, String> {
        val info = mutableMapOf<String, String>()
        
        val currentImei = prefs.getString(IMEI_KEY, null)
        info["current_imei"] = currentImei ?: "not_available"
        info["is_valid_imei"] = if (currentImei != null) isValidImei(currentImei).toString() else "false"
        info["saved_time"] = if (prefs.contains("flutter.imei_saved_time")) {
            java.util.Date(prefs.getLong("flutter.imei_saved_time", 0)).toString()
        } else {
            "never"
        }
        info["android_version"] = Build.VERSION.SDK_INT.toString()
        info["device_model"] = Build.MODEL
        info["has_phone_permission"] = hasPhonePermission().toString()
        
        return info
    }
}

// Extension for easy access in services
fun Context.getDeviceImei(): String {
    return ImeiManager.getInstance(this).getDeviceIdentifier()
}

fun Context.validateImei(): String {
    return ImeiManager.getInstance(this).validateAndRefreshImei()
}