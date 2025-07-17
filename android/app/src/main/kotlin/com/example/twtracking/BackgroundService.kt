package com.example.twtracking

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
import android.app.AlarmManager
import android.os.SystemClock
import com.google.gson.Gson
import android.os.Handler

class BackgroundService : Service() {
    companion object {
        @JvmStatic
        var totalSatellites: Int = 0
        @JvmStatic
        var connectedSatellites: Int = 0
        
        private const val ACTION_RESTART_SERVICE = "com.trackingWorld.tracking.RESTART_SERVICE"
        private const val TAG = "BackgroundService"
    }

    // Service-owned CarPowerManager - NO foreground dependency
    private var carPowerManager: CarPowerManager? = null
    private var isCarPowerInitialized = false // ADD THIS MISSING VARIABLE

    private var isIgStatusReady = false // NEW: Flag to check if igStatus is reliable

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private val CHANNEL_ID = "tracking_service"
    private val NOTIFICATION_ID = 888
    private var wakeLock: PowerManager.WakeLock? = null
    private var cpuWakeLock: PowerManager.WakeLock? = null
    private var screenWakeLock: PowerManager.WakeLock? = null
    private var wifiWakeLock: PowerManager.WakeLock? = null
    private var gpsWakeLock: PowerManager.WakeLock? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    private val serverUrl = "http://twca.trackingworld.com.pk:3000/api/location"
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
    private var gpsTimer: Int = 5
    private var uploadTimer: Int = 10
    private var angleThreshold: Float = 45f
    private var overSpeedingThreshold: Float = 60f
    private var distanceThreshold: Float = 1000f
    private var movingTimer: Int = 60
    private var stopTimer: Int = 130
    
    private val restartReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_RESTART_SERVICE) {
                Log.d(TAG, "Received restart broadcast")
                startLocationUpdates()
            }
        }
    }

    private var igStatus = 0 // Initialize to 0 (ACC off)
    private var isCarPowerAvailable = false
    private var serviceStartAttempts = 0
    private val MAX_START_ATTEMPTS = 3

    private val mainHandler = Handler(Looper.getMainLooper())

    private val accStateListener: (Boolean) -> Unit = { isAccOn ->
        mainHandler.post {
            try {
                val newIgStatus = if (isAccOn) 1 else 0
                val oldStatus = igStatus

                if (!isIgStatusReady) {
                    Log.d(TAG, "✅ igStatus is now ready. Initial igStatus: $newIgStatus")
                    isIgStatusReady = true
                }
                
                Log.d(TAG, "🚗 ACC Callback Received. isAccOn: $isAccOn, newIgStatus: $newIgStatus, oldStatus: $oldStatus")

                igStatus = newIgStatus // Always update to the latest from the source of truth

                if (newIgStatus != oldStatus) {
                    Log.d(TAG, "🔄 igStatus updated: $oldStatus -> $newIgStatus")
                    updateNotificationWithAccState(isAccOn)

                    // Save a new location point with the changed igStatus and sync
                    if (lastLocation != null) {
                        saveLocationData(lastLocation!!)
                        // Trigger immediate sync
                        syncExecutor.execute {
                            performSyncToServer()
                        }
                    } else {
                        Log.w(TAG, "⚠️ No location available, igStatus changed but not synced yet")
                    }
                } else {
                    Log.d(TAG, "ℹ️ igStatus value confirmed: $newIgStatus")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error in accStateListener", e)
            }
        }
    }

    inner class LocationDatabaseHelper(context: Context) : SQLiteOpenHelper(context, "location_tracking.db", null, 4) {
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
                    createAt TEXT
                )
            """)
            
            db.execSQL("CREATE INDEX idx_sync_status ON location_data(sync_status)")
            db.execSQL("CREATE INDEX idx_createAt ON location_data(createAt)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 3) {
                try {
                    db.execSQL("CREATE INDEX IF NOT EXISTS idx_sync_status ON location_data(sync_status)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS idx_createAt ON location_data(createAt)")
                } catch (e: Exception) {
                    Log.e(TAG, "Error creating indexes: ${e.message}")
                }
            }
            
            if (oldVersion < 4) {
                try {
                    // Migrate created_at to createAt (camelCase)
                    // Check if created_at column exists
                    val cursor = db.rawQuery("PRAGMA table_info(location_data)", null)
                    var hasCreatedAt = false
                    while (cursor.moveToNext()) {
                        val columnName = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                        if (columnName == "created_at") {
                            hasCreatedAt = true
                            break
                        }
                    }
                    cursor.close()
                    
                    if (hasCreatedAt) {
                        // Add createAt column
                        db.execSQL("ALTER TABLE location_data ADD COLUMN createAt TEXT")
                        
                        // Copy data from created_at to createAt
                        db.execSQL("UPDATE location_data SET createAt = created_at")
                        
                        Log.d(TAG, "Successfully migrated created_at to createAt for version 4")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error migrating created_at to createAt: ${e.message}")
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
                            ORDER BY createAt ASC 
                            LIMIT ?
                        )
                    """, arrayOf(recordsToDelete))
                    
                    Log.d(TAG, "Deleted $recordsToDelete old synced records to maintain 1000 record limit")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error maintaining record limit: ${e.message}")
            }
        }

        // NEW METHOD: Get unsynced data
        fun getUnsyncedData(limit: Int = 50): List<Map<String, Any>> {
            val db = readableDatabase
            val data = mutableListOf<Map<String, Any>>()
            
            try {
                val cursor = db.query(
                    "location_data",
                    null,
                    "sync_status = ?",
                    arrayOf("0"),
                    null,
                    null,
                    "createAt ASC",
                    limit.toString()
                )
                
                while (cursor.moveToNext()) {
                    val row = mutableMapOf<String, Any>()
                    for (i in 0 until cursor.columnCount) {
                        val columnName = cursor.getColumnName(i)
                        when (cursor.getType(i)) {
                            android.database.Cursor.FIELD_TYPE_INTEGER -> row[columnName] = cursor.getLong(i)
                            android.database.Cursor.FIELD_TYPE_FLOAT -> row[columnName] = cursor.getDouble(i)
                            android.database.Cursor.FIELD_TYPE_STRING -> row[columnName] = cursor.getString(i)
                            android.database.Cursor.FIELD_TYPE_BLOB -> row[columnName] = cursor.getBlob(i)
                            else -> row[columnName] = cursor.getString(i) ?: ""
                        }
                    }
                    data.add(row)
                }
                cursor.close()
                
                Log.d(TAG, "Retrieved ${data.size} unsynced records")
            } catch (e: Exception) {
                Log.e(TAG, "Error getting unsynced data: ${e.message}")
            }
            
            return data
        }

        // NEW METHOD: Mark records as synced
        fun markAsSynced(ids: List<Long>) {
            if (ids.isEmpty()) return
            
            val db = writableDatabase
            try {
                val placeholders = ids.joinToString(",") { "?" }
                val args = ids.map { it.toString() }.toTypedArray()
                
                val updatedRows = db.update(
                    "location_data",
                    ContentValues().apply { put("sync_status", 1) },
                    "id IN ($placeholders)",
                    args
                )
                
                Log.d(TAG, "Marked $updatedRows records as synced")
            } catch (e: Exception) {
                Log.e(TAG, "Error marking records as synced: ${e.message}")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "=== BACKGROUND SERVICE ONCREATE (INDEPENDENT) ===")
        Log.d(TAG, "Process ID: ${android.os.Process.myPid()}")
        Log.d(TAG, "Thread: ${Thread.currentThread().name}")
        
        try {
            // Clear old igStatus values to start fresh
            clearOldIgStatusValues()
            
            dbHelper = LocationDatabaseHelper(this)
            acquireWakeLock()
            createNotificationChannel()
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
            
            // CRITICAL: Initialize CarPowerManager INSIDE the service
            initializeCarPowerManager()
            
            // CRITICAL: Ensure IMEI is available
            ensureImeiAvailable()
            
            val filter = IntentFilter(ACTION_RESTART_SERVICE)
            registerReceiver(restartReceiver, filter)
            
            loadConfiguration()
            setupLocationUpdates()
            startPeriodicSync()
            
            // NEW: Force update igStatus after initialization
            Handler().postDelayed({
                forceUpdateIgStatusOnStart()
            }, 2000) // Wait 2 seconds for CarPowerManager to initialize
            
            Log.d(TAG, "✅ Service onCreate completed successfully (INDEPENDENT)")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in service onCreate", e)
        }
    }

    /**
     * CRITICAL: Initialize CarPowerManager INSIDE the BackgroundService
     */
    private fun initializeCarPowerManager() {
        try {
            Log.d(TAG, "=== INITIALIZING SERVICE-OWNED CAR POWER MANAGER ===")
            Log.d(TAG, "   - Service context: ${this.javaClass.simpleName}")
            Log.d(TAG, "   - Thread: ${Thread.currentThread().name}")
            
            // Initialize CarPowerManager with simple callback
            carPowerManager = CarPowerManager(this)
            carPowerManager?.setAccStateCallback(accStateListener)
            
            // Initialize CarPowerManager
            carPowerManager?.initialize()
            
            // Check initialization status after a delay
            Handler().postDelayed({
                try {
                    Log.d(TAG, "🔄 CHECKING CAR POWER MANAGER INITIALIZATION STATUS:")
                    
                    val isProperlyInitialized = carPowerManager?.isProperlyInitialized() ?: false
                    val detailedStatus = carPowerManager?.getDetailedStatus()
                    
                    Log.d(TAG, "   - isProperlyInitialized: $isProperlyInitialized")
                    detailedStatus?.forEach { (key, value) ->
                        Log.d(TAG, "   - $key: $value")
                    }
                    
                    if (isProperlyInitialized) {
                        val initialState = carPowerManager?.getCurrentAccState() ?: false
                        val initialIgStatus = if (initialState) 1 else 0
                        
                        Log.d(TAG, "🚗 GETTING INITIAL STATE:")
                        Log.d(TAG, "   - ACC ON: $initialState")
                        Log.d(TAG, "   - igStatus: $initialIgStatus")
                        
                        if (igStatus != initialIgStatus) {
                            val oldStatus = igStatus
                            igStatus = initialIgStatus
                            Log.d(TAG, "🔄 Initial igStatus updated: $oldStatus → $initialIgStatus")
                            storeAccStateForFlutter(initialIgStatus)
                        }
                        
                        isCarPowerInitialized = true
                        Log.d(TAG, "✅ Service-owned CarPowerManager properly initialized. Final igStatus: $igStatus")
                        
                    } else {
                        Log.w(TAG, "⚠️ CarPowerManager not properly initialized, using fallback")
                        isCarPowerInitialized = false
                        // Don't override igStatus - preserve the actual detected state
                        Log.w(TAG, "⚠️ CarPowerManager not initialized, but preserving current igStatus: $igStatus")
                    }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error checking CarPowerManager initialization status", e)
                    isCarPowerInitialized = false
                    // Don't override igStatus - preserve the actual detected state
                    Log.w(TAG, "⚠️ Error checking CarPowerManager, but preserving current igStatus: $igStatus")
                }
            }, 3000) // Wait 3 seconds for initialization (increased from 1 second)
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize service-owned CarPowerManager: ${e.message}")
            Log.e(TAG, "   - Exception type: ${e.javaClass.simpleName}")
            Log.e(TAG, "   - Exception message: ${e.message}")
            isCarPowerInitialized = false
            // Don't override igStatus - preserve the actual detected state
            Log.w(TAG, "⚠️ Failed to initialize CarPowerManager, but preserving current igStatus: $igStatus")
        }
    }

    // NEW METHOD: Trigger immediate sync with current igStatus
    private fun triggerImmediateSync(newIgStatus: Int) {
        try {
            Log.d(TAG, "🔄 TRIGGERING IMMEDIATE SYNC with igStatus: $newIgStatus")
            
            // Update any pending location records with new igStatus
            updatePendingRecordsWithIgStatus(newIgStatus)
            
            // Trigger sync service
            val syncIntent = Intent(this, BackgroundService::class.java).apply {
                action = "SYNC_IMMEDIATE"
                putExtra("ig_status", newIgStatus)
            }
            startService(syncIntent)
            
            Log.d(TAG, "✅ Immediate sync triggered")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error triggering immediate sync", e)
        }
    }

    // NEW METHOD: Update pending records with new igStatus
    private fun updatePendingRecordsWithIgStatus(newIgStatus: Int) {
        try {
            val db = dbHelper.writableDatabase
            
            val updateCount = db.update(
                "location_data",
                android.content.ContentValues().apply {
                    put("igStatus", newIgStatus)
                },
                "sync_status = 0",
                null
            )
            
            Log.d(TAG, "📊 Updated $updateCount pending records with igStatus: $newIgStatus")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error updating pending records", e)
        }
    }

    // NEW METHOD: Perform sync with specific igStatus
    private fun performSyncWithIgStatus(newIgStatus: Int) {
        try {
            Log.d(TAG, "🔄 PERFORMING SYNC WITH igStatus: $newIgStatus")
            
            // Update current igStatus
            igStatus = newIgStatus
            
            // Update any pending records
            updatePendingRecordsWithIgStatus(newIgStatus)
            
            // Trigger the existing sync logic
            // This will use the updated igStatus for all pending records
            Log.d(TAG, "✅ Sync with igStatus $newIgStatus completed")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error performing sync with igStatus", e)
        }
    }

    // NEW METHOD: Test current igStatus and power state (for debugging)
    private fun testCurrentIgStatus() {
        try {
            Log.d(TAG, "🧪 TESTING CURRENT IG STATUS:")
            Log.d(TAG, "   - Service igStatus: $igStatus")
            Log.d(TAG, "   - CarPowerManager initialized: $isCarPowerInitialized")
            
            // Test CarPowerManager state
            carPowerManager?.testCurrentPowerState()
            
            // Check SharedPreferences
            val flutterPrefs = getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
            val storedIgStatus = flutterPrefs.getInt("current_ig_status", -1)
            val timestamp = flutterPrefs.getLong("ig_status_timestamp", 0)
            
            Log.d(TAG, "   - Stored igStatus: $storedIgStatus")
            Log.d(TAG, "   - Timestamp: $timestamp")
            
            // Check tracking_prefs
            val trackingPrefs = getSharedPreferences("tracking_prefs", Context.MODE_PRIVATE)
            val trackingIgStatus = trackingPrefs.getInt("current_ig_status", -1)
            val trackingTimestamp = trackingPrefs.getLong("ig_status_timestamp", 0)
            
            Log.d(TAG, "   - Tracking igStatus: $trackingIgStatus")
            Log.d(TAG, "   - Tracking timestamp: $trackingTimestamp")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error testing igStatus", e)
        }
    }

    // NEW METHOD: Clear old igStatus values on startup
    private fun clearOldIgStatusValues() {
        try {
            Log.d(TAG, "🧹 CLEARING OLD IG STATUS VALUES")
            
            // Clear FlutterSharedPreferences
            val flutterPrefs = getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
            flutterPrefs.edit().remove("current_ig_status").remove("ig_status_timestamp").apply()
            
            // Clear tracking_prefs
            val trackingPrefs = getSharedPreferences("tracking_prefs", Context.MODE_PRIVATE)
            trackingPrefs.edit().remove("current_ig_status").remove("ig_status_timestamp").apply()
            
            Log.d(TAG, "✅ Cleared old igStatus values from SharedPreferences")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error clearing old igStatus values", e)
        }
    }

    private fun handleSleepStateChange(isSleeping: Boolean) {
        try {
            if (isSleeping) {
                Log.d(TAG, "🚗 Service handling sleep transition")
                stopLocationUpdates()
                scheduleSleepWakeUpChecks()
                storeSleepState(true)
                showSleepNotification()
            } else {
                Log.d(TAG, "🚗 Service handling wake transition")
                refreshWakeLocks()
                startLocationUpdates()
                cancelSleepWakeUpChecks()
                storeSleepState(false)
                showWakeUpNotification()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling sleep state change", e)
        }
    }

    private fun showSleepNotification() {
        try {
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("GPS Tracking - Sleep Mode")
                .setContentText("Service maintained during AVN sleep")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .build()

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(NOTIFICATION_ID, notification)
            
            Log.d(TAG, "Sleep notification shown")
        } catch (e: Exception) {
            Log.e(TAG, "Error showing sleep notification", e)
        }
    }

    private fun showWakeUpNotification() {
        try {
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("GPS Tracking - Wake Up")
                .setContentText("Service resumed after AVN wake-up")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .build()

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(NOTIFICATION_ID, notification)
            
            Log.d(TAG, "Wake-up notification shown")
        } catch (e: Exception) {
            Log.e(TAG, "Error showing wake-up notification", e)
        }
    }

    // NEW METHOD: Store ACC state for Flutter with detailed logging
    private fun storeAccStateForFlutter(igStatus: Int) {
        try {
            Log.d(TAG, "🔄 STORING ACC STATE FOR FLUTTER:")
            Log.d(TAG, "   - New igStatus: $igStatus")
            Log.d(TAG, "   - Service igStatus: ${this.igStatus}")
            Log.d(TAG, "   - Timestamp: ${System.currentTimeMillis()}")
            
            // Store in FlutterSharedPreferences (for Flutter sync service)
            val flutterPrefs = getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
            flutterPrefs.edit().apply {
                putInt("current_ig_status", igStatus)
                putLong("ig_status_timestamp", System.currentTimeMillis())
                apply()
            }
            
            // Store in tracking_prefs (for BackgroundService)
            val trackingPrefs = getSharedPreferences("tracking_prefs", Context.MODE_PRIVATE)
            trackingPrefs.edit().apply {
                putInt("current_ig_status", igStatus)
                putLong("ig_status_timestamp", System.currentTimeMillis())
                apply()
            }
            
            Log.d(TAG, "✅ Stored ACC state consistently for Flutter: $igStatus")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error storing ACC state: ${e.message}")
        }
    }

    private fun updateNotificationWithAccState(isAccOn: Boolean) {
        try {
            val status = if (isAccOn) "ACC ON" else "ACC OFF"
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("GPS Tracking Active")
                .setContentText("Status: $status")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .build()

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(NOTIFICATION_ID, notification)
            
            Log.d(TAG, "Updated notification with ACC state: $status")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating ACC state notification", e)
        }
    }

    // FIX: Add missing parameter to showBackgroundOnlyNotification
    private fun showBackgroundOnlyNotification(startedBy: String = "unknown") {
        try {
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("GPS Tracking - Background Mode")
                .setContentText("Service auto-started by system")
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                    "GPS Tracking - Background Mode\n" +
                    "Service auto-started by: $startedBy\n" +
                    "CarPowerManager active\n" +
                    "Tap app icon to open interface"
                ))
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setAutoCancel(false)
                .build()

            startForeground(NOTIFICATION_ID, notification)
            Log.d(TAG, "Background-only notification shown")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error showing background-only notification", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "=== BACKGROUND SERVICE STARTED (INDEPENDENT) ===")
        Log.d(TAG, "Start ID: $startId")
        Log.d(TAG, "Intent action: ${intent?.action}")
        
        val startedBy = intent?.getStringExtra("started_by") ?: "unknown"
        val isAutoStarted = intent?.getBooleanExtra("auto_started", false) ?: false
        val isBackgroundOnly = intent?.getBooleanExtra("background_only", false) ?: false
        val isCarPowerTriggered = intent?.getBooleanExtra("car_power_triggered", false) ?: false
        val isWakeUpFromSleep = intent?.getBooleanExtra("wake_up_from_sleep", false) ?: false
        val isSleepKeepAlive = intent?.getBooleanExtra("sleep_keep_alive", false) ?: false
        val isImeiAvailable = intent?.getBooleanExtra("imei_available", false) ?: false
        val imei = intent?.getStringExtra("imei")
        
        Log.d(TAG, "Started by: $startedBy")
        Log.d(TAG, "Auto started: $isAutoStarted")
        Log.d(TAG, "Background only: $isBackgroundOnly")
        Log.d(TAG, "Car Power Triggered: $isCarPowerTriggered")
        Log.d(TAG, "IMEI Available: $isImeiAvailable")
        Log.d(TAG, "IMEI: $imei")
        
        // Handle IMEI if provided by MainActivity
        if (isImeiAvailable && imei != null && imei.isNotEmpty()) {
            Log.d(TAG, "=== HANDLING IMEI FROM MAIN ACTIVITY ===")
            handleImeiFromMainActivity(imei)
        }
        
        val prefs = getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
        val wasSleeping = prefs.getBoolean("flutter.is_sleeping", false)
        
        when {
            intent?.action == "SYNC_IMMEDIATE" -> {
                Log.d(TAG, "🔄 HANDLING IMMEDIATE SYNC REQUEST")
                val newIgStatus = intent.getIntExtra("ig_status", igStatus)
                Log.d(TAG, "Sync with igStatus: $newIgStatus")
                performSyncWithIgStatus(newIgStatus)
            }
            intent?.action == "CHECK_POWER_STATE" -> {
                Log.d(TAG, "🔍 HANDLING MANUAL POWER STATE CHECK")
                checkAndUpdatePowerState()
            }
            intent?.action == "GET_CURRENT_IG_STATUS" -> {
                Log.d(TAG, "📊 HANDLING GET CURRENT IG STATUS")
                // Update SharedPreferences with current igStatus for Flutter to read
                storeAccStateForFlutter(igStatus)
                Log.d(TAG, "✅ Current igStatus stored for Flutter: $igStatus")
            }
            intent?.action == "TEST_ACC_STATE_DETECTION" -> {
                Log.d(TAG, "🧪 HANDLING ACC STATE DETECTION TEST")
                testAccStateDetection()
            }
            intent?.action == "TEST_SPECIFIC_POWER_STATE" -> {
                Log.d(TAG, "🧪 HANDLING SPECIFIC POWER STATE TEST")
                val testState = intent.getIntExtra("test_state", 0)
                testSpecificPowerState(testState)
            }
            intent?.action == "TEST_SERVER_SYNC" -> {
                Log.d(TAG, "🧪 HANDLING TEST SERVER SYNC")
                testServerSyncWithIgStatus()
            }
            intent?.action == "SEND_IGSTATUS_DIRECTLY" -> {
                Log.d(TAG, "🚀 HANDLING DIRECT IGSTATUS SEND")
                sendIgStatusDirectlyToServer()
            }
            isCarPowerTriggered -> {
                Log.d(TAG, "🚗 CAR POWER TRIGGERED START")
                handleCarPowerStart(intent)
            }
            isWakeUpFromSleep -> {
                Log.d(TAG, "🚗 Waking up from sleep state")
                handleWakeUpFromSleep(isBackgroundOnly)
            }
            isSleepKeepAlive -> {
                Log.d(TAG, "🚗 Sleep keep-alive")
                handleSleepKeepAlive()
            }
            wasSleeping -> {
                Log.d(TAG, "🚗 Service restart during sleep - resuming sleep management")
                handleServiceRestartDuringSleep()
            }
            isBackgroundOnly -> {
                Log.d(TAG, "🔄 Background-only start mode")
                handleBackgroundOnlyStart(startedBy)
            }
            else -> {
                Log.d(TAG, "🚗 Normal service operation")
                handleNormalServiceStart(startedBy, isAutoStarted)
            }
        }
        
        if (!isCarPowerInitialized) {
            Log.w(TAG, "CarPowerManager not initialized, retrying...")
            initializeCarPowerManager()
        }
        
        return START_STICKY
    }

    // FIX: Add missing startedBy parameter
    private fun handleBackgroundOnlyStart(startedBy: String) {
        try {
            Log.d(TAG, "=== HANDLING BACKGROUND-ONLY START ===")
            
            showBackgroundOnlyNotification(startedBy)
            startLocationUpdates()
            startPeriodicSync()
            refreshWakeLocks()
            
            Log.d(TAG, "✅ Background-only service started successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling background-only start", e)
        }
    }

    private fun handleServiceRestartDuringSleep() {
        try {
            Log.d(TAG, "=== HANDLING SERVICE RESTART DURING SLEEP ===")
            
            if (!isCarPowerInitialized) {
                initializeCarPowerManager()
            }
            
            showSleepNotification()
            acquireWakeLock()
            
            Log.d(TAG, "✅ Service restart during sleep handled")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling service restart during sleep", e)
        }
    }

    private fun handleNormalServiceStart(startedBy: String, isAutoStarted: Boolean) {
        try {
            Log.d(TAG, "=== HANDLING NORMAL SERVICE START ===")
            Log.d(TAG, "Started by: $startedBy")
            Log.d(TAG, "Auto started: $isAutoStarted")
            
            if (isAutoStarted) {
                showBackgroundOnlyNotification(startedBy)
            } else {
                startForeground(NOTIFICATION_ID, createNotification())
            }
            
            startLocationUpdates()
            startPeriodicSync()
            
            Log.d(TAG, "✅ Normal service start completed")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling normal service start", e)
        }
    }

    private fun handleCarPowerStart(intent: Intent?) {
        try {
            Log.d(TAG, "=== HANDLING CAR POWER START ===")
            
            // Handle car power extras
            val igStatus = intent?.getIntExtra("current_ig_status", 0) ?: 0
            val trigger = intent?.getStringExtra("started_by") ?: "unknown"
            
            Log.d(TAG, "=== HANDLING CAR POWER START ===")
            Log.d(TAG, "Trigger: $trigger")
            Log.d(TAG, "igStatus: $igStatus")
            
            // Update current igStatus
            this.igStatus = igStatus
            
            // Store ACC state for Flutter
            storeAccStateForFlutter(igStatus)
            
            // Continue with normal service operation
            Log.d(TAG, "🚗 Car power start - continuing normal operation")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling car power start", e)
        }
    }

    private fun handleWakeUpFromSleep(isBackgroundOnly: Boolean) {
        try {
            Log.d(TAG, "=== HANDLING WAKE-UP FROM SLEEP ===")
            Log.d(TAG, "Background only mode: $isBackgroundOnly")
            
            updateWakeUpTimestamp()
            refreshWakeLocks()
            startLocationUpdates()
            cancelSleepWakeUpChecks()
            storeSleepState(false)
            startPeriodicSync()
            showBackgroundWakeUpNotification()
            
            Log.d(TAG, "✅ Successfully resumed from sleep state (background mode)")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling wake-up from sleep", e)
        }
    }

    private fun showBackgroundWakeUpNotification() {
        try {
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("GPS Tracking Resumed")
                .setContentText("Service restarted after AVN wake-up")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setAutoCancel(false)
                .build()

            startForeground(NOTIFICATION_ID, notification)
            Log.d(TAG, "✅ Background wake-up notification shown")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error showing background wake-up notification", e)
        }
    }

    private fun handleSleepKeepAlive() {
        try {
            Log.d(TAG, "=== HANDLING SLEEP KEEP-ALIVE ===")
            updateNotificationWithSleepState()
            Log.d(TAG, "✅ Sleep keep-alive maintained")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling sleep keep-alive", e)
        }
    }

    private fun updateNotificationWithSleepState() {
        try {
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("GPS Tracking - Sleep Mode")
                .setContentText("Service maintained during AVN sleep")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .build()

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(NOTIFICATION_ID, notification)
            
            Log.d(TAG, "Updated notification for sleep state")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating sleep state notification", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "=== BACKGROUND SERVICE DESTROYED ===")
        
        try {
            val prefs = getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
            val isSleeping = prefs.getBoolean("flutter.is_sleeping", false)
            
            if (isSleeping) {
                Log.d(TAG, "🚗 Service destroyed during sleep - scheduling restart")
                scheduleServiceRestart()
                return
            }
            
            carPowerManager?.cleanup()
            fusedLocationClient.removeLocationUpdates(locationCallback)
            releaseWakeLock()
            unregisterReceiver(restartReceiver)
            stopForeground(true)
            
            Log.d(TAG, "✅ Service cleanup completed")
        } catch (e: Exception) {
            Log.e(TAG, "Error during service cleanup", e)
        }
    }

    private fun scheduleServiceRestart() {
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(this, BackgroundService::class.java)
            val pendingIntent = PendingIntent.getService(
                this,
                999,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            val triggerTime = SystemClock.elapsedRealtime() + 30000
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            }
            
            Log.d(TAG, "✅ Service restart scheduled")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to schedule service restart: ${e.message}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // Implementation methods - keep your existing logic
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
            
            Log.d(TAG, "Configuration loaded successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading configuration: ${e.message}")
        }
    }

    private fun setupLocationUpdates() {
        try {
            setupEnhancedGnssCallback()
            forceRegisterGnssCallback()
            
            val locationRequest = LocationRequest.create().apply {
                priority = LocationRequest.PRIORITY_HIGH_ACCURACY
                interval = gpsTimer * 1000L
                fastestInterval = 1000L
                maxWaitTime = gpsTimer * 2000L
                smallestDisplacement = 1f
            }

            locationCallback = object : LocationCallback() {
                override fun onLocationResult(locationResult: LocationResult) {
                    locationResult.lastLocation?.let { location ->
                        if (location.accuracy > 30) {
                            Log.d(TAG, "Skipping inaccurate location: accuracy = ${location.accuracy}m")
                            return
                        }
                        
                        val currentTime = System.currentTimeMillis()
                        val timeSinceLastUpdate = currentTime - lastLocationUpdateTime
                        val speed = getEnhancedAccurateSpeed(location)
                        val distance = lastLocation?.distanceTo(location) ?: 0f

                        if (shouldProcessLocationUpdate(location, speed, distance, timeSinceLastUpdate)) {
                            val reason = calculateEnhancedReason(location)
                            
                            Log.d(TAG, "=== PROCESSING LOCATION UPDATE ===")
                            Log.d(TAG, "Location: ${location.latitude}, ${location.longitude}")
                            Log.d(TAG, "Speed: ${String.format("%.1f", speed)} km/h")
                            Log.d(TAG, "Reason: $reason")
                            
                            updateNotification(
                                "GPS Tracking Active", 
                                "Speed: ${String.format("%.1f", speed)} km/h - Reason: $reason"
                            )
                            
                            val correctedLocation = Location(location).apply {
                                this.speed = speed / 3.6f
                            }
                            
                            saveLocationData(correctedLocation)
                            lastLocationUpdateTime = currentTime
                            lastLocation = location
                        }
                    }
                }
            }

            startLocationUpdates()
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up location updates", e)
        }
    }

    private fun setupEnhancedGnssCallback() {
        gnssStatusCallback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                totalSatellites = status.satelliteCount
                connectedSatellites = 0
                
                for (i in 0 until status.satelliteCount) {
                    if (status.usedInFix(i)) {
                        connectedSatellites++
                    }
                }
                
                Log.d(TAG, "Satellites: $connectedSatellites/$totalSatellites")
            }
        }
    }

    private fun forceRegisterGnssCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            
            try {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    locationManager.registerGnssStatusCallback(gnssStatusCallback)
                    Log.d(TAG, "GNSS callback registered")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error registering GNSS callback: ${e.message}")
            }
        }
    }

    private fun startLocationUpdates() {
        try {
            if (!::fusedLocationClient.isInitialized || !::locationCallback.isInitialized) {
                Log.w(TAG, "Location components not ready")
                return
            }

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
                Log.d(TAG, "Location updates started")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting location updates", e)
        }
    }

    private fun stopLocationUpdates() {
        try {
            if (::fusedLocationClient.isInitialized && ::locationCallback.isInitialized) {
                fusedLocationClient.removeLocationUpdates(locationCallback)
                Log.d(TAG, "Location updates stopped")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping location updates", e)
        }
    }

    private fun startPeriodicSync() {
        try {
            syncExecutor.scheduleAtFixedRate({
                try {
                    if (!isSyncing) {
                        syncData()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in periodic sync", e)
                }
            }, 0, uploadTimer.toLong(), TimeUnit.SECONDS)
            
            // Add periodic igStatus test (every 30 seconds)
            syncExecutor.scheduleAtFixedRate({
                try {
                    testCurrentIgStatus()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in periodic igStatus test", e)
                }
            }, 30, 30, TimeUnit.SECONDS)
            
            // NEW: Add periodic power state checker (every 15 seconds)
            syncExecutor.scheduleAtFixedRate({
                try {
                    checkAndUpdatePowerState()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in periodic power state check", e)
                }
            }, 15, 15, TimeUnit.SECONDS)
            
            Log.d(TAG, "Periodic sync started")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting periodic sync", e)
        }
    }

    // UPDATED METHOD: Check and update power state periodically
    private fun checkAndUpdatePowerState() {
        try {
            Log.d(TAG, "🔍 PERIODIC POWER STATE CHECK")
            
            // Test current power state
            carPowerManager?.testCurrentPowerState()
            
            // NEW: Use force update method for more reliable igStatus detection
            carPowerManager?.forceUpdateIgStatus()
            
            // Get current power state from CarPowerManager
            val currentPowerState = carPowerManager?.getCurrentAccState() ?: false
            val expectedIgStatus = if (currentPowerState) 1 else 0
            
            Log.d(TAG, "   - Current ACC state: $currentPowerState")
            Log.d(TAG, "   - Expected igStatus: $expectedIgStatus")
            Log.d(TAG, "   - Service igStatus: $igStatus")
            Log.d(TAG, "   - Timestamp: ${System.currentTimeMillis()}")
            
            // Check if igStatus needs to be updated
            if (igStatus != expectedIgStatus) {
                val oldStatus = igStatus
                igStatus = expectedIgStatus
                
                Log.d(TAG, "🔄 PERIODIC CHECK: igStatus updated: $oldStatus → $expectedIgStatus")
                
                // Save location data with new igStatus and sync to server
                if (lastLocation != null) {
                    saveLocationData(lastLocation!!)
                    
                    // Trigger immediate sync
                    syncExecutor.execute {
                        try {
                            Log.d(TAG, "🔄 Syncing location data with updated igStatus: $expectedIgStatus")
                            performSyncToServer()
                            Log.d(TAG, "✅ Location data synced with updated igStatus: $expectedIgStatus")
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Error syncing location data", e)
                        }
                    }
                } else {
                    Log.w(TAG, "⚠️ No location available, igStatus updated but not synced yet")
                }
                
                // Update notification
                updateNotificationWithAccState(currentPowerState)
                
                Log.d(TAG, "✅ Periodic power state check completed - igStatus updated and location synced")
            } else {
                Log.d(TAG, "ℹ️ Periodic check: igStatus unchanged ($igStatus)")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in periodic power state check", e)
        }
    }

    private fun syncData() {
        // Your existing sync implementation
        Log.d(TAG, "Syncing data...")
    }

    private fun getEnhancedAccurateSpeed(location: Location): Float {
        // Simplified speed calculation
        return if (location.hasSpeed() && location.speed >= 0) {
            location.speed * 3.6f // Convert m/s to km/h
        } else {
            0f
        }
    }

    private fun calculateEnhancedReason(location: Location): String {
        val speed = getEnhancedAccurateSpeed(location)
        
        // Check for distance-based reason if we have a previous location
        if (lastLocation != null) {
            val distance = lastLocation!!.distanceTo(location)
            if (distance >= distanceThreshold) {
                return "Distance"
            }
        }
        
        // Check for turn detection if we have a previous location
        if (lastLocation != null && speed >= 5f) {
            val bearingChange = kotlin.math.abs(location.bearing - lastLocation!!.bearing)
            val normalizedBearingChange = if (bearingChange > 180f) 360f - bearingChange else bearingChange
            
            if (normalizedBearingChange >= angleThreshold) {
                return "Turn"
            }
        }
        
        return when {
            speed > overSpeedingThreshold -> "Over Speeding"
            speed < 3f -> "Idle"
            speed >= 8f -> "Move"
            else -> "Idle"
        }
    }

    private fun shouldProcessLocationUpdate(location: Location, speed: Float, distance: Float, timeSinceLastUpdate: Long): Boolean {
        return when {
            lastLocation == null -> true
            location.accuracy > 50 -> false
            timeSinceLastUpdate >= gpsTimer * 1000L -> true
            distance >= distanceThreshold -> true
            speed >= overSpeedingThreshold -> true
            speed >= 8f && distance > 15f -> true
            else -> false
        }
    }

    private fun saveLocationData(location: Location) {
        try {
            if (!isIgStatusReady) {
                Log.w(TAG, "⚠️ Discarding location point because igStatus is not ready yet.")
                return
            }

            val currentTime = System.currentTimeMillis()
            val imei = getImei()
            val reason = calculateEnhancedReason(location)
            
            var fixedSpeed = location.speed * 3.6f
            if (fixedSpeed < 0) fixedSpeed = 0f
            
            if (imei.isEmpty() || imei == "unknown") {
                Log.e(TAG, "❌ Cannot save location without valid IMEI!")
                return
            }
            
            // Get current igStatus directly from service (no SharedPreferences dependency)
            val currentIgStatus = this.igStatus
            
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
                put("gmtSettings", "GMT+${java.time.ZoneId.systemDefault().rules.getOffset(java.time.Instant.now()).totalSeconds / 3600}:00")
                put("igStatus", currentIgStatus) // Use service igStatus directly
                put("localPrimaryId", currentTime % 100000)
                put("name", Build.MODEL)
                put("phoneNo", "unknown")
                put("provider", "fused")
                put("reason", reason)
                put("versionNo", "v ${getAppVersion()}")
                put("sync_status", 0)
                put("createAt", java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss.SSS")))
            }

            val db = dbHelper.writableDatabase
            val id = db.insert("location_data", null, values)
            
            // Log the igStatus being saved (direct from service)
            Log.d(TAG, "💾 SAVED LOCATION DATA - ID: $id")
            Log.d(TAG, "   - igStatus saved (direct): $currentIgStatus")
            Log.d(TAG, "   - ACC state: ${if (currentIgStatus == 1) "ON" else "OFF"}")
            Log.d(TAG, "   - Timestamp: ${System.currentTimeMillis()}")
            Log.d(TAG, "   - IMEI: $imei")
            Log.d(TAG, "   - Source: BackgroundService igStatus (no SharedPreferences)")
            
            Log.d(TAG, "Saved location data with ID: $id")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving location data: ${e.message}")
        }
    }

    private fun getImei(): String {
        try {
            val imeiManager = ImeiManager.getInstance(this)
            val deviceId = imeiManager.getDeviceIdentifier()
            
            if (deviceId != "unknown" && deviceId.isNotEmpty()) {
                return deviceId
            }
            
            val prefs = getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
            val storedImei = prefs.getString("flutter.imei", null)
            
            if (!storedImei.isNullOrEmpty() && storedImei != "unknown") {
                return storedImei
            }
            
            return "unknown"
            
        } catch (e: Exception) {
            Log.e(TAG, "Error getting IMEI: ${e.message}")
            return "unknown"
        }
    }

    private fun ensureImeiAvailable() {
        try {
            val imeiManager = ImeiManager.getInstance(this)
            val deviceId = imeiManager.getDeviceIdentifier()
            
            if (deviceId != "unknown" && deviceId.isNotEmpty()) {
                Log.d(TAG, "✅ IMEI secured for service: $deviceId")
            } else {
                Log.e(TAG, "❌ IMEI not available")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error ensuring IMEI: ${e.message}")
        }
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "TrackingWorld::LocationServiceWakeLock"
            )
            wakeLock?.acquire(10*60*1000L)
            
            Log.d(TAG, "Wake locks acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring wake locks", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { 
                if (it.isHeld) {
                    it.release()
                }
            }
            Log.d(TAG, "Wake locks released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing wake locks", e)
        }
    }

    private fun refreshWakeLocks() {
        try {
            releaseWakeLock()
            acquireWakeLock()
            Log.d(TAG, "Wake locks refreshed")
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing wake locks", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Location Tracking Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background location tracking service"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): android.app.Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Location Tracking")
            .setContentText("Service running")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }

    private fun updateNotification(title: String, content: String) {
        try {
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .build()

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating notification", e)
        }
    }

    private fun scheduleSleepWakeUpChecks() {
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(this, SleepWakeUpReceiver::class.java).apply {
                action = "com.trackingWorld.tracking.SLEEP_WAKE_UP_CHECK"
            }
            
            val pendingIntent = PendingIntent.getBroadcast(
                this, 0, intent, 
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            alarmManager.setRepeating(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + 30000,
                30000,
                pendingIntent
            )
            
            Log.d(TAG, "Sleep wake-up checks scheduled")
        } catch (e: Exception) {
            Log.e(TAG, "Error scheduling sleep checks", e)
        }
    }

    private fun cancelSleepWakeUpChecks() {
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(this, SleepWakeUpReceiver::class.java).apply {
                action = "com.trackingWorld.tracking.SLEEP_WAKE_UP_CHECK"
            }
            
            val pendingIntent = PendingIntent.getBroadcast(
                this, 0, intent, 
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            alarmManager.cancel(pendingIntent)
            Log.d(TAG, "Sleep wake-up checks cancelled")
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling sleep checks", e)
        }
    }

    private fun storeSleepState(isSleeping: Boolean) {
        try {
            val prefs = getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
            prefs.edit().apply {
                putBoolean("flutter.is_sleeping", isSleeping)
                putLong("flutter.sleep_state_timestamp", System.currentTimeMillis())
                apply()
            }
            Log.d(TAG, "Stored sleep state: $isSleeping")
        } catch (e: Exception) {
            Log.e(TAG, "Error storing sleep state", e)
        }
    }

    private fun updateWakeUpTimestamp() {
        try {
            val prefs = getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
            prefs.edit().putLong("flutter.last_wake_up_time", System.currentTimeMillis()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error updating wake-up timestamp", e)
        }
    }

    // NEW METHOD: Handle immediate sync requests
    private fun handleImmediateSync(igStatus: Int, reason: String) {
        try {
            Log.d(TAG, "=== HANDLING IMMEDIATE SYNC ===")
            Log.d(TAG, "igStatus: $igStatus, Reason: $reason")
            
            // Update current igStatus
            this.igStatus = igStatus
            
            // Store consistently
            storeAccStateForFlutter(igStatus)
            
            // Update notification
            updateNotificationWithAccState(igStatus == 1)
            
            // Force sync to server immediately
            forceSyncToServer(igStatus, reason)
            
            Log.d(TAG, "✅ Immediate sync completed for igStatus: $igStatus")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling immediate sync", e)
        }
    }

    // NEW METHOD: Force sync to server with current igStatus
    private fun forceSyncToServer(igStatus: Int, reason: String) {
        try {
            Log.d(TAG, "🔄 Force syncing to server - igStatus: $igStatus, Reason: $reason")
            
            // Update all unsynced records with current igStatus
            updateUnsyncedRecordsIgStatus(igStatus)
            
            // Trigger immediate sync
            syncExecutor.execute {
                try {
                    Log.d(TAG, "Starting forced sync to server...")
                    performSyncToServer()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in forced sync", e)
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error forcing sync to server", e)
        }
    }

    // NEW METHOD: Update unsynced records with current igStatus
    private fun updateUnsyncedRecordsIgStatus(igStatus: Int) {
        try {
            val db = dbHelper.writableDatabase
            val updatedRows = db.update(
                "location_data",
                ContentValues().apply { put("igStatus", igStatus) },
                "sync_status = ?",
                arrayOf("0")
            )
            Log.d(TAG, "Updated $updatedRows unsynced records with igStatus: $igStatus")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating unsynced records igStatus", e)
        }
    }

    // NEW METHOD: Perform actual sync to server
    private fun performSyncToServer() {
        if (isSyncing) {
            Log.d(TAG, "Sync already in progress, skipping...")
            return
        }

        isSyncing = true
        Log.d(TAG, "Starting sync to server...")

        try {
            val unsyncedData = dbHelper.getUnsyncedData(limit = 50)
            if (unsyncedData.isEmpty()) {
                Log.d(TAG, "No unsynced data to upload")
                return
            }

            Log.d(TAG, "Found ${unsyncedData.size} records to sync")
            val syncedIds = mutableListOf<Long>()

            for (data in unsyncedData) {
                try {
                    // Remove internal fields before sending
                    val dataToSend = data.toMutableMap()
                    dataToSend.remove("id")
                    dataToSend.remove("sync_status")
                    
                    // Convert createdAt to createAt (camelCase) and format properly
                    if (dataToSend.containsKey("created_at")) {
                        val createdAtMillis = when (val createdAt = dataToSend["created_at"]) {
                            is Long -> createdAt
                            is Int -> createdAt.toLong()
                            is String -> createdAt.toLongOrNull() ?: System.currentTimeMillis()
                            else -> System.currentTimeMillis()
                        }
                        val createdAtDateTime = java.time.Instant.ofEpochMilli(createdAtMillis)
                        val formattedCreatedAt = createdAtDateTime.atZone(java.time.ZoneId.systemDefault())
                            .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss.SSS"))
                        
                        // Remove the snake_case key and add camelCase key
                        dataToSend.remove("created_at")
                        dataToSend["createAt"] = formattedCreatedAt
                    }

                    // Log the igStatus being sent to server
                    val igStatusBeingSent = dataToSend["igStatus"] as? Int ?: 0
                    val recordId = when (val id = data["id"]) {
                        is Long -> id.toString()
                        is Int -> id.toString()
                        is String -> id
                        else -> "unknown"
                    }
                    Log.d(TAG, "🔄 SYNC TO SERVER - Record ID: $recordId")
                    Log.d(TAG, "   - igStatus being sent: $igStatusBeingSent")
                    Log.d(TAG, "   - Current service igStatus: $igStatus")
                    Log.d(TAG, "   - ACC state: ${if (igStatusBeingSent == 1) "ON" else "OFF"}")
                    Log.d(TAG, "   - Timestamp: ${System.currentTimeMillis()}")

                    Log.d(TAG, "Sending data: ${dataToSend}")

                    val request = okhttp3.Request.Builder()
                        .url(serverUrl)
                        .post(Gson().toJson(dataToSend).toRequestBody("application/json".toMediaTypeOrNull()))
                        .addHeader("Content-Type", "application/json")
                        .addHeader("User-Agent", "TrackingWorld-Mobile-App")
                        .build()

                    val response = client.newCall(request).execute()

                    if (response.isSuccessful) {
                        val recordId = when (val id = data["id"]) {
                            is Long -> id
                            is Int -> id.toLong()
                            is String -> id.toLongOrNull() ?: 0L
                            else -> 0L
                        }
                        syncedIds.add(recordId)
                        Log.d(TAG, "✅ Successfully synced record ID: $recordId with igStatus: $igStatusBeingSent")
                    } else {
                        Log.e(TAG, "❌ Server error: ${response.code} - ${response.body?.string()}")
                    }

                    response.close()

                } catch (e: Exception) {
                    val recordId = when (val id = data["id"]) {
                        is Long -> id.toString()
                        is Int -> id.toString()
                        is String -> id
                        else -> "unknown"
                    }
                    Log.e(TAG, "Error syncing record $recordId: $e")
                }
            }

            // Mark successfully synced records
            if (syncedIds.isNotEmpty()) {
                dbHelper.markAsSynced(syncedIds)
                Log.d(TAG, "Marked ${syncedIds.size} records as synced")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error in sync to server", e)
        } finally {
            isSyncing = false
            Log.d(TAG, "Sync to server completed")
        }
    }

    // NEW METHOD: Get current igStatus
    fun getCurrentIgStatus(): Int {
        return igStatus
    }

    // NEW METHOD: Handle IMEI from MainActivity
    private fun handleImeiFromMainActivity(imei: String) {
        try {
            Log.d(TAG, "=== HANDLING IMEI FROM MAIN ACTIVITY ===")
            Log.d(TAG, "IMEI: $imei")
            
            // Store IMEI in SharedPreferences
            val prefs = getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
            prefs.edit().putString("flutter.imei", imei).apply()
            
            // Also store in tracking_prefs for consistency
            val trackingPrefs = getSharedPreferences("tracking_prefs", Context.MODE_PRIVATE)
            trackingPrefs.edit().putString("imei", imei).apply()
            
            Log.d(TAG, "✅ IMEI stored successfully: $imei")
            
            // Now that IMEI is available, ensure the service is fully operational
            ensureServiceOperational()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling IMEI from MainActivity", e)
        }
    }

    // NEW METHOD: Ensure service is fully operational
    private fun ensureServiceOperational() {
        try {
            Log.d(TAG, "=== ENSURING SERVICE OPERATIONAL ===")
            
            // Check if IMEI is available
            val imei = getImei()
            if (imei.isNotEmpty() && imei != "unknown") {
                Log.d(TAG, "✅ IMEI is available: $imei")
                
                // Start location updates if not already started
                if (!::fusedLocationClient.isInitialized) {
                    Log.d(TAG, "🔄 Initializing location client")
                    fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
                }
                
                // Start location updates
                startLocationUpdates()
                
                // Start periodic sync
                startPeriodicSync()
                
                // Show operational notification
                showOperationalNotification(imei)
                
                Log.d(TAG, "✅ Service is now fully operational with IMEI: $imei")
                
            } else {
                Log.w(TAG, "⚠️ IMEI not available yet, service will wait")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error ensuring service operational", e)
        }
    }

    // NEW METHOD: Show operational notification
    private fun showOperationalNotification(imei: String) {
        try {
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("GPS Tracking Active")
                .setContentText("IMEI: $imei - Service operational")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .build()

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(NOTIFICATION_ID, notification)
            
            Log.d(TAG, "✅ Operational notification shown with IMEI: $imei")
        } catch (e: Exception) {
            Log.e(TAG, "Error showing operational notification", e)
        }
    }

    private fun getAppVersion(): String {
        return try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            packageInfo.versionName ?: "unknown"
        } catch (e: Exception) {
            Log.e(TAG, "Error getting app version", e)
            "unknown"
        }
    }

    // UPDATED METHOD: Force update igStatus on service start
    private fun forceUpdateIgStatusOnStart() {
        try {
            Log.d(TAG, "🚀 FORCE UPDATE IG STATUS ON START")
            
            // NEW: Debug power states to understand AVN behavior
            carPowerManager?.debugPowerStates()
            
            // NEW: Use CarPowerManager's force update method
            carPowerManager?.forceUpdateIgStatus()
            
            // Get current power state from CarPowerManager
            val currentPowerState = carPowerManager?.getCurrentAccState() ?: false
            val expectedIgStatus = if (currentPowerState) 1 else 0
            
            Log.d(TAG, "   - Current ACC state: $currentPowerState")
            Log.d(TAG, "   - Expected igStatus: $expectedIgStatus")
            Log.d(TAG, "   - Current service igStatus: $igStatus")
            Log.d(TAG, "   - Timestamp: ${System.currentTimeMillis()}")
            
            // Always update igStatus on start to ensure consistency
            val oldStatus = igStatus
            igStatus = expectedIgStatus
            
            Log.d(TAG, "🔄 FORCE UPDATE: igStatus set to: $oldStatus → $expectedIgStatus")
            
            // Save location data with new igStatus and sync to server
            if (lastLocation != null) {
                saveLocationData(lastLocation!!)
                
                // Trigger immediate sync
                syncExecutor.execute {
                    try {
                        Log.d(TAG, "🔄 Syncing location data with force updated igStatus: $expectedIgStatus")
                        performSyncToServer()
                        Log.d(TAG, "✅ Location data synced with force updated igStatus: $expectedIgStatus")
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error syncing location data", e)
                    }
                }
            } else {
                Log.w(TAG, "⚠️ No location available, igStatus force updated but not synced yet")
            }
            
            // Update notification
            updateNotificationWithAccState(currentPowerState)
            
            Log.d(TAG, "✅ Force update igStatus on start completed - location synced")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in force update igStatus on start", e)
        }
    }

    // NEW METHOD: Test ACC state detection manually
    fun testAccStateDetection() {
        try {
            Log.d(TAG, "🧪 MANUAL ACC STATE DETECTION TEST")
            
            // Test CarPowerManager status
            carPowerManager?.let { manager ->
                Log.d(TAG, "   - CarPowerManager initialized: ${manager.isProperlyInitialized()}")
                Log.d(TAG, "   - Detailed status: ${manager.getDetailedStatus()}")
                
                // Debug power states
                manager.debugPowerStates()
                
                // Test current state
                val currentAccState = manager.getCurrentAccState()
                val currentIgStatus = manager.getCurrentIgStatus()
                
                Log.d(TAG, "   - Current ACC state: $currentAccState")
                Log.d(TAG, "   - Current igStatus: $currentIgStatus")
                
                // Test service state
                Log.d(TAG, "   - Service igStatus: $igStatus")
                Log.d(TAG, "   - States match: ${currentIgStatus == igStatus}")
                
            } ?: run {
                Log.w(TAG, "   - CarPowerManager is null")
            }
            
            // Test SharedPreferences
            val flutterPrefs = getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
            val storedIgStatus = flutterPrefs.getInt("current_ig_status", -1)
            val timestamp = flutterPrefs.getLong("ig_status_timestamp", 0)
            
            Log.d(TAG, "   - Stored igStatus: $storedIgStatus")
            Log.d(TAG, "   - Timestamp: $timestamp")
            
            // Test tracking_prefs
            val trackingPrefs = getSharedPreferences("tracking_prefs", Context.MODE_PRIVATE)
            val trackingIgStatus = trackingPrefs.getInt("current_ig_status", -1)
            val trackingTimestamp = trackingPrefs.getLong("ig_status_timestamp", 0)
            
            Log.d(TAG, "   - Tracking igStatus: $trackingIgStatus")
            Log.d(TAG, "   - Tracking timestamp: $trackingTimestamp")
            
            Log.d(TAG, "✅ ACC state detection test completed")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in ACC state detection test", e)
        }
    }

    // NEW METHOD: Test specific power state from service
    fun testSpecificPowerState(testState: Int) {
        try {
            Log.d(TAG, "🧪 TESTING SPECIFIC POWER STATE FROM SERVICE: $testState")
            
            carPowerManager?.testSpecificPowerState(testState)
            
            // Get all power states info for reference
            val allStatesInfo = carPowerManager?.getAllPowerStatesInfo()
            Log.d(TAG, "📋 All power states info: $allStatesInfo")
            
            Log.d(TAG, "✅ Specific power state test from service completed")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error testing specific power state from service", e)
        }
    }

    // NEW METHOD: Test server sync with current igStatus
    fun testServerSyncWithIgStatus() {
        try {
            Log.d(TAG, "🧪 TESTING SERVER SYNC WITH CURRENT IGSTATUS")
            Log.d(TAG, "   - Current service igStatus: $igStatus")
            Log.d(TAG, "   - ACC state: ${if (igStatus == 1) "ON" else "OFF"}")
            Log.d(TAG, "   - Timestamp: ${System.currentTimeMillis()}")
            
            // Update all unsynced records with current igStatus
            updateUnsyncedRecordsIgStatus(igStatus)
            
            // Trigger immediate sync
            syncExecutor.execute {
                try {
                    Log.d(TAG, "🔄 Starting test sync to server with igStatus: $igStatus")
                    performSyncToServer()
                    Log.d(TAG, "✅ Test sync completed")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error in test sync", e)
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error testing server sync", e)
        }
    }

    // NEW METHOD: Send igStatus directly to server (no SharedPreferences)
    fun sendIgStatusDirectlyToServer() {
        try {
            Log.d(TAG, "🚀 SENDING IGSTATUS DIRECTLY TO SERVER")
            Log.d(TAG, "   - Current service igStatus: $igStatus")
            Log.d(TAG, "   - ACC state: ${if (igStatus == 1) "ON" else "OFF"}")
            Log.d(TAG, "   - Timestamp: ${System.currentTimeMillis()}")
            Log.d(TAG, "   - Source: BackgroundService (no SharedPreferences)")
            
            // Instead of sending test data, save a location record with current igStatus and sync it
            if (lastLocation != null) {
                // Save current location with updated igStatus
                saveLocationData(lastLocation!!)
                
                // Trigger immediate sync of the saved data
                syncExecutor.execute {
                    try {
                        Log.d(TAG, "🔄 Syncing location data with igStatus: $igStatus")
                        performSyncToServer()
                        Log.d(TAG, "✅ Location data synced with igStatus: $igStatus")
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error syncing location data", e)
                    }
                }
            } else {
                Log.w(TAG, "⚠️ No location available, cannot send igStatus to server")
                Log.w(TAG, "   - Will send igStatus when location becomes available")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in sendIgStatusDirectlyToServer", e)
        }
    }

    // NEW METHOD: Request location update for valid coordinates
    private fun requestLocationUpdate() {
        try {
            Log.d(TAG, "📍 REQUESTING LOCATION UPDATE FOR VALID COORDINATES")
            
            if (::fusedLocationClient.isInitialized) {
                // Request a single location update
                val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000L)
                    .setMinUpdateIntervalMillis(5000L)
                    .setMaxUpdateDelayMillis(10000L)
                    .build()

                fusedLocationClient.requestLocationUpdates(
                    locationRequest,
                    object : LocationCallback() {
                        override fun onLocationResult(locationResult: LocationResult) {
                            locationResult.lastLocation?.let { location ->
                                Log.d(TAG, "✅ Got location update for valid coordinates")
                                Log.d(TAG, "   - Latitude: ${location.latitude}")
                                Log.d(TAG, "   - Longitude: ${location.longitude}")
                                Log.d(TAG, "   - Accuracy: ${location.accuracy}")
                                
                                // Now try sending igStatus again with valid coordinates
                                sendIgStatusDirectlyToServer()
                                
                                // Remove this callback after getting location
                                fusedLocationClient.removeLocationUpdates(this)
                            }
                        }
                    },
                    Looper.getMainLooper()
                )
                
                Log.d(TAG, "📍 Location update requested")
            } else {
                Log.w(TAG, "⚠️ Location client not initialized, cannot request location update")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error requesting location update", e)
        }
    }
}