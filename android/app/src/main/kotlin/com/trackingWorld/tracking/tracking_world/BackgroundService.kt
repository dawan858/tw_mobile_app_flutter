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
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import kotlin.math.abs
import kotlin.math.sqrt

class BackgroundService : Service() {
    companion object {
        @JvmStatic
        var totalSatellites: Int = 0
        @JvmStatic
        var connectedSatellites: Int = 0
        
        private const val ACTION_RESTART_SERVICE = "com.trackingWorld.tracking.RESTART_SERVICE"
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
    private lateinit var gnssStatusCallback: GnssStatus.Callback
    private var lastLocationUpdateTime: Long = 0
    private var isMoving: Boolean = false
    private var lastMovementTime: Long = 0
    private var lastStopTime: Long = 0

    // Enhanced stationary detection
    private val recentLocations = mutableListOf<Location>()
    private val maxLocationBuffer = 5
    private var stationaryStartTime: Long = 0
    private var consecutiveStationaryCount = 0

    // Configuration parameters with defaults
    private var gpsTimer: Int = 5 // Default 5 seconds
    private var uploadTimer: Int = 10 // Default 10 seconds
    private var angleThreshold: Float = 45f // Default 45 degrees
    private var overSpeedingThreshold: Float = 60f // Default 60 km/h
    private var distanceThreshold: Float = 1000f // Default 1000 meters
    private var movingTimer: Int = 60 // Default 60 seconds
    private var stopTimer: Int = 130 // Default 130 seconds
    
    private val restartReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_RESTART_SERVICE) {
                Log.d("BackgroundService", "Received restart broadcast")
                startLocationUpdates()
            }
        }
    }

    inner class LocationDatabaseHelper(context: Context) : SQLiteOpenHelper(context, "location_tracking.db", null, 3) {
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
            
            // Create indexes for performance
            db.execSQL("CREATE INDEX idx_sync_status ON location_data(sync_status)")
            db.execSQL("CREATE INDEX idx_created_at ON location_data(created_at)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 3) {
                try {
                    db.execSQL("CREATE INDEX IF NOT EXISTS idx_sync_status ON location_data(sync_status)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS idx_created_at ON location_data(created_at)")
                } catch (e: Exception) {
                    Log.e("BackgroundService", "Error creating indexes: ${e.message}")
                }
            }
        }
        
        fun maintainRecordLimit() {
            val db = writableDatabase
            try {
                val cursor = db.rawQuery("SELECT COUNT(*) FROM location_data", null)
                cursor.moveToFirst()
                val totalRecords = cursor.getInt(0)
                cursor.close()
                
                if (totalRecords > 1000) {
                    val recordsToDelete = totalRecords - 1000
                    db.execSQL("""
                        DELETE FROM location_data 
                        WHERE id IN (
                            SELECT id FROM location_data 
                            WHERE sync_status = 1 
                            ORDER BY created_at ASC 
                            LIMIT ?
                        )
                    """, arrayOf(recordsToDelete))
                    
                    Log.d("BackgroundService", "Deleted $recordsToDelete old synced records to maintain 1000 record limit")
                }
            } catch (e: Exception) {
                Log.e("BackgroundService", "Error maintaining record limit: ${e.message}")
            }
        }
    }

    override fun onCreate() {
    super.onCreate()
    Log.d("BackgroundService", "=== BACKGROUND SERVICE CREATED ===")
    Log.d("BackgroundService", "Process ID: ${android.os.Process.myPid()}")
    Log.d("BackgroundService", "Thread: ${Thread.currentThread().name}")
    
    // Initialize components
    dbHelper = LocationDatabaseHelper(this)
    acquireWakeLock()
    createNotificationChannel()
    fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
    
    // CRITICAL: Ensure IMEI is available
    ensureImeiAvailable()
    
    // Register restart receiver
    val filter = IntentFilter(ACTION_RESTART_SERVICE)
    registerReceiver(restartReceiver, filter)
    
    loadConfiguration()
    setupLocationUpdates()
    startPeriodicSync()
    
    // Perform initial health check
    verifyServiceHealth()
    
    // Schedule periodic health checks
    val handler = android.os.Handler(Looper.getMainLooper())
    handler.post(object : Runnable {
        override fun run() {
            logServiceStatus()
            validateImeiPeriodically()
            verifyServiceHealth()
            handler.postDelayed(this, 30000) // Every 30 seconds
        }
    })
    
    Log.d("BackgroundService", "✅ Service initialization completed")
}

   

private fun validateImeiPeriodically() {
    try {
        val currentImei = getImei()
        
        // Log IMEI status
        Log.d("BackgroundService", "Periodic IMEI check: $currentImei")
        
        // If IMEI is invalid, try to refresh
        if (currentImei == "unknown" || currentImei.isEmpty()) {
            Log.w("BackgroundService", "Invalid IMEI detected during periodic check, attempting refresh...")
            
            val imeiManager = ImeiManager.getInstance(this)
            val refreshedImei = imeiManager.validateAndRefreshImei()
            Log.d("BackgroundService", "Periodic refresh result: $refreshedImei")
        }
        
    } catch (e: Exception) {
        Log.e("BackgroundService", "Error in periodic IMEI validation: ${e.message}")
    }
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

            if (::fusedLocationClient.isInitialized && ::locationCallback.isInitialized) {
                updateLocationRequestInterval()
            }
            updateSyncInterval()
            
            Log.d("BackgroundService", "Configuration loaded successfully")
        } catch (e: Exception) {
            Log.e("BackgroundService", "Error loading configuration: ${e.message}")
        }
    }

    private fun updateLocationRequestInterval() {
        try {
            if (!::fusedLocationClient.isInitialized || !::locationCallback.isInitialized) {
                Log.w("BackgroundService", "Location components not initialized yet")
                return
            }

            fusedLocationClient.removeLocationUpdates(locationCallback)
            val locationRequest = LocationRequest.create().apply {
                priority = LocationRequest.PRIORITY_HIGH_ACCURACY
                interval = gpsTimer * 1000L
                fastestInterval = 1000L
                maxWaitTime = gpsTimer * 2000L
                smallestDisplacement = 1f
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

    private fun addLocationToBuffer(location: Location) {
        recentLocations.add(location)
        if (recentLocations.size > maxLocationBuffer) {
            recentLocations.removeAt(0)
        }
    }

    private fun calculateSpeedFromDistance(currentLocation: Location, previousLocation: Location?): Float {
        if (previousLocation == null) return 0f
        
        val distance = currentLocation.distanceTo(previousLocation) // meters
        val timeDiff = (currentLocation.time - previousLocation.time) / 1000f // seconds
        
        return if (timeDiff > 0) {
            (distance / timeDiff) * 3.6f // Convert m/s to km/h
        } else {
            0f
        }
    }

    private fun calculatePositionStability(): Float {
        if (recentLocations.size < 3) return 0f
        
        // Calculate average position
        var avgLat = 0.0
        var avgLng = 0.0
        for (loc in recentLocations) {
            avgLat += loc.latitude
            avgLng += loc.longitude
        }
        avgLat /= recentLocations.size
        avgLng /= recentLocations.size
        
        // Calculate variance
        var variance = 0f
        for (loc in recentLocations) {
            val dist = FloatArray(1)
            Location.distanceBetween(avgLat, avgLng, loc.latitude, loc.longitude, dist)
            variance += dist[0] * dist[0]
        }
        variance /= recentLocations.size
        
        return 1f / (1f + variance / 100f) // Normalize to 0-1
    }

    private fun isDeviceStationary(): Boolean {
        if (recentLocations.size < 3) return false
        
        // Check if all recent positions are within 15 meters
        var maxDistance = 0f
        for (i in 0 until recentLocations.size - 1) {
            for (j in i + 1 until recentLocations.size) {
                val distance = recentLocations[i].distanceTo(recentLocations[j])
                if (distance > maxDistance) maxDistance = distance
            }
        }
        
        return maxDistance < 15f
    }

    private fun getEnhancedAccurateSpeed(location: Location): Float {
        addLocationToBuffer(location)
        
        val gpsSpeed = if (location.hasSpeed() && location.speed >= 0) {
            location.speed * 3.6f // Convert m/s to km/h
        } else {
            0f
        }
        
        val calculatedSpeed = if (lastLocation != null && lastLocation!!.time != location.time) {
            calculateSpeedFromDistance(location, lastLocation)
        } else {
            0f
        }
        
        val stability = calculatePositionStability()
        val isStationary = isDeviceStationary()
        
        Log.d("BackgroundService", "Speed Analysis:")
        Log.d("BackgroundService", "  GPS: ${String.format("%.1f", gpsSpeed)} km/h")
        Log.d("BackgroundService", "  Calculated: ${String.format("%.1f", calculatedSpeed)} km/h")
        Log.d("BackgroundService", "  Accuracy: ${String.format("%.1f", location.accuracy)}m")
        Log.d("BackgroundService", "  Stability: ${String.format("%.1f", stability * 100)}%")
        Log.d("BackgroundService", "  Stationary: $isStationary")
        
        // Enhanced stationary detection
        if (isStationary) {
            consecutiveStationaryCount++
            if (consecutiveStationaryCount >= 3) {
                Log.d("BackgroundService", "Device confirmed stationary (${consecutiveStationaryCount} consecutive)")
                return 0f
            }
        } else {
            consecutiveStationaryCount = 0
        }
        
        // Filter unrealistic speeds
        if (gpsSpeed > 100f || calculatedSpeed > 100f) {
            Log.d("BackgroundService", "Filtering unrealistic speed -> 0 km/h")
            return 0f
        }
        
        // Accuracy-based filtering
        if (location.accuracy > 25f) {
            // Poor accuracy - be very conservative
            if (gpsSpeed < 8f && calculatedSpeed < 8f && stability < 0.3f) {
                return 0f
            }
        } else {
            // Good accuracy - normal filtering
            if (gpsSpeed < 5f && calculatedSpeed < 5f && stability < 0.5f) {
                return 0f
            }
        }
        
        // Return the most reliable speed
        var finalSpeed = 0f
        if (gpsSpeed >= 5f && calculatedSpeed >= 5f) {
            finalSpeed = (gpsSpeed + calculatedSpeed) / 2f // Average both
        } else if (gpsSpeed >= 8f) {
            finalSpeed = gpsSpeed
        } else if (calculatedSpeed >= 8f) {
            finalSpeed = calculatedSpeed
        }
        
        Log.d("BackgroundService", "  Final: ${String.format("%.1f", finalSpeed)} km/h")
        return finalSpeed
    }

    private fun calculateEnhancedReason(currentLocation: Location): String {
        if (lastLocation == null) {
            return "Initial Position"
        }

        val speed = getEnhancedAccurateSpeed(currentLocation)
        val bearingChange = abs(currentLocation.bearing - lastLocation!!.bearing)
        val normalizedBearingChange = if (bearingChange > 180) 360 - bearingChange else bearingChange
        val distance = currentLocation.distanceTo(lastLocation!!)

        // Enhanced movement detection with stricter criteria
        val isSignificantMovement = speed >= 8f || (distance > 20f && speed > 3f)

        if (isSignificantMovement) {
            if (!isMoving) {
                isMoving = true
                lastMovementTime = System.currentTimeMillis()
                Log.d("BackgroundService", "Movement detected: Speed ${String.format("%.1f", speed)} km/h")
            }
            lastStopTime = System.currentTimeMillis()
        } else {
            // Faster transition to idle (15 seconds)
            if (isMoving && (System.currentTimeMillis() - lastStopTime) > 15000L) {
                isMoving = false
                Log.d("BackgroundService", "Movement stopped - transitioning to idle")
            }
        }

        return when {
            speed > overSpeedingThreshold -> "Over Speeding"
            speed < 3f -> "Idle"
            !isMoving -> "Idle"
            speed >= 10f && normalizedBearingChange > angleThreshold -> "Turn"
            isMoving && speed >= 8f -> "Move"
            else -> "Idle"
        }
    }

    private fun shouldProcessLocationUpdate(location: Location, speed: Float, distance: Float, timeSinceLastUpdate: Long): Boolean {
        return when {
            lastLocation == null -> true
            location.accuracy > 50 -> false // Skip very inaccurate locations
            timeSinceLastUpdate >= gpsTimer * 1000L -> true
            distance >= distanceThreshold -> true
            speed >= overSpeedingThreshold -> true
            speed >= 8f && distance > 15f -> true // Significant movement
            else -> false
        }
    }

    private fun logServiceStatus() {
        Log.d("BackgroundService", "=== SERVICE STATUS ===")
        Log.d("BackgroundService", "Service running: ${this.javaClass.simpleName}")
        Log.d("BackgroundService", "GPS Timer: ${gpsTimer}s")
        Log.d("BackgroundService", "Location client initialized: ${::fusedLocationClient.isInitialized}")
        Log.d("BackgroundService", "Location callback initialized: ${::locationCallback.isInitialized}")
        
        try {
            val db = dbHelper.readableDatabase
            val cursor = db.rawQuery("SELECT COUNT(*) FROM location_data", null)
            cursor.moveToFirst()
            val totalRecords = cursor.getInt(0)
            cursor.close()
            
            val unsyncedCursor = db.rawQuery("SELECT COUNT(*) FROM location_data WHERE sync_status = 0", null)
            unsyncedCursor.moveToFirst()
            val unsyncedRecords = unsyncedCursor.getInt(0)
            unsyncedCursor.close()
            
            Log.d("BackgroundService", "Database records - Total: $totalRecords, Unsynced: $unsyncedRecords")
        } catch (e: Exception) {
            Log.e("BackgroundService", "Error checking database: ${e.message}")
        }
    }

    private fun startPeriodicSync() {
        syncExecutor.scheduleAtFixedRate({
            if (!isSyncing) {
                syncData()
            }
        }, 5, uploadTimer.toLong(), TimeUnit.SECONDS)
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
                    "100"
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
                                Log.e("BackgroundService", "Failed to sync data. Status: ${response.code}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("BackgroundService", "Error syncing data: ${e.message}")
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
                            db.update("location_data", values, "id = ?", arrayOf(id.toString()))
                        }
                        db.setTransactionSuccessful()
                        Log.d("BackgroundService", "Successfully marked ${syncedIds.size} records as synced")
                    } finally {
                        db.endTransaction()
                    }
                    
                    dbHelper.maintainRecordLimit()
                }
            } catch (e: Exception) {
                Log.e("BackgroundService", "Error in sync process: ${e.message}")
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

    private fun createNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("GPS Tracking Active")
        .setContentText("Service running in background")
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setAutoCancel(false)
        .setShowWhen(true)
        .setWhen(System.currentTimeMillis())
        .setStyle(NotificationCompat.BigTextStyle().bigText(
            "GPS Tracking Service is active and monitoring location.\n" +
            "Started: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}\n" +
            "IMEI: ${"unknown".take(10)}..."
        ))
        .build()

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

// Method to verify service is working correctly
private fun verifyServiceHealth() {
    Log.d("BackgroundService", "=== SERVICE HEALTH CHECK ===")
    
    try {
        // Check IMEI
        val imei = "unknown"
        Log.d("BackgroundService", "IMEI Status: ${if (imei.isNotEmpty() && imei != "unknown") "✅ Valid" else "❌ Invalid"}")
        
        // Check location client
        Log.d("BackgroundService", "Location Client: ${if (::fusedLocationClient.isInitialized) "✅ Ready" else "❌ Not Ready"}")
        
        // Check database
        try {
            val db = dbHelper.readableDatabase
            val cursor = db.rawQuery("SELECT COUNT(*) FROM location_data", null)
            cursor.moveToFirst()
            val recordCount = cursor.getInt(0)
            cursor.close()
            Log.d("BackgroundService", "Database: ✅ $recordCount records")
        } catch (e: Exception) {
            Log.e("BackgroundService", "Database: ❌ Error - ${e.message}")
        }
        
        // Check network connectivity
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val networkInfo = connectivityManager.activeNetworkInfo
        Log.d("BackgroundService", "Network: ${if (networkInfo?.isConnected == true) "✅ Connected" else "❌ Disconnected"}")
        
        Log.d("BackgroundService", "✅ Service health check completed")
        
    } catch (e: Exception) {
        Log.e("BackgroundService", "❌ Error in health check: ${e.message}")
    }
}

    private fun debugGnssStatus() {
    Log.d("BackgroundService", "=== GNSS DEBUG INFO ===")
    Log.d("BackgroundService", "Total Satellites: $totalSatellites")
    Log.d("BackgroundService", "Connected Satellites: $connectedSatellites")
    Log.d("BackgroundService", "GNSS Callback initialized: ${::gnssStatusCallback.isInitialized}")
    
    // Check if location manager is available
    val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
    Log.d("BackgroundService", "GPS Provider enabled: ${locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)}")
    Log.d("BackgroundService", "Network Provider enabled: ${locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)}")
    
    // Check GNSS status registration
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        Log.d("BackgroundService", "Android version supports GNSS status callbacks")
    } else {
        Log.d("BackgroundService", "Android version does not support GNSS status callbacks")
    }
}
private fun setupEnhancedGnssCallback() {
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
            
            Log.d("BackgroundService", "=== SATELLITE STATUS UPDATE ===")
            Log.d("BackgroundService", "Total satellites visible: $totalSatellites")
            
            for (i in 0 until status.satelliteCount) {
                val usedInFix = status.usedInFix(i)
                val hasEphemeris = status.hasEphemerisData(i)
                val hasAlmanac = status.hasAlmanacData(i)
                val cn0DbHz = status.getCn0DbHz(i)
                
                if (usedInFix) {
                    connectedSatellites++
                }
                
                Log.d("BackgroundService", "Satellite $i: Used=$usedInFix, Ephemeris=$hasEphemeris, Almanac=$hasAlmanac, CN0=$cn0DbHz")
            }
            
            Log.d("BackgroundService", "Connected satellites: $connectedSatellites")
            Log.d("BackgroundService", "Satellite ratio: $connectedSatellites/$totalSatellites")
            
            // Update notification with satellite info
            updateNotificationWithSatellites()
        }
    }
}

// Force register GNSS callback
private fun forceRegisterGnssCallback() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                // Unregister first if already registered
                try {
                    locationManager.unregisterGnssStatusCallback(gnssStatusCallback)
                    Log.d("BackgroundService", "Unregistered existing GNSS callback")
                } catch (e: Exception) {
                    Log.d("BackgroundService", "No existing GNSS callback to unregister")
                }
                
                // Register new callback
                val success = locationManager.registerGnssStatusCallback(gnssStatusCallback)
                Log.d("BackgroundService", "GNSS callback registration success: $success")
                
                if (success) {
                    Log.d("BackgroundService", "GNSS status callback registered successfully")
                } else {
                    Log.e("BackgroundService", "Failed to register GNSS status callback")
                }
            } else {
                Log.e("BackgroundService", "Missing location permission for GNSS callback")
            }
        } catch (e: Exception) {
            Log.e("BackgroundService", "Error registering GNSS callback: ${e.message}")
        }
    } else {
        Log.w("BackgroundService", "GNSS status callbacks not supported on this Android version")
    }
}

private fun updateNotificationWithSatellites() {
    val notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("GPS Tracking Active")
        .setContentText("Satellites: $connectedSatellites/$totalSatellites")
        .setStyle(NotificationCompat.BigTextStyle().bigText(
            "Satellites: $connectedSatellites/$totalSatellites\n" +
            "Status: ${if (connectedSatellites > 0) "GPS Lock" else "Searching..."}\n" +
            "Service: Active"
        ))
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setVisibility(NotificationCompat.VISIBILITY_SECRET)
        .build()

    val notificationManager = getSystemService(NotificationManager::class.java)
    notificationManager.notify(NOTIFICATION_ID, notification)
}



    private fun setupLocationUpdates() {


        // Setup enhanced GNSS callback
    setupEnhancedGnssCallback()
    
    // Force register GNSS callback
    forceRegisterGnssCallback()
    
    // Debug GNSS status
    debugGnssStatus()


        gnssStatusCallback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                totalSatellites = status.satelliteCount
                connectedSatellites = 0
                
                for (i in 0 until status.satelliteCount) {
                    if (status.usedInFix(i)) {
                        connectedSatellites++
                    }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                locationManager.registerGnssStatusCallback(gnssStatusCallback)
            }
        }
        
        val locationRequest = LocationRequest.create().apply {
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            interval = gpsTimer * 1000L
            fastestInterval = 1000L
            maxWaitTime = gpsTimer * 2000L
            smallestDisplacement = 1f
        }

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                Log.d("BackgroundService", "=== LOCATION CALLBACK TRIGGERED ===")
                
                locationResult.lastLocation?.let { location ->
                    if (location.accuracy > 30) {
                        Log.d("BackgroundService", "Skipping inaccurate location: accuracy = ${location.accuracy}m")
                        return
                    }
                    
                    val currentTime = System.currentTimeMillis()
                    val timeSinceLastUpdate = currentTime - lastLocationUpdateTime
                    val speed = getEnhancedAccurateSpeed(location)
                    val distance = lastLocation?.distanceTo(location) ?: 0f

                    if (shouldProcessLocationUpdate(location, speed, distance, timeSinceLastUpdate)) {
                        val reason = calculateEnhancedReason(location)
                        
                        Log.d("BackgroundService", "=== PROCESSING LOCATION UPDATE ===")
                        Log.d("BackgroundService", "Location: ${location.latitude}, ${location.longitude}")
                        Log.d("BackgroundService", "Speed: ${String.format("%.1f", speed)} km/h")
                        Log.d("BackgroundService", "Reason: $reason")
                        
                        updateNotification(
                            "GPS Tracking Active", 
                            "Speed: ${String.format("%.1f", speed)} km/h\n" +
                            "Accuracy: ${String.format("%.1f", location.accuracy)}m\n" +
                            "Reason: $reason\n" +
                            "Satellites: $connectedSatellites/$totalSatellites"
                        )
                        
                        val correctedLocation = Location(location).apply {
                            this.speed = speed / 3.6f // Convert back to m/s for storage
                        }
                        
                        saveLocationData(correctedLocation)
                        lastLocationUpdateTime = currentTime
                        lastLocation = location
                    } else {
                        Log.d("BackgroundService", "Skipping location update - Speed: ${String.format("%.1f", speed)} km/h")
                    }
                }
            }
        }

        startLocationUpdates()
    }

    private fun getImei(): String {
    Log.d("BackgroundService", "=== GETTING IMEI FOR LOCATION DATA (IMEI ONLY) ===")
    
    try {
        // Use the ImeiManager for IMEI only
        val imeiManager = ImeiManager.getInstance(this)
        val deviceId = imeiManager.getDeviceIdentifier()
        
        Log.d("BackgroundService", "Got device ID for location: $deviceId")
        
        if (deviceId != "unknown" && deviceId.isNotEmpty()) {
            return deviceId
        }
        
        // Check SharedPreferences as backup
        val prefs = getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
        val storedImei = prefs.getString("flutter.imei", null)
        
        if (!storedImei.isNullOrEmpty() && storedImei != "unknown") {
            Log.d("BackgroundService", "Using stored IMEI: $storedImei")
            return storedImei
        }
        
        // No fallbacks - return "unknown" if IMEI is not available
        Log.e("BackgroundService", "❌ IMEI not available")
        return "unknown"
        
    } catch (e: Exception) {
        Log.e("BackgroundService", "❌ Critical error getting IMEI: ${e.message}")
        return "unknown"
    }
}

// Add this method to validate IMEI before saving location data
// Updated ensureImeiAvailable method - IMEI ONLY
private fun ensureImeiAvailable() {
    Log.d("BackgroundService", "=== ENSURING IMEI IS AVAILABLE (IMEI ONLY) ===")
    
    try {
        val imeiManager = ImeiManager.getInstance(this)
        val deviceId = imeiManager.getDeviceIdentifier()
        
        if (deviceId != "unknown" && deviceId.isNotEmpty()) {
            Log.d("BackgroundService", "✅ IMEI secured for service: $deviceId")
        } else {
            Log.e("BackgroundService", "❌ CRITICAL: Service cannot start without IMEI!")
            Log.e("BackgroundService", "❌ Check READ_PHONE_STATE permission and device support")
        }
        
    } catch (e: Exception) {
        Log.e("BackgroundService", "❌ CRITICAL ERROR ensuring IMEI: ${e.message}")
    }
}

private fun validateImeiBeforeSave(): String {
    val imei = getImei()
    
    Log.d("BackgroundService", "Validating IMEI before save: $imei")
    
    // Check if IMEI is valid
    if (imei == "unknown" || imei.isEmpty()) {
        Log.w("BackgroundService", "Invalid IMEI detected, attempting refresh...")
        
        try {
            val imeiManager = ImeiManager.getInstance(this)
            val freshImei = imeiManager.forceRefreshImei()
            Log.d("BackgroundService", "Refreshed IMEI: $freshImei")
            
            if (freshImei != "unknown" && freshImei.isNotEmpty()) {
                return freshImei
            } else {
                Log.e("BackgroundService", "❌ Still no valid IMEI after refresh")
                return "unknown"
            }
        } catch (e: Exception) {
            Log.e("BackgroundService", "Error refreshing IMEI: ${e.message}")
            return "unknown"
        }
    }
    
    return imei
}

    private fun saveLocationData(location: Location) {
        try {
            val currentTime = System.currentTimeMillis()
            val imei = validateImeiBeforeSave()
            val reason = calculateEnhancedReason(location)
            
            var fixedSpeed = location.speed * 3.6f
            if (fixedSpeed < 0) fixedSpeed = 0f
            
            Log.d("BackgroundService", "=== SAVING LOCATION DATA ===")
            Log.d("BackgroundService", "Speed: ${String.format("%.1f", fixedSpeed)} km/h")
            Log.d("BackgroundService", "Reason: $reason")


            // Validate IMEI before saving
        if (imei.isEmpty() || imei == "unknown") {
            Log.e("BackgroundService", "❌ CRITICAL: Cannot save location without valid IMEI!")
            return
        }
            
            val values = ContentValues().apply {
                put("latitude", location.latitude)
                put("longitude", location.longitude)
                put("accuracy", location.accuracy)
                put("altitude", location.altitude)
                put("speed", fixedSpeed)
                put("bearing", location.bearing)
                put("imei", imei)
                put("timestamp", java.time.Instant.now().toString())
                put("deviceRDT", java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss.SSS")))
                put("gmtSettings", "GMT+${java.time.ZoneId.systemDefault().rules.getOffset(java.time.Instant.now()).totalSeconds / 3600}:00 ${java.time.Year.now().value}")
                put("igStatus", 1L)
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
            Log.d("BackgroundService", "Saved location data with ID: $id")
        } catch (e: Exception) {
            Log.e("BackgroundService", "Error saving location data: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    Log.d("BackgroundService", "=== SERVICE START COMMAND ===")
    Log.d("BackgroundService", "Intent: ${intent?.action}")
    Log.d("BackgroundService", "Flags: $flags")
    Log.d("BackgroundService", "StartId: $startId")
    
    // Check if started automatically
    val autoStarted = intent?.getBooleanExtra("auto_started", false) ?: false
    val startedBy = intent?.getStringExtra("started_by") ?: "unknown"
    
    if (autoStarted) {
        Log.d("BackgroundService", "✅ Service auto-started by: $startedBy")
    }
    
    // Ensure IMEI is available
    ensureImeiAvailable()
    
    // Create and show notification immediately
    val notification = createNotification()
    startForeground(NOTIFICATION_ID, notification)
    
    // Start location tracking
    startLocationUpdates()
    
    Log.d("BackgroundService", "✅ Service fully started and tracking")
    
    // Return START_STICKY to ensure restart if killed
    return START_STICKY
}

   

    private fun createStopIntent(): PendingIntent {
        val intent = Intent(this, BackgroundService::class.java)
        intent.action = "STOP_SERVICE"
        return PendingIntent.getService(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun updateNotification(title: String, content: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.create().apply {
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            interval = gpsTimer * 1000L
            fastestInterval = 1000L
            maxWaitTime = gpsTimer * 2000L
            smallestDisplacement = 1f
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
    Log.d("BackgroundService", "=== SERVICE BEING DESTROYED ===")
    
    try {
        // Clean up resources
        fusedLocationClient.removeLocationUpdates(locationCallback)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            locationManager.unregisterGnssStatusCallback(gnssStatusCallback)
        }
        unregisterReceiver(restartReceiver)
        syncExecutor.shutdown()
        networkExecutor.shutdown()
    } catch (e: Exception) {
        Log.e("BackgroundService", "Error in cleanup: ${e.message}")
    }
    
    wakeLock?.release()
    
    // CRITICAL: Schedule multiple restart attempts
    scheduleMultipleRestarts()
    
    super.onDestroy()
}

private fun scheduleMultipleRestarts() {
    Log.d("BackgroundService", "=== SCHEDULING SERVICE RESTARTS ===")
    
    try {
        // Method 1: Immediate restart broadcast
        val restartIntent = Intent(ACTION_RESTART_SERVICE)
        sendBroadcast(restartIntent)
        Log.d("BackgroundService", "✅ Restart broadcast sent")
        
        // Method 2: Direct service restart
        val serviceIntent = Intent(applicationContext, BackgroundService::class.java)
        serviceIntent.putExtra("auto_started", true)
        serviceIntent.putExtra("started_by", "SERVICE_RESTART")
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Log.d("BackgroundService", "✅ Direct service restart attempted")
        } catch (e: Exception) {
            Log.e("BackgroundService", "❌ Direct restart failed: ${e.message}")
        }
        
        // Method 3: Delayed restart using AlarmManager
        scheduleDelayedRestart()
        
    } catch (e: Exception) {
        Log.e("BackgroundService", "❌ Error scheduling restarts: ${e.message}")
    }
}

private fun scheduleDelayedRestart() {
    try {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val restartIntent = Intent(this, BootReceiver::class.java).apply {
            action = "com.trackingWorld.tracking.DELAYED_RESTART"
        }
        
        val pendingIntent = android.app.PendingIntent.getBroadcast(
            this, 
            999, 
            restartIntent, 
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        
        val triggerTime = System.currentTimeMillis() + 30000 // 30 seconds delay
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                android.app.AlarmManager.RTC_WAKEUP,
                triggerTime,
                pendingIntent
            )
        } else {
            alarmManager.setExact(
                android.app.AlarmManager.RTC_WAKEUP,
                triggerTime,
                pendingIntent
            )
        }
        
        Log.d("BackgroundService", "✅ Delayed restart scheduled for 30 seconds")
        
    } catch (e: Exception) {
        Log.e("BackgroundService", "❌ Error scheduling delayed restart: ${e.message}")
    }
}
    
    private fun scheduleRestart() {
        Log.d("BackgroundService", "Scheduling service restart")
        val intent = Intent(ACTION_RESTART_SERVICE)
        sendBroadcast(intent)
        
        // Also try to restart immediately
        val serviceIntent = Intent(applicationContext, BackgroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }
}