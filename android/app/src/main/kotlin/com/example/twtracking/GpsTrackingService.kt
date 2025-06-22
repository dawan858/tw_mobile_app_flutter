package com.example.twtracking

import android.app.*
import android.content.Intent
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import android.util.Log
import android.content.Context
import android.location.Location
import android.content.SharedPreferences

class GpsTrackingService : Service() {
    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null
    private val CHANNEL_ID = "GpsTrackingChannel"
    private val NOTIFICATION_ID = 1
    private var igStatus = 0 // Set to 0 when service starts (ACC OFF)
    private var lastLocationUpdateTime: Long = 0
    private var lastProcessedLocation: Location? = null
    private var gpsTimer: Int = 5 // Default 5 seconds
    private var uploadTimer: Int = 10 // Default 10 minutes
    private var angleThreshold: Float = 45f // Default 45 degrees
    private var overSpeedingThreshold: Float = 60f // Default 60 km/h
    private var distanceThreshold: Float = 1000f // Default 1000 meters
    private var movingTimer: Int = 60 // Default 60 seconds
    private var stopTimer: Int = 130 // Default 130 seconds
    private var lastLocation: Location? = null
    private var lastUpdateTime: Long = 0
    private var isMoving: Boolean = false
    private var lastMovementTime: Long = 0
    private var lastStopTime: Long = 0

    override fun onCreate() {
        super.onCreate()
        loadConfiguration()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        setupLocationUpdates()
    }

    private fun loadConfiguration() {
        try {
            val prefs = getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
            gpsTimer = prefs.getInt("flutter.gpsTimer", 5)
            uploadTimer = prefs.getInt("flutter.uploadTimer", 10)
            angleThreshold = prefs.getFloat("flutter.angleThreshold", 45f)
            overSpeedingThreshold = prefs.getFloat("flutter.overSpeedingThreshold", 60f)
            distanceThreshold = prefs.getFloat("flutter.distanceThreshold", 1000f)
            movingTimer = prefs.getInt("flutter.movingTimer", 60)
            stopTimer = prefs.getInt("flutter.stopTimer", 130)
            Log.d("GpsTrackingService", "Configuration loaded successfully")
        } catch (e: Exception) {
            Log.e("GpsTrackingService", "Error loading configuration: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "GPS Tracking Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Used for GPS tracking service"
        }
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GPS Tracking Active")
            .setContentText("Tracking location in background")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun setupLocationUpdates() {
        Log.d("GpsTrackingService", "Setting up location updates with interval: $gpsTimer seconds")
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, gpsTimer * 1000L)
            .setMinUpdateIntervalMillis(gpsTimer * 1000L)
            .setMaxUpdateDelayMillis(gpsTimer * 2000L)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    val currentTime = System.currentTimeMillis()
                    val timeSinceLastUpdate = currentTime - lastLocationUpdateTime
                    val distance = lastLocation?.distanceTo(location) ?: 0f
                    val speed = location.speed * 3.6f // Convert to km/h

                    if (
                        timeSinceLastUpdate >= gpsTimer * 1000L ||
                        distance >= distanceThreshold ||
                        speed >= overSpeedingThreshold
                    ) {
                        Log.d("GpsTrackingService", "Processing location update: ${location.latitude}, ${location.longitude}")
                        val intent = Intent("com.example.tracking_world.LOCATION_UPDATE")
                        intent.putExtra("latitude", location.latitude)
                        intent.putExtra("longitude", location.longitude)
                        intent.putExtra("accuracy", location.accuracy)
                        intent.putExtra("altitude", location.altitude)
                        intent.putExtra("speed", speed)
                        intent.putExtra("bearing", location.bearing)
                        intent.putExtra("igStatus", igStatus)
                        // Add reason if needed
                        sendBroadcast(intent)
                        lastLocationUpdateTime = currentTime
                        lastLocation = location
                        Log.d("GpsTrackingService", "Location data processed. Next update in $gpsTimer seconds")
                    } else {
                        Log.d("GpsTrackingService", "Skipping location update - no trigger met (timer, distance, speed)")
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
            Log.d("GpsTrackingService", "Location updates requested successfully")
        } catch (e: SecurityException) {
            Log.e("GpsTrackingService", "Error requesting location updates", e)
        }
    }

    private fun shouldProcessLocation(location: Location): Boolean {
        if (lastLocation == null) return true
        
        val distance = lastLocation!!.distanceTo(location)
        val speed = location.speed * 3.6f // Convert to km/h
        
        return distance > 5 || speed > 1 || 
               (System.currentTimeMillis() - lastLocationUpdateTime) >= gpsTimer * 1000L
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Load current igStatus from SharedPreferences instead of resetting to 0
        try {
            val prefs = getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
            val currentIgStatus = prefs.getInt("current_ig_status", 0)
            igStatus = currentIgStatus
            Log.d("GpsTrackingService", "Loaded current igStatus from SharedPreferences: $igStatus")
        } catch (e: Exception) {
            Log.e("GpsTrackingService", "Error loading igStatus from SharedPreferences: ${e.message}")
            igStatus = 0 // Fallback only if there's an error
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            fusedLocationClient?.removeLocationUpdates(locationCallback!!)
            Log.d("GpsTrackingService", "Location updates removed")
        } catch (e: Exception) {
            Log.e("GpsTrackingService", "Error removing location updates", e)
        }
    }
} 