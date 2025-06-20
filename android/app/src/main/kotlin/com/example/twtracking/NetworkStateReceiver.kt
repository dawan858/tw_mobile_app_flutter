package com.example.twtracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log

class NetworkStateReceiver : BroadcastReceiver() {
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

       override fun onReceive(context: Context, intent: Intent) {
        Log.d("NetworkStateReceiver", "Received action: ${intent.action}")
        
        when (intent.action) {
            ConnectivityManager.CONNECTIVITY_ACTION -> {
                val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val networkInfo = connectivityManager.activeNetworkInfo
                val isConnected = networkInfo != null && networkInfo.isConnected

                if (isConnected) {
                    Log.d("NetworkStateReceiver", "Network connected - starting background service only")
                    startBackgroundServiceOnly(context, "network_connected")
                }
            }
            "android.bluetooth.adapter.action.STATE_CHANGED" -> {
                Log.d("NetworkStateReceiver", "Bluetooth state changed - starting background service only")
                startBackgroundServiceOnly(context, "bluetooth_state_changed")
            }
            "android.intent.action.TIME_SET" -> {
                Log.d("NetworkStateReceiver", "Time set - starting background service only")
                startBackgroundServiceOnly(context, "time_set")
            }
            "android.intent.action.TIMEZONE_CHANGED" -> {
                Log.d("NetworkStateReceiver", "Timezone changed - starting background service only")
                startBackgroundServiceOnly(context, "timezone_changed")
            }
            "android.intent.action.BOOT_COMPLETED" -> {
                Log.d("NetworkStateReceiver", "Boot completed - starting background service only")
                startBackgroundServiceOnly(context, "boot_completed")
            }
        }
    }

    private fun startBackgroundServiceOnly(context: Context, trigger: String) {
        try {
            Log.d("NetworkStateReceiver", "=== STARTING BACKGROUND SERVICE ONLY ===")
            Log.d("NetworkStateReceiver", "Trigger: $trigger")
            
            // Start ONLY the background service - NO UI
            val serviceIntent = Intent(context, BackgroundService::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                putExtra("started_by", trigger)
                putExtra("auto_started", true)
                putExtra("background_only", true) // Key flag for background-only operation
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            
            Log.d("NetworkStateReceiver", "✅ Background service started (NO UI)")
        } catch (e: Exception) {
            Log.e("NetworkStateReceiver", "❌ Failed to start background service: ${e.message}")
        }
    }

    // Network callback methods remain the same but also use background-only start
    fun registerNetworkCallback(context: Context) {
        connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.d("NetworkStateReceiver", "Network available - starting background service only")
                    startBackgroundServiceOnly(context, "network_callback_available")
                }

                override fun onLost(network: Network) {
                    Log.d("NetworkStateReceiver", "Network lost")
                }
            }

            val networkRequest = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addTransportType(NetworkCapabilities.TRANSPORT_BLUETOOTH)
                .build()

            connectivityManager?.registerNetworkCallback(networkRequest, networkCallback!!)
        }
    }

    fun unregisterNetworkCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && networkCallback != null) {
            connectivityManager?.unregisterNetworkCallback(networkCallback!!)
            networkCallback = null
        }
    }
} 