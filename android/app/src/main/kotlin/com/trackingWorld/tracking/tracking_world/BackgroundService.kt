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

class BackgroundService : Service() {
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
    private val serverUrl = "http://ec2-3-83-201-132.compute-1.amazonaws.com:3000/api/location"
    private lateinit var dbHelper: LocationDatabaseHelper
    private val syncExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val networkExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var isSyncing = false

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
                    heading REAL,
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

    override fun onCreate() {
        super.onCreate()
        Log.d("BackgroundService", "Service created")
        dbHelper = LocationDatabaseHelper(this)
        acquireWakeLock()
        createNotificationChannel()
        setupLocationUpdates()
        startPeriodicSync()
    }

    private fun startPeriodicSync() {
        syncExecutor.scheduleAtFixedRate({
            if (!isSyncing) {
                syncData()
            }
        }, 5, 5, TimeUnit.MINUTES)
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
                        put("heading", cursor.getDouble(cursor.getColumnIndexOrThrow("heading")))
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
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "This channel is used for GPS tracking notifications"
                setSound(null, null)
                enableVibration(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun setupLocationUpdates() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    Log.d("BackgroundService", "Location update: ${location.latitude}, ${location.longitude}")
                    updateNotification("GPS Tracking Active", "Location: ${location.latitude}, ${location.longitude}")
                    saveLocationData(location)
                }
            }
        }
    }

    private fun saveLocationData(location: Location) {
        try {
            val prefs = getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
            val imei = prefs.getString("flutter.imei", "unknown") ?: "unknown"
            val currentTime = System.currentTimeMillis()

            val values = ContentValues().apply {
                put("latitude", location.latitude)
                put("longitude", location.longitude)
                put("accuracy", location.accuracy)
                put("altitude", location.altitude)
                put("speed", location.speed)
                put("heading", location.bearing)
                put("imei", imei)
                put("timestamp", java.time.Instant.now().toString())
                put("deviceRDT", java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss.SSS")))
                put("gmtSettings", "GMT+${java.time.ZoneId.systemDefault().rules.getOffset(java.time.Instant.now()).totalSeconds / 3600}:00 ${java.time.Year.now().value}")
                put("igStatus", 1)
                put("localPrimaryId", currentTime % 100000)
                put("name", imei)
                put("phoneNo", Build.MODEL)
                put("provider", "fused")
                put("reason", "Location Update")
                put("versionNo", "v ${Build.VERSION.RELEASE}")
                put("sync_status", 0)
                put("created_at", currentTime)
            }

            val db = dbHelper.writableDatabase
            val id = db.insert("location_data", null, values)
            Log.d("BackgroundService", "Saved location data with ID: $id")
            
            // Try to sync immediately
            if (!isSyncing) {
                syncData()
            }
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
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setOngoing(true)
        .build()

    private fun updateNotification(title: String, content: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.create().apply {
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            interval = 5000 // 5 seconds
            fastestInterval = 3000 // 3 seconds
            maxWaitTime = 10000 // 10 seconds
        }

        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                fusedLocationClient.requestLocationUpdates(
                    locationRequest,
                    locationCallback,
                    Looper.getMainLooper()
                )
                Log.d("BackgroundService", "Location updates started")
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