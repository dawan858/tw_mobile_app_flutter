package com.trackingWorld.tracking.tracking_world

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
                    Log.d("NetworkStateReceiver", "Network connected - waking up app")
                    startTrackingService(context)
                }
            }
            "android.bluetooth.adapter.action.STATE_CHANGED" -> {
                Log.d("NetworkStateReceiver", "Bluetooth state changed - waking up app")
                startTrackingService(context)
            }
            "android.intent.action.TIME_SET" -> {
                Log.d("NetworkStateReceiver", "Time set - waking up app")
                startTrackingService(context)
            }
            "android.intent.action.TIMEZONE_CHANGED" -> {
                Log.d("NetworkStateReceiver", "Timezone changed - waking up app")
                startTrackingService(context)
            }
            "android.intent.action.BOOT_COMPLETED" -> {
                Log.d("NetworkStateReceiver", "Boot completed - starting services")
                startTrackingService(context)
            }
        }
    }

    private fun startTrackingService(context: Context) {
        try {
            // Start the background service
            val serviceIntent = Intent(context, BackgroundService::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                putExtra("started_by", "NetworkStateReceiver")
                putExtra("auto_started", true)
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            
            // Also start the main activity to ensure app is visible
            try {
                val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    putExtra("auto_started", true)
                    putExtra("started_by", "NetworkStateReceiver")
                }
                
                if (launchIntent != null) {
                    context.startActivity(launchIntent)
                    Log.d("NetworkStateReceiver", "Main activity started successfully")
                }
            } catch (e: Exception) {
                Log.e("NetworkStateReceiver", "Failed to start main activity: ${e.message}")
            }
            
            Log.d("NetworkStateReceiver", "BackgroundService started successfully")
        } catch (e: Exception) {
            Log.e("NetworkStateReceiver", "Failed to start BackgroundService: ${e.message}")
        }
    }

    fun registerNetworkCallback(context: Context) {
        connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.d("NetworkStateReceiver", "Network available - waking up app")
                    val serviceIntent = Intent(context, BackgroundService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
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