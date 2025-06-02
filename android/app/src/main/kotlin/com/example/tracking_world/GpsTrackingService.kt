package com.example.tracking_world

import android.app.*
import android.content.Intent
import android.location.Location
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.fixedRateTimer

class GpsTrackingService : Service() {
    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var isTracking = false
    private var lastLocation: Location? = null
    private var lastUpdateTime: Long = 0
    private val UPDATE_INTERVAL = 5000L // 5 seconds
    private val FASTEST_INTERVAL = 3000L // 3 seconds
    private val NOTIFICATION_ID = 1
    private val CHANNEL_ID = "GpsTrackingChannel"
    private val TAG = "GpsTrackingService"
    private var wakeLock: PowerManager.WakeLock? = null
    private var isServiceRunning = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        acquireWakeLock()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        setupLocationUpdates()
        isServiceRunning = true
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "GpsTrackingService::WakeLock"
        ).apply {
            acquire(10*60*1000L /*10 minutes*/)
        }
        Log.d(TAG, "WakeLock acquired")
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "GPS Tracking Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Used for GPS tracking service"
            setShowBadge(false)
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
        Log.d(TAG, "Notification channel created")
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GPS Tracking Active")
            .setContentText("Tap to open app")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun setupLocationUpdates() {
        Log.d(TAG, "Setting up location updates")
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        
        val locationRequest = LocationRequest.create().apply {
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            interval = UPDATE_INTERVAL
            fastestInterval = FASTEST_INTERVAL
            setWaitForAccurateLocation(false)
            setMaxWaitTime(UPDATE_INTERVAL)
        }

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                Log.d(TAG, "Received location update")
                locationResult.lastLocation?.let { location ->
                    Log.d(TAG, "Location: ${location.latitude}, ${location.longitude}")
                    if (shouldUpdateLocation(location)) {
                        lastLocation = location
                        lastUpdateTime = System.currentTimeMillis()
                        sendLocationToServer(location)
                    } else {
                        Log.d(TAG, "Skipping location update - too soon or too close")
                    }
                }
            }
        }

        try {
            fusedLocationClient?.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                Looper.getMainLooper()
            )
            isTracking = true
            Log.d(TAG, "Location updates requested successfully")
        } catch (e: SecurityException) {
            Log.e(TAG, "Error requesting location updates", e)
        }
    }

    private fun shouldUpdateLocation(newLocation: Location): Boolean {
        if (lastLocation == null) {
            Log.d(TAG, "First location update")
            return true
        }
        
        val distance = lastLocation!!.distanceTo(newLocation)
        val timeDiff = System.currentTimeMillis() - lastUpdateTime
        
        val shouldUpdate = distance > 5 || timeDiff > UPDATE_INTERVAL
        Log.d(TAG, "Location update check - Distance: $distance meters, Time diff: $timeDiff ms, Should update: $shouldUpdate")
        return shouldUpdate
    }

    private fun sendLocationToServer(location: Location) {
        Log.d(TAG, "Sending location to server")
        serviceScope.launch {
            try {
                val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss.SSS", Locale.getDefault())
                val currentTime = dateFormat.format(Date())
                
                val locationData = JSONObject().apply {
                    put("latitude", location.latitude)
                    put("longitude", location.longitude)
                    put("accuracy", location.accuracy)
                    put("altitude", location.altitude)
                    put("bearing", location.bearing)
                    put("speed", location.speed * 3.6) // Convert m/s to km/h
                    put("deviceRDT", currentTime)
                    put("gmtSettings", "GMT+${TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 3600000}:00 ${Calendar.getInstance().get(Calendar.YEAR)}")
                    put("time", System.currentTimeMillis())
                    put("provider", location.provider)
                    put("reason", "Background")
                }

                Log.d(TAG, "Sending data: ${locationData.toString()}")

                val url = URL("http://ec2-3-83-201-132.compute-1.amazonaws.com:3000/api/location")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                connection.outputStream.use { os ->
                    os.write(locationData.toString().toByteArray())
                    os.flush()
                }

                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK || responseCode == HttpURLConnection.HTTP_CREATED) {
                    Log.d(TAG, "Location data sent successfully")
                } else {
                    Log.e(TAG, "Failed to send location data. Response code: $responseCode")
                    val errorStream = connection.errorStream?.bufferedReader()?.use { it.readText() }
                    Log.e(TAG, "Error response: $errorStream")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending location data", e)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service onStartCommand")
        if (!isServiceRunning) {
            onCreate()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "Service onTaskRemoved")
        super.onTaskRemoved(rootIntent)
        // Restart the service if it's killed
        val restartServiceIntent = Intent(applicationContext, GpsTrackingService::class.java)
        restartServiceIntent.setPackage(packageName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(restartServiceIntent)
        } else {
            startService(restartServiceIntent)
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "Service onDestroy")
        super.onDestroy()
        locationCallback?.let {
            fusedLocationClient?.removeLocationUpdates(it)
        }
        serviceScope.cancel()
        isTracking = false
        isServiceRunning = false
        
        // Release wake lock
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        
        // Restart the service
        val restartServiceIntent = Intent(applicationContext, GpsTrackingService::class.java)
        restartServiceIntent.setPackage(packageName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(restartServiceIntent)
        } else {
            startService(restartServiceIntent)
        }
    }
} 