package com.trackingWorld.tracking.tracking_world

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import android.util.Log
import android.os.PowerManager
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.location.GnssStatus
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import android.content.ContentValues
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ExecutorService
import io.flutter.embedding.engine.FlutterEngineCache

class BackgroundService : Service() {
    companion object {
        @JvmStatic
        var totalSatellites: Int = 0
        @JvmStatic
        var connectedSatellites: Int = 0
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private val CHANNEL_ID = "tracking_service"
    private val NOTIFICATION_ID = 888
    private var wakeLock: PowerManager.WakeLock? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    private val serverUrl = "http://ec2-52-66-236-101.ap-south-1.compute.amazonaws.com:3000/api/location"
    private lateinit var dbHelper: LocationDatabaseHelper
    private var syncExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val networkExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var isSyncing = false
    private var lastLocation: Location? = null
    private val MIN_BEARING_CHANGE = 10.0f // Default minimum bearing change to consider it a turn
    private val MIN_SPEED_FOR_MOVEMENT = 3.6f // Default minimum speed in km/h to consider it movement
    private val MIN_DISTANCE = 5.0f // Default minimum distance in meters to consider movement
    private val MIN_SPEED_FOR_TURN = 5.0f // Default minimum speed in km/h to consider a turn valid
    private lateinit var gnssStatusCallback: GnssStatus.Callback
    private var lastSyncTime: Long = 0
    private var lastLocationUpdateTime: Long = 0
    private var lastProcessedLocation: Location? = null
    private var isMoving: Boolean = false
    private var lastMovementTime: Long = 0
    private var lastStopTime: Long = 0

    // Configuration parameters with defaults
    private var gpsTimer: Int = 5 // Default 5 seconds
    private var uploadTimer: Int = 10 // Default 10 minutes
    private var angleThreshold: Float = 45f // Default 45 degrees
    private var overSpeedingThreshold: Float = 60f // Default 60 km/h
    private var distanceThreshold: Float = 1000f // Default 1000 meters
    private var movingTimer: Int = 60 // Default 60 seconds
    private var stopTimer: Int = 130 // Default 130 seconds

    inner class LocationDatabaseHelper(context: Context) : SQLiteOpenHelper(context, "location_tracking.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE location_data(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    latitude REAL NOT NULL,
                    longitude REAL NOT NULL,
                    accuracy REAL,
                    altitude REAL,
                    speed REAL,
                    bearing REAL,
                    imei TEXT,
                    timestamp TEXT,
                    deviceRDT TEXT,
                    gmtSettings TEXT,
                    igStatus INTEGER,
                    localPrimaryId INTEGER,
                    name TEXT,
                    phoneNo TEXT,
                    provider TEXT,
                    reason TEXT,
                    versionNo TEXT,
                    sync_status INTEGER DEFAULT 0,
                    created_at INTEGER
                )
            """)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS location_data")
            onCreate(db)
        }
    }

    private fun loadConfiguration() {
        try {
            val prefs = getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
            
            // Load configuration values with defaults
            gpsTimer = prefs.getInt("flutter.gpsTimer", 5)
            uploadTimer = prefs.getInt("flutter.uploadTimer", 10)
            angleThreshold = prefs.getFloat("flutter.angleThreshold", 45f)
            overSpeedingThreshold = prefs.getFloat("flutter.overSpeedingThreshold", 60f)
            distanceThreshold = prefs.getFloat("flutter.distanceThreshold", 1000f)
            movingTimer = prefs.getInt("flutter.movingTimer", 60)
            stopTimer = prefs.getInt("flutter.stopTimer", 130)

            // Update intervals based on configuration
            updateLocationRequestInterval()
            updateSyncInterval()
            
            Log.d("BackgroundService", "Configuration loaded successfully")
        } catch (e: Exception) {
            Log.e("BackgroundService", "Error loading configuration: ${e.message}")
        }
    }

    private fun updateLocationRequestInterval() {
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
            val locationRequest = LocationRequest.create().apply {
                priority = LocationRequest.PRIORITY_HIGH_ACCURACY
                interval = gpsTimer * 1000L // Convert seconds to milliseconds
                fastestInterval = (gpsTimer / 2) * 1000L // Half of the interval
                maxWaitTime = gpsTimer * 2000L // Double the interval
            }

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                fusedLocationClient.requestLocationUpdates(
                    locationRequest,
                    locationCallback,
                    Looper.getMainLooper()
                )
                Log.d("BackgroundService", "Location updates interval updated to ${gpsTimer} seconds")
            }
        } catch (e: Exception) {
            Log.e("BackgroundService", "Error updating location request interval: ${e.message}")
        }
    }

    private fun updateSyncInterval() {
        syncExecutor.shutdown()
        syncExecutor = Executors.newSingleThreadScheduledExecutor()
        syncExecutor.scheduleAtFixedRate({
            if (!isSyncing) {
                syncData()
            }
        }, uploadTimer.toLong(), uploadTimer.toLong(), TimeUnit.SECONDS)
        Log.d("BackgroundService", "Sync interval updated to ${uploadTimer} seconds")
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("BackgroundService", "Service created")
        dbHelper = LocationDatabaseHelper(this)
        acquireWakeLock()
        createNotificationChannel()
        loadConfiguration() // Load configuration before setting up location updates
        setupLocationUpdates()
        startPeriodicSync()
    }

    private fun startPeriodicSync() {
        syncExecutor.scheduleAtFixedRate({
            if (!isSyncing) {
                syncData()
            }
        }, 5, 5, TimeUnit.SECONDS)
    }

    private fun syncData() {
        if (isSyncing) return
        isSyncing = true

        networkExecutor.execute {
            try {
                val db = dbHelper.readableDatabase
                val cursor = db.query(
                    "location_data",
                    null,
                    "sync_status = ?",
                    arrayOf("0"),
                    null,
                    null,
                    "created_at ASC",
                    "1000"
                )

                val syncedIds = mutableListOf<Int>()
                while (cursor.moveToNext()) {
                    val id = cursor.getInt(cursor.getColumnIndexOrThrow("id"))
                    val json = JSONObject().apply {
                        put("latitude", cursor.getDouble(cursor.getColumnIndexOrThrow("latitude")))
                        put("longitude", cursor.getDouble(cursor.getColumnIndexOrThrow("longitude")))
                        put("accuracy", cursor.getDouble(cursor.getColumnIndexOrThrow("accuracy")))
                        put("altitude", cursor.getDouble(cursor.getColumnIndexOrThrow("altitude")))
                        put("speed", cursor.getDouble(cursor.getColumnIndexOrThrow("speed")))
                        put("bearing", cursor.getDouble(cursor.getColumnIndexOrThrow("bearing")))
                        put("imei", cursor.getString(cursor.getColumnIndexOrThrow("imei")))
                        put("timestamp", cursor.getString(cursor.getColumnIndexOrThrow("timestamp")))
                        put("deviceRDT", cursor.getString(cursor.getColumnIndexOrThrow("deviceRDT")))
                        put("gmtSettings", cursor.getString(cursor.getColumnIndexOrThrow("gmtSettings")))
                        put("igStatus", cursor.getInt(cursor.getColumnIndexOrThrow("igStatus")))
                        put("localPrimaryId", cursor.getInt(cursor.getColumnIndexOrThrow("localPrimaryId")))
                        put("name", cursor.getString(cursor.getColumnIndexOrThrow("name")))
                        put("phoneNo", cursor.getString(cursor.getColumnIndexOrThrow("phoneNo")))
                        put("provider", cursor.getString(cursor.getColumnIndexOrThrow("provider")))
                        put("reason", cursor.getString(cursor.getColumnIndexOrThrow("reason")))
                        put("versionNo", cursor.getString(cursor.getColumnIndexOrThrow("versionNo")))
                    }

                    try {
                        val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
                        val requestBody = json.toString().toRequestBody(mediaType)
                        val request = Request.Builder()
                            .url(serverUrl)
                            .post(requestBody)
                            .build()

                        client.newCall(request).execute().use { response ->
                            if (response.isSuccessful) {
                                syncedIds.add(id)
                                Log.d("BackgroundService", "Successfully synced data with ID: $id")
                            } else {
                                val errorBody = response.body?.string() ?: "No error body"
                                Log.e("BackgroundService", "Failed to sync data. Status: ${response.code}, Error: $errorBody")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("BackgroundService", "Error syncing data: ${e.message}", e)
                        e.printStackTrace()
                        continue
                    }
                }
                cursor.close()

                if (syncedIds.isNotEmpty()) {
                    val db = dbHelper.writableDatabase
                    db.beginTransaction()
                    try {
                        for (id in syncedIds) {
                            val values = ContentValues().apply {
                                put("sync_status", 1)
                            }
                            db.update(
                                "location_data",
                                values,
                                "id = ?",
                                arrayOf(id.toString())
                            )
                        }
                        db.setTransactionSuccessful()
                        Log.d("BackgroundService", "Successfully marked ${syncedIds.size} records as synced")
                    } catch (e: Exception) {
                        Log.e("BackgroundService", "Error updating sync status: ${e.message}", e)
                    } finally {
                        db.endTransaction()
                    }

                    try {
                        val deletedCount = db.delete("location_data", "sync_status = ?", arrayOf("1"))
                        Log.d("BackgroundService", "Cleaned up $deletedCount synced records")
                    } catch (e: Exception) {
                        Log.e("BackgroundService", "Error cleaning up synced data: ${e.message}", e)
                    }
                } else {
                    Log.d("BackgroundService", "No records were successfully synced")
                }
            } catch (e: Exception) {
                Log.e("BackgroundService", "Error in sync process: ${e.message}", e)
                e.printStackTrace()
            } finally {
                isSyncing = false
            }
        }
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "TrackingWorld::LocationWakeLock"
        ).apply {
            acquire(10*60*1000L /*10 minutes*/)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "GPS Tracking Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "This channel is used for GPS tracking notifications"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
                enableLights(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun setupLocationUpdates() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        
        // Setup GNSS Status Callback
        gnssStatusCallback = object : GnssStatus.Callback() {
            override fun onStarted() {
                Log.d("BackgroundService", "GNSS started")
            }

            override fun onStopped() {
                Log.d("BackgroundService", "GNSS stopped")
            }

            override fun onFirstFix(ttffMillis: Int) {
                Log.d("BackgroundService", "First GNSS fix after $ttffMillis ms")
            }

            override fun onSatelliteStatusChanged(status: GnssStatus) {
                totalSatellites = status.satelliteCount
                connectedSatellites = 0
                
                for (i in 0 until status.satelliteCount) {
                    if (status.usedInFix(i)) {
                        connectedSatellites++
                    }
                }
                
                Log.d("BackgroundService", "Satellites - Total: $totalSatellites, Connected: $connectedSatellites")
                
                // Just update the static variables for MainActivity to access
            }
        }

        // Register GNSS Status Callback
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                locationManager.registerGnssStatusCallback(gnssStatusCallback)
            }
        }
        
        val locationRequest = LocationRequest.create().apply {
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            interval = gpsTimer * 1000L // Use configured GPS timer
            fastestInterval = (gpsTimer / 2) * 1000L // Half of the interval
            maxWaitTime = gpsTimer * 2000L // Double the interval
        }

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
                        Log.d("BackgroundService", "Processing location update: ${location.latitude}, ${location.longitude}")
                        updateNotification(
                            "GPS Tracking Active", 
                            "Location: ${location.latitude}, ${location.longitude}\n" +
                            "Satellites: $connectedSatellites/$totalSatellites"
                        )
                        saveLocationData(location)
                        lastLocationUpdateTime = currentTime
                        lastLocation = location
                        Log.d("BackgroundService", "Location data saved and synced. Next update in ${gpsTimer} seconds")
                    } else {
                        Log.d("BackgroundService", "Skipping location update - no trigger met (timer, distance, speed)")
                    }
                }
            }
        }

        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                fusedLocationClient.requestLocationUpdates(
                    locationRequest,
                    locationCallback,
                    Looper.getMainLooper()
                )
                Log.d("BackgroundService", "Location updates started with interval: ${gpsTimer} seconds")
            } else {
                Log.e("BackgroundService", "Location permission not granted")
            }
        } catch (e: Exception) {
            Log.e("BackgroundService", "Error starting location updates", e)
        }
    }

    private fun calculateReason(currentLocation: Location): String {
        if (lastLocation == null) {
            return "Initial Position"
        }

        // Calculate speed in km/h
        val speed = currentLocation.speed * 3.6f

        // Calculate bearing change
        val bearingChange = Math.abs(currentLocation.bearing - lastLocation!!.bearing)
        val normalizedBearingChange = if (bearingChange > 180) 360 - bearingChange else bearingChange

        // Calculate distance moved
        val distance = currentLocation.distanceTo(lastLocation!!)

        // Update movement status
        if (speed > 1 || distance > 5) {
            if (!isMoving) {
                isMoving = true
                lastMovementTime = System.currentTimeMillis()
            }
            lastStopTime = System.currentTimeMillis()
        } else {
            if (isMoving && (System.currentTimeMillis() - lastStopTime) > stopTimer * 1000L) {
                isMoving = false
            }
        }

        // Determine reason based on movement patterns and configuration
        return when {
            // Check for over-speeding
            speed > overSpeedingThreshold -> "Over Speeding"
            
            // Check if the device is moving at all
            !isMoving -> "Idle"
            
            // Check for turns using configured angle threshold
            speed >= 5 && normalizedBearingChange > angleThreshold -> "Turn"
            
            // If moving but not turning, it's a move
            isMoving -> "Move"
            
            // Default case for very small movements
            else -> "Idle"
        }
    }

    private fun shouldProcessLocation(location: Location): Boolean {
        if (lastLocation == null) return true
        
        val distance = lastLocation!!.distanceTo(location)
        val speed = location.speed * 3.6f // Convert to km/h
        
        return distance > 5 || speed > 1 || 
               (System.currentTimeMillis() - lastLocationUpdateTime) >= gpsTimer * 1000L
    }

    private fun getImei(): String {
        return try {
            val prefs = getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
            prefs.getString("flutter.imei", "unknown") ?: "unknown"
        } catch (e: Exception) {
            Log.e("BackgroundService", "Error getting IMEI: ${e.message}")
            "unknown"
        }
    }

    private fun saveLocationData(location: Location) {
        try {
            val currentTime = System.currentTimeMillis()
            val imei = getImei()
            val reason = calculateReason(location)
            
            // Convert speed from m/s to km/h for storage
            var fixedSpeed = location.speed * 3.6f
            if (fixedSpeed < 0) fixedSpeed = 0f
            
            val values = ContentValues().apply {
                put("latitude", location.latitude)
                put("longitude", location.longitude)
                put("accuracy", location.accuracy)
                put("altitude", location.altitude)
                put("speed", fixedSpeed) // Now storing speed in km/h
                put("bearing", location.bearing)
                put("imei", imei)
                put("timestamp", java.time.Instant.now().toString())
                put("deviceRDT", java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss.SSS")))
                put("gmtSettings", "GMT+${java.time.ZoneId.systemDefault().rules.getOffset(java.time.Instant.now()).totalSeconds / 3600}:00 ${java.time.Year.now().value}")
                put("igStatus", 1L) // Explicitly use Long type
                put("localPrimaryId", currentTime % 100000)
                put("name", Build.MODEL)
                val serial = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Build.getSerial() else Build.SERIAL
                } catch (e: Exception) { "unknown" }
                put("phoneNo", serial)
                put("provider", "fused")
                put("reason", reason)
                put("versionNo", "v ${Build.VERSION.RELEASE}")
                put("sync_status", 0)
                put("created_at", currentTime)
            }

            val db = dbHelper.writableDatabase
            val id = db.insert("location_data", null, values)
            Log.d("BackgroundService", "Saved location data with ID: $id, Reason: $reason")
            
            // Update last location after saving
            lastLocation = location
        } catch (e: Exception) {
            Log.e("BackgroundService", "Error saving location data: ${e.message}", e)
            e.printStackTrace()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("BackgroundService", "Service starting")
        startForeground(NOTIFICATION_ID, createNotification())
        startLocationUpdates()
        return START_STICKY
    }

    private fun createNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("GPS Tracking")
        .setContentText("Initializing...")
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setVisibility(NotificationCompat.VISIBILITY_SECRET)
        .setStyle(NotificationCompat.DecoratedCustomViewStyle())
        .build()

    private fun updateNotification(title: String, content: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.create().apply {
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            interval = gpsTimer * 1000L // Use configured GPS timer
            fastestInterval = (gpsTimer / 2) * 1000L // Half of the interval
            maxWaitTime = gpsTimer * 2000L // Double the interval
        }

        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                fusedLocationClient.requestLocationUpdates(
                    locationRequest,
                    locationCallback,
                    Looper.getMainLooper()
                )
                Log.d("BackgroundService", "Location updates started with interval: ${gpsTimer} seconds")
            } else {
                Log.e("BackgroundService", "Location permission not granted")
            }
        } catch (e: Exception) {
            Log.e("BackgroundService", "Error starting location updates", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d("BackgroundService", "Service being destroyed")
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
                locationManager.unregisterGnssStatusCallback(gnssStatusCallback)
            }
            syncExecutor.shutdown()
            networkExecutor.shutdown()
        } catch (e: Exception) {
            Log.e("BackgroundService", "Error in onDestroy: ${e.message}")
        }
        wakeLock?.release()
        super.onDestroy()
        
        // Try to restart the service
        val intent = Intent(applicationContext, BackgroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
} 