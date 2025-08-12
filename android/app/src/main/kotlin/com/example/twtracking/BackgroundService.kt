package com.example.twtracking

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

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
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    private val serverUrl = "http://twca.trackingworld.com.pk:3000/api/location"
    private lateinit var dbHelper: LocationDatabaseHelper
    private var syncExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var isSyncing = false
    private var lastLocation: Location? = null
    private lateinit var gnssStatusCallback: GnssStatus.Callback
    private var lastLocationUpdateTime: Long = 0

    private var gpsTimer: Int = 5
    private var uploadTimer: Int = 10
    private var angleThreshold: Float = 45f
    private var overSpeedingThreshold: Float = 70f  // Updated to 70 km/h
    private var distanceThreshold: Float = 500f     // Updated to 500m
    private var movingTimer: Int = 60
    private var stopTimer: Int = 130
    
    // NEW: Configuration reload timer
    private var configReloadTimer: java.util.Timer? = null

    private val restartReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_RESTART_SERVICE) {
                Log.d(TAG, "Received restart broadcast")
                startLocationUpdates()
            }
        }
    }

    private var igStatus = 0 // Initialize to 0 (ACC off)
    private var lastIgStatus = 0 // Track previous igStatus for ignition change detection
    
    // NEW: Enhanced ignition state tracking for server communication
    private var previousIgnitionState = 0 // Track the previous ignition state
    private var ignitionOnDetected = false // Flag to track if ignition on was detected
    private var ignitionOffDetected = false // Flag to track if ignition off was detected
    private var lastIgnitionChangeTime = 0L // Timestamp of last ignition change
    private var shouldSendData = true // Flag to control data transmission based on ignition state
    private var pendingIgnitionOnReason = false // Flag to ensure first record after ignition on has "Ignition On" reason
    private var pendingIgnitionOffReason = false // Flag to ensure ignition off is properly recorded
    private var ignitionStateChangeBuffer = mutableListOf<Pair<String, Long>>() // Buffer for ignition state changes
    private var lastProcessedIgnitionChange = "" // Track last processed ignition change to prevent duplicates

    // NEW: Reason timing tracking variables
    private var lastIdleSyncTime: Long = 0
    private var lastMoveSyncTime: Long = 0
    private var lastOverSpeedingSyncTime: Long = 0
    private var lastSyncedReason: String? = null
    private val idleConsecutiveTimeout: Long = 120000L // 120 seconds in milliseconds
    private val moveConsecutiveTimeout: Long = 30000L // 30 seconds in milliseconds
    private val overSpeedingConsecutiveTimeout: Long = 45000L // 45 seconds in milliseconds

    private val mainHandler = Handler(Looper.getMainLooper())

    private val accStateListener: (Boolean) -> Unit = { isAccOn ->

//        mainHandler.post {
            try {
                val newIgStatus = if (isAccOn) 1 else 0
                val oldStatus = if (isAccOn) 0 else 1
                    Log.d(TAG, "✅ igStatus is now ready. Initial igStatus: $newIgStatus")

//                if (!isIgStatusReady) {
//        Log.e("accStateListener: ","${isAccOn}")
//                    isIgStatusReady = true
//
//                    // Log the start of continuous monitoring
//                    dbHelper.insertIgnitionLog(
//                        "Continuously monitoring the igStatus",
//                        "Monitoring service active, checking every 5 seconds",
//                        "monitoring"
//                    )
//                }
                
//                Log.d(TAG, "🚗 ACC Callback Received. isAccOn: $isAccOn, newIgStatus: $newIgStatus, oldStatus: $oldStatus")

                // NEW: Check for engine start scenario

//                if (igStatus==1) {
//                    Log.d(TAG, "🚗 ENGINE START IN PROGRESS - Maintaining current igStatus: $igStatus")
//                    dbHelper.insertIgnitionLog(
//                        "Engine start in progress - ignoring temporary power fluctuation",
//                        "Maintaining igStatus: $igStatus during engine start",
//                        "engine_start_progress"
//                    )
//                }

//                igStatus = newIgStatus // Always update to the latest from the source of truth

//                if (newIgStatus != oldStatus) {
                    Log.e(TAG, "🔄 igStatus updated: $oldStatus -> $newIgStatus")
                    Log.e(TAG, "🚗 IGNITION STATE CHANGE DETECTED:")
                    Log.e(TAG, "   - Old Status: $oldStatus (${if (oldStatus == 1) "ACC_ON" else "ACC_OFF"})")
                    Log.e(TAG, "   - New Status: $newIgStatus (${if (newIgStatus == 1) "ACC_ON" else "ACC_OFF"})")
                    Log.e(TAG, "   - Timestamp: ${System.currentTimeMillis()}")
                    Log.e(TAG, "   - Source: CarPowerManager callback")
                    
                    // NEW: Enhanced ignition state change handling
                    handleIgnitionStateChange(oldStatus, newIgStatus)
                    
                    updateNotificationWithAccState(isAccOn)
                    
                    // Log the ignition status change
                    val oldStatusName = if (oldStatus == 1) "ACC_ON" else "ACC_OFF"
                    val newStatusName = if (newIgStatus == 1) "ACC_ON" else "ACC_OFF"
                    dbHelper.insertIgnitionLog(
                        "Ignition status changed: $oldStatusName → $newStatusName",
                        "igStatus: $oldStatus → $newIgStatus",
                        if (newIgStatus == 1) "acc_on" else "acc_off"
                    )

                    // NEW: Let handleIgnitionStateChange handle all ignition-related location saving
                    // No duplicate location saving here - handleIgnitionStateChange will handle it
                    Log.d(TAG, "🚗 Ignition state change handled by handleIgnitionStateChange method")
                    
                    // Update lastIgStatus for future change detection
                    lastIgStatus = oldStatus
//                } else {
//                    Log.d(TAG, "ℹ️ igStatus value confirmed: $newIgStatus")
//                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error in accStateListener", e)
            }
//        }
    }

    inner class LocationDatabaseHelper(context: Context) : SQLiteOpenHelper(context, "location_tracking.db", null, 5) {
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
            
            // Create ignition_logs table
            db.execSQL("""
                CREATE TABLE ignition_logs(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    message TEXT NOT NULL,
                    details TEXT,
                    log_type TEXT DEFAULT 'info',
                    timestamp TEXT
                )
            """)
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
            
            if (oldVersion < 5) {
                try {
                    // Create ignition_logs table if it doesn't exist
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS ignition_logs(
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            message TEXT NOT NULL,
                            details TEXT,
                            log_type TEXT DEFAULT 'info',
                            timestamp TEXT
                        )
                    """)
                    Log.d(TAG, "Created ignition_logs table for version 5")
                } catch (e: Exception) {
                    Log.e(TAG, "Error creating ignition_logs table: ${e.message}")
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
                // First, get total count of records
                val totalCursor = db.rawQuery("SELECT COUNT(*) FROM location_data", null)
                totalCursor.moveToFirst()
                val totalRecords = totalCursor.getInt(0)
                totalCursor.close()
                
                // Get count of synced records
                val syncedCursor = db.rawQuery("SELECT COUNT(*) FROM location_data WHERE sync_status = 1", null)
                syncedCursor.moveToFirst()
                val syncedRecords = syncedCursor.getInt(0)
                syncedCursor.close()
                
                // Get count of unsynced records
                val unsyncedCursor = db.rawQuery("SELECT COUNT(*) FROM location_data WHERE sync_status = 0", null)
                unsyncedCursor.moveToFirst()
                val unsyncedRecords = unsyncedCursor.getInt(0)
                unsyncedCursor.close()
                
                Log.e(TAG, "ERROR: Database stats - Total: $totalRecords, Synced: $syncedRecords, Unsynced: $unsyncedRecords")
                
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
                
                Log.e(TAG, "ERROR: Retrieved ${data.size} unsynced records")
            } catch (e: Exception) {
                Log.e(TAG, "ERROR: Exception getting unsynced data: ${e.message}")
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
                
                Log.e(TAG, "ERROR: Marked $updatedRows records as synced. IDs: $ids")
                Log.e(TAG, "ERROR: These records were successfully sent to server and marked as synced")
            } catch (e: Exception) {
                Log.e(TAG, "ERROR: Exception marking records as synced: ${e.message}")
            }
        }

        // Ignition Logs Methods
        fun insertIgnitionLog(message: String, details: String = "", logType: String = "info") {
            val db = writableDatabase
            try {
                val timestamp = java.time.Instant.now().toString()
                val values = ContentValues().apply {
                    put("message", message)
                    put("details", details)
                    put("log_type", logType)
                    put("timestamp", timestamp)
                }
                
                val id = db.insert("ignition_logs", null, values)
                Log.e(TAG, "Inserted ignition log: $message (ID: $id)")
                
                // Notify Flutter to upload the log to endpoint
                try {
                    val intent = Intent("UPLOAD_IGNITION_LOG")
                    intent.putExtra("message", message)
                    intent.putExtra("details", details)
                    intent.putExtra("logType", logType)
                    intent.putExtra("timestamp", timestamp)
                    sendBroadcast(intent)
                    Log.e(TAG, "Broadcast sent for ignition log upload: $message")
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending ignition log upload broadcast: ${e.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error inserting ignition log: ${e.message}")
            }
        }

        fun getIgnitionLogs(limit: Int = 100): List<Map<String, Any>> {
            val db = readableDatabase
            val data = mutableListOf<Map<String, Any>>()
            
            try {
                val cursor = db.query(
                    "ignition_logs",
                    null,
                    null,
                    null,
                    null,
                    null,
                    "timestamp DESC",
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
                
                Log.d(TAG, "Retrieved ${data.size} ignition logs")
            } catch (e: Exception) {
                Log.e(TAG, "Error getting ignition logs: ${e.message}")
            }
            
            return data
        }

        fun clearIgnitionLogs() {
            val db = writableDatabase
            try {
                val deletedRows = db.delete("ignition_logs", null, null)
                Log.d(TAG, "Cleared $deletedRows ignition logs")
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing ignition logs: ${e.message}")
            }
        }

        // NEW METHOD: Reset all records to unsynced status
        fun resetAllRecordsToUnsynced() {
            val db = writableDatabase
            try {
                val updatedRows = db.update(
                    "location_data",
                    ContentValues().apply { put("sync_status", 0) },
                    "sync_status = ?",
                    arrayOf("1")
                )
                Log.e(TAG, "ERROR: Reset $updatedRows records from synced to unsynced status")
            } catch (e: Exception) {
                Log.e(TAG, "ERROR: Exception resetting records to unsynced: ${e.message}")
            }
        }

        // NEW METHOD: Verify sync status integrity
        fun verifySyncStatusIntegrity() {
            val db = readableDatabase
            try {
                // Get total records
                val totalCursor = db.rawQuery("SELECT COUNT(*) FROM location_data", null)
                totalCursor.moveToFirst()
                val totalRecords = totalCursor.getInt(0)
                totalCursor.close()
                
                // Get synced records
                val syncedCursor = db.rawQuery("SELECT COUNT(*) FROM location_data WHERE sync_status = 1", null)
                syncedCursor.moveToFirst()
                val syncedRecords = syncedCursor.getInt(0)
                syncedCursor.close()
                
                // Get unsynced records
                val unsyncedCursor = db.rawQuery("SELECT COUNT(*) FROM location_data WHERE sync_status = 0", null)
                unsyncedCursor.moveToFirst()
                val unsyncedRecords = unsyncedCursor.getInt(0)
                unsyncedCursor.close()
                
                Log.e(TAG, "ERROR: Sync Status Integrity Check:")
                Log.e(TAG, "ERROR:   - Total records: $totalRecords")
                Log.e(TAG, "ERROR:   - Synced records: $syncedRecords")
                Log.e(TAG, "ERROR:   - Unsynced records: $unsyncedRecords")
                
                // Check for potential issues
                if (syncedRecords > totalRecords * 0.8 && unsyncedRecords == 0) {
                    Log.e(TAG, "ERROR: ⚠️ WARNING: High percentage of records marked as synced with no unsynced records")
                    Log.e(TAG, "ERROR:   This might indicate incorrect sync status marking")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "ERROR: Exception in sync status integrity check: ${e.message}")
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "=== BACKGROUND SERVICE ONCREATE (INDEPENDENT) ===")
        Log.d(TAG, "Process ID: ${android.os.Process.myPid()}")
        Log.d(TAG, "Thread: ${Thread.currentThread().name}")
        
        try {
            // Clear old igStatus values to start fresh
            clearOldIgStatusValues()
            
            dbHelper = LocationDatabaseHelper(this)
            
            // NEW: Reset all records to unsynced status to fix sync issue
            dbHelper.resetAllRecordsToUnsynced()
            
            // NEW: Verify sync status integrity after reset
            dbHelper.verifySyncStatusIntegrity()
            
            acquireWakeLock()
            createNotificationChannel()
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
            
            // CRITICAL: Initialize CarPowerManager INSIDE the service
            initializeCarPowerManager()
            
            // CRITICAL: Ensure IMEI is available
            ensureImeiAvailable()
            
            // NEW: Try to update unknown IMEI records on service start
            updateUnknownImeiRecords()
            
            val filter = IntentFilter(ACTION_RESTART_SERVICE)
            registerReceiver(restartReceiver, filter)
            
            loadConfiguration()
            setupLocationUpdates()
            startPeriodicSync()
            
            // NEW: Check configuration integrity after startup
            checkAndFixConfiguration()
            
            // NEW: Start configuration reload timer
            startConfigurationReloadTimer()
            
            // NEW: Force update igStatus after initialization
            Handler().postDelayed({
//                forceUpdateIgStatusOnStart()
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
            Log.e(TAG, "=== INITIALIZING SERVICE-OWNED CAR POWER MANAGER === ${carPowerManager==null}")
            Log.e(TAG, "   - Service context: ${this.javaClass.simpleName}")
            Log.e(TAG, "   - Thread: ${Thread.currentThread().name}")

            // Initialize CarPowerManager with simple callback
            if(carPowerManager!=null) return

            carPowerManager = CarPowerManager(this)
            carPowerManager?.setAccStateCallback(accStateListener)

            // Set up ignition log callback
            carPowerManager?.setIgnitionLogCallback { message, details, logType ->
                try {
//                    dbHelper.insertIgnitionLog(message, details, logType)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in ignition log callback: ${e.message}")
                }
            }

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
            Log.e("showBackgroundOnlyNotification", startedBy)
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
            
            // CRITICAL: Always load configuration regardless of how service was started
            Log.d(TAG, "🔄 FORCING CONFIGURATION LOAD ON SERVICE START")
            loadConfiguration()
            checkAndFixConfiguration()
        initializeCarPowerManager()


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
            intent?.action == "GET_CONFIGURATION" -> {
                Log.d(TAG, "📊 HANDLING GET CONFIGURATION")
                val config = getCurrentConfiguration()
                Log.d(TAG, "Current configuration: $config")
            }
            intent?.action == "REFRESH_SATELLITE_DATA" -> {
                Log.d(TAG, "🛰️ HANDLING REFRESH SATELLITE DATA")
                refreshSatelliteData()
            }
            intent?.action == "GET_IGNITION_STATE_TRACKING_STATUS" -> {
                Log.d(TAG, "📊 HANDLING GET IGNITION STATE TRACKING STATUS")
                val status = getIgnitionStateTrackingStatus()
                Log.d(TAG, "✅ Ignition state tracking status retrieved")
            }
            intent?.action == "TRIGGER_IGNITION_STATE_CHANGE" -> {
                Log.d(TAG, "🚗 HANDLING TRIGGER IGNITION STATE CHANGE")
                val newStatus = intent.getIntExtra("new_status", 0)
                triggerIgnitionStateChange(newStatus)
                Log.d(TAG, "✅ Ignition state change triggered for status: $newStatus")
            }
            intent?.action == "CLEAR_IGNITION_STATE_TRACKING" -> {
                Log.d(TAG, "🧹 HANDLING CLEAR IGNITION STATE TRACKING")
                clearIgnitionStateTracking()
                Log.d(TAG, "✅ Ignition state tracking cleared")
            }
            intent?.action == "GET_TIMING_STATUS" -> {
                Log.d(TAG, "📊 HANDLING GET TIMING STATUS")
                val status = getTimingStatus()
                Log.d(TAG, "✅ Timing status retrieved")
            }
            isCarPowerTriggered -> {
                Log.d(TAG, "🚗 CAR POWER TRIGGERED START")
                handleCarPowerStart(intent)
                
                // CRITICAL: Force configuration reload for car power triggered start
                Log.d(TAG, "🔄 CAR POWER TRIGGERED: FORCING CONFIGURATION RELOAD")
                forceReloadConfigurationAndRestart()
            }
            isWakeUpFromSleep -> {
                Log.d(TAG, "🚗 Waking up from sleep state")
                handleWakeUpFromSleep(isBackgroundOnly)
                
                // CRITICAL: Force configuration reload for wake-up from sleep
                Log.d(TAG, "🔄 WAKE-UP FROM SLEEP: FORCING CONFIGURATION RELOAD")
                forceReloadConfigurationAndRestart()
            }
            isSleepKeepAlive -> {
                Log.d(TAG, "🚗 Sleep keep-alive")
                handleSleepKeepAlive()
                
                // CRITICAL: Force configuration reload for sleep keep-alive
                Log.d(TAG, "🔄 SLEEP KEEP-ALIVE: FORCING CONFIGURATION RELOAD")
                forceReloadConfigurationAndRestart()
            }
            wasSleeping -> {
                Log.d(TAG, "🚗 Service restart during sleep - resuming sleep management")
                handleServiceRestartDuringSleep()
                
                // CRITICAL: Force configuration reload for service restart during sleep
                Log.d(TAG, "🔄 SERVICE RESTART DURING SLEEP: FORCING CONFIGURATION RELOAD")
                forceReloadConfigurationAndRestart()
            }
            isBackgroundOnly -> {
                Log.d(TAG, "🔄 Background-only start mode")
                handleBackgroundOnlyStart(startedBy)
                
                // CRITICAL: Force configuration reload for background mode
                Log.d(TAG, "🔄 BACKGROUND MODE: FORCING CONFIGURATION RELOAD")
                forceReloadConfigurationAndRestart()
            }
            intent?.action == "FORCE_IGNITION_REASON_UPDATE" -> {
                Log.d(TAG, "🔄 HANDLING FORCE IGNITION REASON UPDATE")
                val newIgStatus = intent.getIntExtra("ig_status", 0)
                forceIgnitionReasonUpdate(newIgStatus)
            }
            intent?.action == "TEST_DATA_COLLECTION" -> {
                Log.d(TAG, "🧪 HANDLING TEST DATA COLLECTION")
                testDataCollection()
            }
            intent?.action == "FORCE_RELOAD_CONFIGURATION" -> {
                Log.d(TAG, "🔄 HANDLING FORCE RELOAD CONFIGURATION")
                forceReloadConfigurationAndRestart()
            }
            intent?.action == "LONG_INTERVAL_RESTART" -> {
                Log.d(TAG, "🔄 HANDLING LONG INTERVAL RESTART")
                ensureServicePersistenceForLongIntervals()
                
                // CRITICAL: Force configuration reload for long interval restart
                Log.d(TAG, "🔄 LONG INTERVAL RESTART: FORCING CONFIGURATION RELOAD")
                forceReloadConfigurationAndRestart()
            }
            else -> {
                Log.d(TAG, "🚗 Normal service operation")
                handleNormalServiceStart(startedBy, isAutoStarted)
            }
        }
        
        if (!isCarPowerInitialized) {
            Log.w(TAG, "CarPowerManager not initialized, retrying...")
//            initializeCarPowerManager()
        }

        // --- FIX: Always ensure tracking is started, regardless of how service is started ---
        ensureServiceIsTracking()
        // --- END FIX ---
        
        // NEW: Ensure service stays alive and continues collecting data
        ensureServicePersistence()
        
        // NEW: Enhanced persistence for long intervals
        ensureServicePersistenceForLongIntervals()
        
        return START_STICKY
    }

    // --- FIX: Helper to always start foreground, location updates, and periodic sync ---
    private fun ensureServiceIsTracking() {
        try {
            // Only start foreground if not already running (don't override existing notification)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Check if service is already in foreground
                val notificationManager = getSystemService(NotificationManager::class.java)
                val activeNotifications = notificationManager.activeNotifications
                val isAlreadyForeground = activeNotifications.any { it.id == NOTIFICATION_ID }
                
                if (!isAlreadyForeground) {
                    startForeground(NOTIFICATION_ID, createNotification())
                    Log.e(TAG, "ERROR: Started foreground service with default notification")
                } else {
                    Log.e(TAG, "ERROR: Service already in foreground, keeping existing notification")
                }
            }
            // Always start location updates and periodic sync
            startLocationUpdates()
            startPeriodicSync()
            Log.e(TAG, "ERROR: ensureServiceIsTracking: Location updates and periodic sync started")
        } catch (e: Exception) {
            Log.e(TAG, "ERROR: Exception in ensureServiceIsTracking", e)
        }
    }

    // FIX: Add missing startedBy parameter
    private fun handleBackgroundOnlyStart(startedBy: String) {
        try {
            Log.d(TAG, "=== HANDLING BACKGROUND-ONLY START ===")
            
            // CRITICAL: Force configuration reload for background-only mode
            Log.d(TAG, "🔄 BACKGROUND-ONLY: FORCING CONFIGURATION RELOAD")
            loadConfiguration()
            checkAndFixConfiguration()
            
            showBackgroundOnlyNotification(startedBy)
            startLocationUpdates()
            startPeriodicSync()
            refreshWakeLocks()
            
            Log.d(TAG, "✅ Background-only service started successfully with configuration reload")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling background-only start", e)
        }
    }

    private fun handleServiceRestartDuringSleep() {
        try {
            Log.d(TAG, "=== HANDLING SERVICE RESTART DURING SLEEP ===")
            
            // CRITICAL: Force configuration reload for service restart during sleep
            Log.d(TAG, "🔄 SERVICE RESTART DURING SLEEP: FORCING CONFIGURATION RELOAD")
            loadConfiguration()
            checkAndFixConfiguration()
            
            if (!isCarPowerInitialized) {
//                initializeCarPowerManager()
            }
            
            showSleepNotification()
            acquireWakeLock()
            
            Log.d(TAG, "✅ Service restart during sleep handled with configuration reload")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling service restart during sleep", e)
        }
    }

    private fun handleNormalServiceStart(startedBy: String, isAutoStarted: Boolean) {
        try {
            Log.d(TAG, "=== HANDLING NORMAL SERVICE START ===")
            Log.d(TAG, "Started by: $startedBy")
            Log.d(TAG, "Auto started: $isAutoStarted")
            
            // CRITICAL: Force configuration reload for normal service start
            Log.d(TAG, "🔄 NORMAL SERVICE START: FORCING CONFIGURATION RELOAD")
            loadConfiguration()
            checkAndFixConfiguration()
            
            if (isAutoStarted) {
                showBackgroundOnlyNotification(startedBy)
            } else {
                startForeground(NOTIFICATION_ID, createNotification())
            }
            
            startLocationUpdates()
            startPeriodicSync()
            
            Log.d(TAG, "✅ Normal service start completed with configuration reload")
            
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
            
            // CRITICAL: Force configuration reload for wake-up from sleep
            Log.d(TAG, "🔄 WAKE-UP FROM SLEEP: FORCING CONFIGURATION RELOAD")
            loadConfiguration()
            checkAndFixConfiguration()
            
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
            
            // CRITICAL: Always schedule restart regardless of sleep state
            Log.e(TAG, "🚨 Service being destroyed - scheduling immediate restart")
            scheduleServiceRestart()
            
            if (isSleeping) {
                Log.d(TAG, "🚗 Service destroyed during sleep - scheduling restart")
                // Additional restart for sleep state
                scheduleServiceRestart()
                return
            }
            
            // NEW: Stop configuration reload timer
            stopConfigurationReloadTimer()
            
            carPowerManager?.cleanup()
            fusedLocationClient.removeLocationUpdates(locationCallback)
            releaseWakeLock()
            unregisterReceiver(restartReceiver)
            stopForeground(true)
            
            Log.d(TAG, "✅ Service cleanup completed")
        } catch (e: Exception) {
            Log.e(TAG, "Error during service cleanup", e)
            // Even if cleanup fails, still schedule restart
            scheduleServiceRestart()
        }
    }

    private fun scheduleServiceRestart() {
        try {
            Log.d(TAG, "🔄 Scheduling service restart...")
            
            // Method 1: Use AlarmManager for immediate restart
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(this, BackgroundService::class.java).apply {
                putExtra("started_by", "service_restart")
                putExtra("auto_started", true)
                putExtra("background_only", true)
            }
            val pendingIntent = PendingIntent.getService(
                this,
                999,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            val triggerTime = SystemClock.elapsedRealtime() + 5000 // Restart in 5 seconds
            
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
            
            // Method 2: Also schedule a backup restart using WorkManager or delayed intent
            val backupIntent = Intent(this, ServiceRestartReceiver::class.java).apply {
                action = "com.trackingWorld.tracking.RESTART_SERVICE"
            }
            val backupPendingIntent = PendingIntent.getBroadcast(
                this,
                998,
                backupIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            // Schedule backup restart in 10 seconds
            val backupTriggerTime = SystemClock.elapsedRealtime() + 10000
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    backupTriggerTime,
                    backupPendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    backupTriggerTime,
                    backupPendingIntent
                )
            }
            
            Log.d(TAG, "✅ Service restart scheduled (primary + backup)")
            
            // Method 3: Set a flag in SharedPreferences to indicate service needs restart
            val prefs = getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
            prefs.edit().apply {
                putBoolean("flutter.service_needs_restart", true)
                putLong("flutter.service_destroyed_time", System.currentTimeMillis())
                apply()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to schedule service restart: ${e.message}")
            
            // Fallback: Try to start service directly
            try {
                val serviceIntent = Intent(this, BackgroundService::class.java).apply {
                    putExtra("started_by", "fallback_restart")
                    putExtra("auto_started", true)
                    putExtra("background_only", true)
                }
                startService(serviceIntent)
                Log.d(TAG, "✅ Fallback service restart attempted")
            } catch (fallbackException: Exception) {
                Log.e(TAG, "❌ Fallback restart also failed: ${fallbackException.message}")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // Implementation methods - keep your existing logic
    private fun loadConfiguration() {
        try {
            val prefs = getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
            
            Log.d(TAG, "🔄 LOADING CONFIGURATION FROM SHAREDPREFERENCES:")
            
            // Log all available configuration keys
            val allPrefs = prefs.all
            Log.d(TAG, "📋 All SharedPreferences keys:")
            allPrefs.forEach { (key, value) ->
                if (key.startsWith("flutter.")) {
                    Log.d(TAG, "   - $key: $value")
                }
            }
            
            val oldGpsTimer = gpsTimer
            val oldUploadTimer = uploadTimer
            val oldAngleThreshold = angleThreshold
            val oldDistanceThreshold = distanceThreshold
            
            // Load configuration with detailed logging
            val rawGpsTimer = prefs.getInt("flutter.gpsTimer", 5)
            val rawUploadTimer = prefs.getInt("flutter.uploadTimer", 10)
            val rawAngleThreshold = prefs.getFloat("flutter.angleThreshold", 45f)
            val rawOverSpeedingThreshold = prefs.getFloat("flutter.overSpeedingThreshold", 70f)
            val rawDistanceThreshold = prefs.getFloat("flutter.distanceThreshold", 500f)
            val rawMovingTimer = prefs.getInt("flutter.movingTimer", 60)
            val rawStopTimer = prefs.getInt("flutter.stopTimer", 130)
            
            Log.d(TAG, "📥 Raw values from SharedPreferences:")
            Log.d(TAG, "   - flutter.gpsTimer: $rawGpsTimer")
            Log.d(TAG, "   - flutter.uploadTimer: $rawUploadTimer")
            Log.d(TAG, "   - flutter.angleThreshold: $rawAngleThreshold")
            Log.d(TAG, "   - flutter.overSpeedingThreshold: $rawOverSpeedingThreshold")
            Log.d(TAG, "   - flutter.distanceThreshold: $rawDistanceThreshold")
            Log.d(TAG, "   - flutter.movingTimer: $rawMovingTimer")
            Log.d(TAG, "   - flutter.stopTimer: $rawStopTimer")
            
            // Assign values
            gpsTimer = rawGpsTimer
            uploadTimer = rawUploadTimer
            angleThreshold = rawAngleThreshold
            overSpeedingThreshold = rawOverSpeedingThreshold
            distanceThreshold = rawDistanceThreshold
            movingTimer = rawMovingTimer
            stopTimer = rawStopTimer
            
            Log.d(TAG, "✅ Assigned configuration values:")
            Log.d(TAG, "   - gpsTimer: $gpsTimer")
            Log.d(TAG, "   - uploadTimer: $uploadTimer")
            Log.d(TAG, "   - angleThreshold: $angleThreshold")
            Log.d(TAG, "   - overSpeedingThreshold: $overSpeedingThreshold")
            Log.d(TAG, "   - distanceThreshold: $distanceThreshold")
            Log.d(TAG, "   - movingTimer: $movingTimer")
            Log.d(TAG, "   - stopTimer: $stopTimer")
            
            // Log configuration changes
            if (oldGpsTimer != gpsTimer || oldUploadTimer != uploadTimer || 
                oldAngleThreshold != angleThreshold || oldDistanceThreshold != distanceThreshold) {
                Log.d(TAG, "🔄 Configuration updated:")
                Log.d(TAG, "   - GPS Timer: $oldGpsTimer → $gpsTimer")
                Log.d(TAG, "   - Upload Timer: $oldUploadTimer → $uploadTimer")
                Log.d(TAG, "   - Angle Threshold: $oldAngleThreshold → $angleThreshold")
                Log.d(TAG, "   - Distance Threshold: $oldDistanceThreshold → $distanceThreshold")
                
                // Restart location updates with new GPS timer
                if (oldGpsTimer != gpsTimer) {
                    restartLocationUpdatesWithNewInterval()
                }
                
                // Restart sync with new upload timer
                if (oldUploadTimer != uploadTimer) {
                    restartPeriodicSyncWithNewInterval()
                }
            } else {
                Log.d(TAG, "✅ Configuration loaded (no changes)")
            }
            
            Log.d(TAG, "📊 Current configuration:")
            Log.d(TAG, "   - GPS Timer: ${gpsTimer}s")
            Log.d(TAG, "   - Upload Timer: ${uploadTimer}s")
            Log.d(TAG, "   - Angle Threshold: ${angleThreshold}°")
            Log.d(TAG, "   - Distance Threshold: ${distanceThreshold}m")
            Log.d(TAG, "   - Over Speeding Threshold: ${overSpeedingThreshold} km/h")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error loading configuration: ${e.message}")
        }
    }
    
    // NEW: Start configuration reload timer
    private fun startConfigurationReloadTimer() {
        try {
            configReloadTimer?.cancel()
            configReloadTimer = java.util.Timer()
            
            configReloadTimer?.scheduleAtFixedRate(object : java.util.TimerTask() {
                override fun run() {
                    try {
                        Log.d(TAG, "🔄 Reloading configuration from SharedPreferences...")
                        loadConfiguration()
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error in configuration reload: ${e.message}")
                    }
                }
            }, 30000, 30000) // Reload every 30 seconds
            
            Log.d(TAG, "✅ Configuration reload timer started (every 30s)")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error starting configuration reload timer: ${e.message}")
        }
    }
    
    // NEW: Stop configuration reload timer
    private fun stopConfigurationReloadTimer() {
        try {
            configReloadTimer?.cancel()
            configReloadTimer = null
            Log.d(TAG, "✅ Configuration reload timer stopped")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error stopping configuration reload timer: ${e.message}")
        }
    }
    
    // NEW: Restart location updates with new GPS timer
    private fun restartLocationUpdatesWithNewInterval() {
        try {
            Log.d(TAG, "🔄 Restarting location updates with new GPS timer: ${gpsTimer}s")
            stopLocationUpdates()
            
            // Wait a moment then restart
            Handler().postDelayed({
                startLocationUpdates()
            }, 1000)
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error restarting location updates: ${e.message}")
        }
    }
    
    // NEW: Restart periodic sync with new upload timer
    private fun restartPeriodicSyncWithNewInterval() {
        try {
            Log.d(TAG, "🔄 Restarting periodic sync with new upload timer: ${uploadTimer}s")
            
            // Cancel existing sync executor
            syncExecutor.shutdown()
            syncExecutor = Executors.newSingleThreadScheduledExecutor()
            
            // Restart periodic sync
            startPeriodicSync()
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error restarting periodic sync: ${e.message}")
        }
    }

    private fun setupLocationUpdates() {
        try {
            Log.e(TAG, "ERROR: setupLocationUpdates called")
            setupEnhancedGnssCallback()
            forceRegisterGnssCallback()
            
            // CRITICAL: Ensure GPS timer is loaded before creating location request
            loadConfiguration()
            
            Log.d(TAG, "📍 SETTING UP LOCATION UPDATES WITH GPS TIMER: ${gpsTimer}s")
            
            val locationRequest = LocationRequest.create().apply {
                priority = LocationRequest.PRIORITY_HIGH_ACCURACY
                interval = gpsTimer * 1000L  // Convert seconds to milliseconds
                fastestInterval = gpsTimer * 1000L  // Set to same as interval to prevent rapid updates
                maxWaitTime = gpsTimer * 2000L  // Allow some flexibility
                smallestDisplacement = 1f
            }
            
            Log.d(TAG, "📍 Location Request Configuration:")
            Log.d(TAG, "   - Interval: ${locationRequest.interval}ms (${locationRequest.interval / 1000}s)")
            Log.d(TAG, "   - Fastest Interval: ${locationRequest.fastestInterval}ms (${locationRequest.fastestInterval / 1000}s)")
            Log.d(TAG, "   - Max Wait Time: ${locationRequest.maxWaitTime}ms (${locationRequest.maxWaitTime / 1000}s)")
            Log.d(TAG, "   - Smallest Displacement: ${locationRequest.smallestDisplacement}m")

            locationCallback = object : LocationCallback() {
                override fun onLocationResult(locationResult: LocationResult) {
                    Log.e(TAG, "ERROR: onLocationResult called in BackgroundService")
                    locationResult.lastLocation?.let { location ->
                        Log.e(TAG, "ERROR: Location received: lat=${location.latitude}, lon=${location.longitude}, acc=${location.accuracy}")
                        
                        // Add more detailed logging
                        Log.d(TAG, "📍 LOCATION UPDATE RECEIVED:")
                        Log.d(TAG, "   - Latitude: ${location.latitude}")
                        Log.d(TAG, "   - Longitude: ${location.longitude}")
                        Log.d(TAG, "   - Accuracy: ${location.accuracy}m")
                        Log.d(TAG, "   - Speed: ${location.speed * 3.6} km/h")
                        Log.d(TAG, "   - Bearing: ${location.bearing}")
                        Log.d(TAG, "   - Timestamp: ${location.time}")
                        Log.d(TAG, "   - Current igStatus: $igStatus")
                        Log.d(TAG, "   - isIgStatusReady: $isIgStatusReady")
                        Log.d(TAG, "   - Service PID: ${android.os.Process.myPid()}")
                        Log.d(TAG, "   - Service running: true")
                        
                        if (location.accuracy > 30) {
                            Log.e(TAG, "ERROR: Skipping inaccurate location: accuracy = ${location.accuracy}m")
                            return
                        }
                        
                        val currentTime = System.currentTimeMillis()
                        val timeSinceLastUpdate = currentTime - lastLocationUpdateTime
                        val speed = getEnhancedAccurateSpeed(location)
                        val distance = lastLocation?.distanceTo(location) ?: 0f

                        Log.d(TAG, "🔍 LOCATION PROCESSING:")
                        Log.d(TAG, "   - Time since last update: ${timeSinceLastUpdate}ms")
                        Log.d(TAG, "   - Distance from last: ${distance}m")
                        Log.d(TAG, "   - Speed: ${speed} km/h")

                        if (shouldProcessLocationUpdate(location, speed, distance, timeSinceLastUpdate)) {
                            val reason = calculateEnhancedReason(location)
                            Log.e(TAG, "ERROR: Processing location update. Speed: ${String.format("%.1f", speed)} km/h, Reason: $reason")
                            val correctedLocation = Location(location).apply {
                                this.speed = speed / 3.6f
                            }
                            saveLocationData(correctedLocation)
                            lastLocationUpdateTime = currentTime
                            lastLocation = location
                            
                            Log.d(TAG, "✅ Location data saved successfully with reason: $reason")
                            Log.d(TAG, "✅ Service continues running and collecting data")
                        } else {
                            Log.e(TAG, "ERROR: Location update not processed. speed=$speed, distance=$distance, timeSinceLastUpdate=$timeSinceLastUpdate")
                            Log.d(TAG, "ℹ️ Location update skipped - conditions not met")
                        }
                    } ?: Log.e(TAG, "ERROR: onLocationResult: lastLocation is null")
                }
            }

            startLocationUpdates()
        } catch (e: Exception) {
            Log.e(TAG, "ERROR: Exception in setupLocationUpdates", e)
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

    private fun refreshSatelliteData() {
        Log.d(TAG, "🛰️ Refreshing satellite data...")
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
                
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    // Force re-register the GNSS callback to trigger satellite status update
                    try {
                        locationManager.unregisterGnssStatusCallback(gnssStatusCallback)
                    } catch (e: Exception) {
                        Log.d(TAG, "GNSS callback was not registered, continuing...")
                    }
                    
                    // Re-register the callback
                    locationManager.registerGnssStatusCallback(gnssStatusCallback)
                    Log.d(TAG, "🛰️ GNSS callback re-registered for satellite data refresh")
                    
                    // Log current satellite data
                    Log.d(TAG, "🛰️ Current satellite data: $connectedSatellites/$totalSatellites")
                } else {
                    Log.w(TAG, "🛰️ Location permission not granted for satellite data refresh")
                }
            } else {
                Log.w(TAG, "🛰️ GNSS status not available on this Android version")
            }
        } catch (e: Exception) {
            Log.e(TAG, "🛰️ Error refreshing satellite data: $e")
        }
    }

    private fun startLocationUpdates() {
        try {
            Log.e(TAG, "ERROR: startLocationUpdates called")
            if (!::fusedLocationClient.isInitialized || !::locationCallback.isInitialized) {
                Log.e(TAG, "ERROR: Location components not ready")
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
                Log.e(TAG, "ERROR: Location updates started")
            } else {
                Log.e(TAG, "ERROR: Missing ACCESS_FINE_LOCATION permission")
            }
        } catch (e: Exception) {
            Log.e(TAG, "ERROR: Exception in startLocationUpdates", e)
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
            Log.e(TAG, "ERROR: startPeriodicSync called")
            syncExecutor.scheduleAtFixedRate({
                try {
                    Log.e(TAG, "ERROR: Periodic sync task executing")
                    if (!isSyncing) {
                        Log.e(TAG, "ERROR: Calling syncData() from periodic task")
                        syncData()
                    } else {
                        Log.e(TAG, "ERROR: Sync already in progress, skipping periodic sync")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "ERROR: Exception in periodic sync task", e)
                }
            }, 0, uploadTimer.toLong(), TimeUnit.SECONDS)
            
            // Add periodic igStatus test (every 30 seconds)
            syncExecutor.scheduleAtFixedRate({
                try {
                    Log.e(TAG, "ERROR: Periodic igStatus test executing")
                    testCurrentIgStatus()
                } catch (e: Exception) {
                    Log.e(TAG, "ERROR: Exception in periodic igStatus test", e)
                }
            }, 30, 30, TimeUnit.SECONDS)
            
            // NEW: Add periodic power state checker (every 15 seconds)
            syncExecutor.scheduleAtFixedRate({
                try {
                    Log.e(TAG, "ERROR: Periodic power state check executing")
                    checkAndUpdatePowerState()
                } catch (e: Exception) {
                    Log.e(TAG, "ERROR: Exception in periodic power state check", e)
                }
            }, 15, 15, TimeUnit.SECONDS)
            
            // NEW: Add periodic data collection status check (every 60 seconds)
            syncExecutor.scheduleAtFixedRate({
                try {
                    Log.d(TAG, "📊 PERIODIC DATA COLLECTION STATUS CHECK")
                    
                    // Check database stats
                    val db = dbHelper.readableDatabase
                    val totalCursor = db.rawQuery("SELECT COUNT(*) FROM location_data", null)
                    totalCursor.moveToFirst()
                    val totalRecords = totalCursor.getInt(0)
                    totalCursor.close()
                    
                    val unsyncedCursor = db.rawQuery("SELECT COUNT(*) FROM location_data WHERE sync_status = 0", null)
                    unsyncedCursor.moveToFirst()
                    val unsyncedRecords = unsyncedCursor.getInt(0)
                    unsyncedCursor.close()
                    
                    val syncedCursor = db.rawQuery("SELECT COUNT(*) FROM location_data WHERE sync_status = 1", null)
                    syncedCursor.moveToFirst()
                    val syncedRecords = syncedCursor.getInt(0)
                    syncedCursor.close()
                    
                    Log.d(TAG, "📊 Database Status:")
                    Log.d(TAG, "   - Total records: $totalRecords")
                    Log.d(TAG, "   - Unsynced records: $unsyncedRecords")
                    Log.d(TAG, "   - Synced records: $syncedRecords")
                    Log.d(TAG, "   - Current igStatus: $igStatus")
                    Log.d(TAG, "   - isIgStatusReady: $isIgStatusReady")
                    Log.d(TAG, "   - isCarPowerInitialized: $isCarPowerInitialized")
                    
                    if (totalRecords == 0) {
                        Log.w(TAG, "⚠️ No data collected - checking location updates")
                        // Check if location updates are working
                        if (::fusedLocationClient.isInitialized) {
                            Log.d(TAG, "✅ Location client is initialized")
                        } else {
                            Log.e(TAG, "❌ Location client not initialized")
                        }
                    } else if (unsyncedRecords > 0) {
                        Log.w(TAG, "⚠️ $unsyncedRecords unsynced records - sync might not be working")
                    } else {
                        Log.d(TAG, "✅ Data collection and sync working properly")
                    }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "ERROR: Exception in periodic data collection status check", e)
                }
            }, 60, 60, TimeUnit.SECONDS)
            
            // NEW: Add periodic data collection test (every 2 minutes)
            syncExecutor.scheduleAtFixedRate({
                try {
                    Log.d(TAG, "🧪 PERIODIC DATA COLLECTION TEST")
                    
                    // Force a location collection test
                    testDataCollection()
                    
                    Log.d(TAG, "✅ Periodic data collection test completed")
                    
                } catch (e: Exception) {
                    Log.e(TAG, "ERROR: Exception in periodic data collection test", e)
                }
            }, 120, 120, TimeUnit.SECONDS)
            
            // NEW: Add periodic service health check (every 30 seconds)
            syncExecutor.scheduleAtFixedRate({
                try {
                    Log.d(TAG, "🏥 PERIODIC SERVICE HEALTH CHECK")
                    
                    // Check if service is still in foreground
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val notificationManager = getSystemService(NotificationManager::class.java)
                        val activeNotifications = notificationManager.activeNotifications
                        val isInForeground = activeNotifications.any { it.id == NOTIFICATION_ID }
                        
                        if (!isInForeground) {
                            Log.w(TAG, "⚠️ Service not in foreground - restarting foreground")
                            startForeground(NOTIFICATION_ID, createNotification())
                        } else {
                            Log.d(TAG, "✅ Service is in foreground")
                        }
                    }
                    
                    // Check if location updates are active
                    if (::fusedLocationClient.isInitialized) {
                        Log.d(TAG, "✅ Location client is initialized")
                    } else {
                        Log.w(TAG, "⚠️ Location client not initialized - reinitializing")
                        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
                    }
                    
                    // Check if CarPowerManager is working
                    if (isCarPowerInitialized && carPowerManager != null) {
                        Log.d(TAG, "✅ CarPowerManager is initialized")
                    } else {
                        Log.w(TAG, "⚠️ CarPowerManager not initialized - reinitializing")
//                        initializeCarPowerManager()
                    }
                    
                    // Check wake locks
                    if (wakeLock?.isHeld == true) {
                        Log.d(TAG, "✅ Wake lock is held")
                    } else {
                        Log.w(TAG, "⚠️ Wake lock not held - reacquiring")
                        acquireWakeLock()
                    }
                    
                    // Check if sync executor is running
                    if (!syncExecutor.isShutdown) {
                        Log.d(TAG, "✅ Sync executor is running")
                    } else {
                        Log.w(TAG, "⚠️ Sync executor is shutdown - recreating")
                        syncExecutor = Executors.newSingleThreadScheduledExecutor()
                        startPeriodicSync()
                    }
                    
                    // NEW: Detect if app was cleared from background
                    detectAppClearedAndEnsureService()
                    
                    // NEW: Check configuration integrity periodically
                    checkAndFixConfiguration()
                    
                    Log.d(TAG, "✅ Health check completed")
                    
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error in health check: $e")
                }
            }, 30, 30, TimeUnit.SECONDS)
            
            // NEW: Add periodic service persistence check (every 2 minutes)
            syncExecutor.scheduleAtFixedRate({
                try {
                    Log.d(TAG, "🔒 PERIODIC SERVICE PERSISTENCE CHECK")
                    
                    // Check if service needs restart flag is set
                    val prefs = getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
                    val needsRestart = prefs.getBoolean("flutter.service_needs_restart", false)
                    
                    if (needsRestart) {
                        Log.w(TAG, "⚠️ Service restart flag detected - clearing flag")
                        prefs.edit().remove("flutter.service_needs_restart").apply()
                    }
                    
                    // Ensure service is properly registered for auto-start
                    ensureAutoStartRegistration()
                    
                    Log.d(TAG, "✅ Service persistence check completed")
                    
                } catch (e: Exception) {
                    Log.e(TAG, "ERROR: Exception in periodic service persistence check", e)
                }
            }, 120, 120, TimeUnit.SECONDS)
            
            Log.e(TAG, "ERROR: Periodic sync started with uploadTimer: ${uploadTimer}s")
        } catch (e: Exception) {
            Log.e(TAG, "ERROR: Exception in startPeriodicSync", e)
        }
    }

    private fun syncData() {
        // Your existing sync implementation
        Log.e(TAG, "ERROR: syncData() called - calling performSyncToServer()")
        performSyncToServer()
    }

    // UPDATED METHOD: Check and update power state periodically
    private fun checkAndUpdatePowerState() {
        try {
            Log.d(TAG, "🔍 PERIODIC POWER STATE CHECK")
            
            // Test current power state
            carPowerManager?.testCurrentPowerState()
            
            // NEW: Use force update method for more reliable igStatus detection
//            carPowerManager?.forceUpdateIgStatus()
            
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

    private fun getEnhancedAccurateSpeed(location: Location): Float {
        // Simplified speed calculation
        return if (location.hasSpeed() && location.speed >= 0) {
            location.speed * 3.6f // Convert m/s to km/h
        } else {
            0f
        }
    }

    // NEW: Check if we should save data based on reason timing rules
    private fun shouldSaveDataBasedOnReason(reason: String): Boolean {
        val currentTime = System.currentTimeMillis()
        
        // NEW: Always save ignition events (highest priority)
        if (reason == "Ignition On" || reason == "Ignition Off") {
            Log.e(TAG, "ERROR: ✅ ALWAYS SAVING IGNITION EVENT - Reason: $reason")
            Log.e(TAG, "ERROR:    - Ignition events have highest priority")
            Log.e(TAG, "ERROR:    - No timing restrictions for ignition events")
            return true
        }
        
        // Always save critical events (except Over Speeding which has timing rules)
        if (reason == "Distance" || reason == "Turn") {
            Log.d(TAG, "✅ Allowing save for critical reason: $reason")
            return true
        }
        
        // Apply timing rules for Idle and Move reasons
        when (reason) {
            "Idle" -> {
                if (lastIdleSyncTime > 0) {
                    val timeSinceLastSync = currentTime - lastIdleSyncTime
                    if (timeSinceLastSync < idleConsecutiveTimeout) {
                        Log.d(TAG, "⏳ Skipping Idle save - last sync was ${timeSinceLastSync / 1000}s ago (need ${idleConsecutiveTimeout / 1000}s)")
                        return false
                    } else {
                        Log.d(TAG, "✅ Allowing Idle save - ${timeSinceLastSync / 1000}s since last sync (threshold: ${idleConsecutiveTimeout / 1000}s)")
                        lastIdleSyncTime = currentTime
                        return true
                    }
                } else {
                    Log.d(TAG, "✅ First Idle save - allowing")
                    lastIdleSyncTime = currentTime
                    return true
                }
            }
            "Move" -> {
                if (lastMoveSyncTime > 0) {
                    val timeSinceLastSync = currentTime - lastMoveSyncTime
                    if (timeSinceLastSync < moveConsecutiveTimeout) {
                        Log.d(TAG, "⏳ Skipping Move save - last sync was ${timeSinceLastSync / 1000}s ago (need ${moveConsecutiveTimeout / 1000}s)")
                        return false
                    } else {
                        Log.d(TAG, "✅ Allowing Move save - ${timeSinceLastSync / 1000}s since last sync (threshold: ${moveConsecutiveTimeout / 1000}s)")
                        lastMoveSyncTime = currentTime
                        return true
                    }
                } else {
                    Log.d(TAG, "✅ First Move save - allowing")
                    lastMoveSyncTime = currentTime
                    return true
                }
            }
            "Over Speeding" -> {
                if (lastOverSpeedingSyncTime > 0) {
                    val timeSinceLastSync = currentTime - lastOverSpeedingSyncTime
                    if (timeSinceLastSync < overSpeedingConsecutiveTimeout) {
                        Log.d(TAG, "⏳ Skipping Over Speeding save - last sync was ${timeSinceLastSync / 1000}s ago (need ${overSpeedingConsecutiveTimeout / 1000}s)")
                        return false
                    } else {
                        Log.d(TAG, "✅ Allowing Over Speeding save - ${timeSinceLastSync / 1000}s since last sync (threshold: ${overSpeedingConsecutiveTimeout / 1000}s)")
                        lastOverSpeedingSyncTime = currentTime
                        return true
                    }
                } else {
                    Log.d(TAG, "✅ First Over Speeding save - allowing")
                    lastOverSpeedingSyncTime = currentTime
                    return true
                }
            }
            else -> {
                Log.d(TAG, "✅ Allowing save for other reason: $reason")
                return true
            }
        }
    }

    private fun calculateEnhancedReason(location: Location, isIgnitionChange: Boolean = false, ignitionStatus: Int = -1): String {
        // NEW: Check for pending ignition reasons first (highest priority)
        if (pendingIgnitionOnReason) {
            Log.e(TAG, "ERROR: 🚗 PENDING IGNITION ON REASON DETECTED - Using 'Ignition On'")
            pendingIgnitionOnReason = false // Clear the flag after using it
            return "Ignition On"
        }
        
        if (pendingIgnitionOffReason) {
            Log.e(TAG, "ERROR: 🚗 PENDING IGNITION OFF REASON DETECTED - Using 'Ignition Off'")
            pendingIgnitionOffReason = false // Clear the flag after using it
            return "Ignition Off"
        }
        
        // If this is an ignition state change, prioritize ignition reason
        if (isIgnitionChange && ignitionStatus != -1) {
            val ignitionReason = if (ignitionStatus == 1) "Ignition On" else "Ignition Off"
            Log.d(TAG, "🚗 Ignition state change detected - reason: $ignitionReason")
            return ignitionReason
        }
        
        val speed = getEnhancedAccurateSpeed(location)
        
        Log.d(TAG, "🔍 Reason calculation - Config: Distance=${distanceThreshold}m, Angle=${angleThreshold}°, Speed=${overSpeedingThreshold}km/h")
        
        // Check for distance-based reason if we have a previous location
        if (lastLocation != null) {
            val distance = lastLocation!!.distanceTo(location)
            Log.d(TAG, "📏 Distance calculation: ${String.format("%.2f", distance)}m (threshold: ${distanceThreshold}m)")
            Log.d(TAG, "📍 Last position: ${lastLocation!!.latitude}, ${lastLocation!!.longitude}")
            Log.d(TAG, "📍 Current position: ${location.latitude}, ${location.longitude}")
            
            if (distance >= distanceThreshold) {
                Log.d(TAG, "✅ Distance reason triggered: ${String.format("%.2f", distance)}m >= ${distanceThreshold}m")
                return "Distance"
            } else {
                Log.d(TAG, "ℹ️ Distance below threshold: ${String.format("%.2f", distance)}m < ${distanceThreshold}m")
            }
        } else {
            Log.d(TAG, "📍 First position - no previous location for distance calculation")
        }
        
        // Check for turn detection if we have a previous location
        if (lastLocation != null && speed >= 5f) {
            val bearingChange = kotlin.math.abs(location.bearing - lastLocation!!.bearing)
            val normalizedBearingChange = if (bearingChange > 180f) 360f - bearingChange else bearingChange
            
            Log.d(TAG, "🧭 Bearing change: ${String.format("%.2f", normalizedBearingChange)}° (threshold: ${angleThreshold}°)")
            
            if (normalizedBearingChange >= angleThreshold) {
                Log.d(TAG, "✅ Turn reason triggered: ${String.format("%.2f", normalizedBearingChange)}° >= ${angleThreshold}°")
                return "Turn"
            }
        }
        
        return when {
            speed > overSpeedingThreshold -> {
                Log.d(TAG, "✅ Over Speeding reason triggered: ${String.format("%.1f", speed)} km/h > ${overSpeedingThreshold} km/h")
                "Over Speeding"
            }
            speed >= 8f -> {
                Log.d(TAG, "✅ Move reason triggered: speed >= 8 km/h (${String.format("%.1f", speed)} km/h)")
                "Move"
            }
            speed < 3f -> {
                Log.d(TAG, "✅ Idle reason triggered: speed < 3 km/h (${String.format("%.1f", speed)} km/h)")
                "Idle"
            }
            else -> {
                Log.d(TAG, "✅ Idle reason triggered: default case (${String.format("%.1f", speed)} km/h)")
                "Idle"
            }
        }
    }

    private fun shouldProcessLocationUpdate(location: Location, speed: Float, distance: Float, timeSinceLastUpdate: Long): Boolean {
        Log.d(TAG, "🔍 LOCATION PROCESSING DECISION:")
        Log.d(TAG, "   - GPS Timer: ${gpsTimer}s (${gpsTimer * 1000L}ms)")
        Log.d(TAG, "   - Distance Threshold: ${distanceThreshold}m")
        Log.d(TAG, "   - Over Speeding Threshold: ${overSpeedingThreshold} km/h")
        Log.d(TAG, "   - Time since last update: ${timeSinceLastUpdate}ms")
        Log.d(TAG, "   - Distance from last: ${distance}m")
        Log.d(TAG, "   - Current speed: ${speed} km/h")
        Log.d(TAG, "   - Last location: ${if (lastLocation != null) "available" else "null"}")
        
        val shouldProcess = when {
            lastLocation == null -> {
                Log.d(TAG, "✅ Processing: First location")
                true
            }
            location.accuracy > 50 -> {
                Log.d(TAG, "❌ Skipping: Poor accuracy (${location.accuracy}m)")
                false
            }
            timeSinceLastUpdate >= gpsTimer * 1000L -> {
                Log.d(TAG, "✅ Processing: Time threshold met (${timeSinceLastUpdate}ms >= ${gpsTimer * 1000L}ms)")
                true
            }
            distance >= distanceThreshold -> {
                Log.d(TAG, "✅ Processing: Distance threshold met (${distance}m >= ${distanceThreshold}m)")
                true
            }
            speed >= overSpeedingThreshold -> {
                Log.d(TAG, "✅ Processing: Over speeding (${speed} km/h >= ${overSpeedingThreshold} km/h)")
                true
            }
            speed >= 8f && distance > 15f -> {
                Log.d(TAG, "✅ Processing: Moving with distance (${speed} km/h >= 8 km/h && ${distance}m > 15m)")
                true
            }
            else -> {
                Log.d(TAG, "❌ Skipping: No conditions met")
                false
            }
        }
        
        Log.d(TAG, "📊 Final decision: ${if (shouldProcess) "PROCESS" else "SKIP"}")
        return shouldProcess
    }

    private fun saveLocationData(location: Location) {
        val reason = calculateEnhancedReason(location, false, -1)
        saveLocationDataWithReason(location, reason)
    }

    private fun saveLocationDataWithReason(location: Location, reason: String) {
        try {
            if (!isIgStatusReady) {
                Log.w(TAG, "⚠️ Discarding location point because igStatus is not ready yet.")
                return
            }

            // NEW: Check if data should be sent based on ignition state
            if (!shouldSendData && reason != "Ignition Off") {
                Log.e(TAG, "ERROR: 🚫 DATA TRANSMISSION DISABLED - Ignition is OFF")
                Log.e(TAG, "ERROR:    - Current igStatus: $igStatus")
                Log.e(TAG, "ERROR:    - Should send data: $shouldSendData")
                Log.e(TAG, "ERROR:    - Reason: $reason")
                Log.e(TAG, "ERROR:    - Only 'Ignition Off' reason allowed when ignition is OFF")
                return
            }

            // NEW: Check if we should save based on reason timing rules
            if (!shouldSaveDataBasedOnReason(reason)) {
                Log.d(TAG, "⏭️ Skipping save for reason: $reason (timing rule applied)")
                return
            }

            val currentTime = System.currentTimeMillis()
            val imei = getImei()
            
            var fixedSpeed = location.speed * 3.6f
            if (fixedSpeed < 0) fixedSpeed = 0f
            
            // Allow saving with IMEI = "unknown" (buffer until IMEI is available)
            val currentIgStatus = this.igStatus
            
            Log.d(TAG, "💾 Saving location data with reason: $reason, igStatus: $currentIgStatus")
            
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
                put("igStatus", currentIgStatus)
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
            
            Log.e(TAG, "ERROR: 💾 SAVED LOCATION DATA - ID: $id, IMEI: $imei, Reason: $reason, Speed: ${String.format("%.1f", fixedSpeed)} km/h")
        } catch (e: Exception) {
            Log.e(TAG, "ERROR: Exception in saveLocationData: ${e.message}")
        }
    }

    private fun getImei(): String {
        try {
            // Always check SharedPreferences first
            val prefs = getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
            val storedImei = prefs.getString("flutter.imei", null)
            if (!storedImei.isNullOrEmpty() && storedImei != "unknown") {
                Log.d(TAG, "getImei: Loaded from SharedPreferences: $storedImei")
                return storedImei
            }
            
            // Fallback to ImeiManager
            val imeiManager = ImeiManager.getInstance(this)
            val deviceId = imeiManager.getDeviceIdentifier()
            if (deviceId != "unknown" && deviceId.isNotEmpty()) {
                // Save to SharedPreferences for future use
                prefs.edit().putString("flutter.imei", deviceId).apply()
                Log.d(TAG, "getImei: Obtained from ImeiManager and saved: $deviceId")
                return deviceId
            }

            Log.e(TAG, "getImei: IMEI is unknown")
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
            Log.e(TAG, "ERROR: Sync already in progress, skipping...")
            return
        }

        isSyncing = true
        Log.e(TAG, "ERROR: Starting sync to server...")

        try {
            val unsyncedData = dbHelper.getUnsyncedData(limit = 50)
            Log.e(TAG, "ERROR: Found ${unsyncedData.size} records to sync")
            
            if (unsyncedData.isEmpty()) {
                Log.e(TAG, "ERROR: No unsynced data to upload")
                return
            }

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
                    val igStatusBeingSent = dataToSend["igStatus"] as? Int ?: igStatus
                    val recordId = when (val id = data["id"]) {
                        is Long -> id.toString()
                        is Int -> id.toString()
                        is String -> id
                        else -> "unknown"
                    }
                    Log.e(TAG, "ERROR: 🔄 SYNC TO SERVER - Record ID: $recordId")
                    Log.e(TAG, "ERROR:    - igStatus being sent: $igStatusBeingSent")
                    Log.e(TAG, "ERROR:    - Current service igStatus: $igStatus")
                    Log.e(TAG, "ERROR:    - ACC state: ${if (igStatusBeingSent == 1) "ON" else "OFF"}")
                    Log.e(TAG, "ERROR:    - Timestamp: ${System.currentTimeMillis()}")

                    Log.e(TAG, "ERROR: Sending data: ${dataToSend}")

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
                        Log.e(TAG, "ERROR: ✅ Successfully synced record ID: $recordId with igStatus: $igStatusBeingSent")
                    } else {
                        Log.e(TAG, "ERROR: ❌ Server error: ${response.code} - ${response.body?.string()}")
                    }

                    response.close()

                } catch (e: Exception) {
                    val recordId = when (val id = data["id"]) {
                        is Long -> id.toString()
                        is Int -> id.toString()
                        is String -> id
                        else -> "unknown"
                    }
                    Log.e(TAG, "ERROR: Exception syncing record $recordId: $e")
                }
            }

            // Mark successfully synced records
            if (syncedIds.isNotEmpty()) {
                dbHelper.markAsSynced(syncedIds)
                Log.e(TAG, "ERROR: Marked ${syncedIds.size} records as synced")
            }

        } catch (e: Exception) {
            Log.e(TAG, "ERROR: Exception in sync to server", e)
        } finally {
            isSyncing = false
            Log.e(TAG, "ERROR: Sync to server completed")
        }
    }
    
    // NEW METHOD: Get current configuration for debugging
    fun getCurrentConfiguration(): Map<String, Any> {
        return mapOf(
            "gpsTimer" to gpsTimer,
            "uploadTimer" to uploadTimer,
            "angleThreshold" to angleThreshold,
            "overSpeedingThreshold" to overSpeedingThreshold,
            "distanceThreshold" to distanceThreshold,
            "movingTimer" to movingTimer,
            "stopTimer" to stopTimer,
            "igStatus" to igStatus,
            "isCarPowerInitialized" to isCarPowerInitialized,
            "isIgStatusReady" to isIgStatusReady
        )
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
            
            // NEW: Try to update unknown IMEI records after IMEI is set
            updateUnknownImeiRecords()
            
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
//            carPowerManager?.forceUpdateIgStatus()
            
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

    // NEW: Update all records with IMEI = "unknown" once IMEI is available
    private fun updateUnknownImeiRecords() {
        try {
            val imei = getImei()
            if (imei == "unknown" || imei.isEmpty()) return

            val db = dbHelper.writableDatabase
            val values = ContentValues().apply { put("imei", imei) }
            val updatedRows = db.update(
                "location_data",
                values,
                "imei = ?",
                arrayOf("unknown")
            )
            Log.d(TAG, "Updated $updatedRows records with new IMEI: $imei")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating unknown IMEI records: ${e.message}")
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.e(TAG, "ERROR: onTaskRemoved called - scheduling service restart")
        scheduleServiceRestart()
    }

    // NEW METHOD: Get latest reason from database
    fun getLatestReason(): String {
        try {
            val db = dbHelper.readableDatabase
            val cursor = db.query(
                "location_data",
                arrayOf("reason"),
                null,
                null,
                null,
                null,
                "createAt DESC",
                "1"
            )
            
            return if (cursor.moveToFirst()) {
                val reason = cursor.getString(cursor.getColumnIndexOrThrow("reason"))
                cursor.close()
                Log.d(TAG, "Latest reason from database: $reason")
                reason
            } else {
                cursor.close()
                Log.d(TAG, "No location data found in database")
                "Unknown"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting latest reason: ${e.message}")
            return "Unknown"
        }
    }

    // NEW METHOD: Reset reason timing tracking for testing
    fun resetReasonTimingTracking() {
        Log.d(TAG, "🔄 Resetting reason timing tracking...")
        lastIdleSyncTime = 0
        lastMoveSyncTime = 0
        lastSyncedReason = null
        Log.d(TAG, "✅ Reason timing tracking reset")
    }

    // NEW METHOD: Get reason timing status for debugging
    fun getReasonTimingStatus(): Map<String, Any> {
        val currentTime = System.currentTimeMillis()
        return mapOf(
            "lastIdleSyncTime" to lastIdleSyncTime,
            "lastMoveSyncTime" to lastMoveSyncTime,
            "lastSyncedReason" to (lastSyncedReason ?: "null"),
            "idleConsecutiveTimeout" to idleConsecutiveTimeout,
            "moveConsecutiveTimeout" to moveConsecutiveTimeout,
            "currentTime" to currentTime,
            "timeSinceLastIdle" to (if (lastIdleSyncTime > 0) currentTime - lastIdleSyncTime else 0),
            "timeSinceLastMove" to (if (lastMoveSyncTime > 0) currentTime - lastMoveSyncTime else 0)
        )
    }

    // NEW METHOD: Force update reason for ignition state change
    @SuppressLint("MissingPermission")
    private fun forceIgnitionReasonUpdate(newIgStatus: Int) {
        try {
            Log.d(TAG, "🚗 FORCING IGNITION REASON UPDATE")
            Log.d(TAG, "   - New igStatus: $newIgStatus")
            Log.d(TAG, "   - Current igStatus: $igStatus")
            
            // Update current igStatus
            val oldStatus = igStatus
            igStatus = newIgStatus
            
            // Save location with ignition reason immediately
            if (lastLocation != null) {
                val ignitionReason = calculateEnhancedReason(lastLocation!!, true, newIgStatus)
                Log.d(TAG, "🚗 Saving forced ignition reason: $ignitionReason")
                saveLocationDataWithReason(lastLocation!!, ignitionReason)
                
                // Trigger immediate sync
                syncExecutor.execute {
                    performSyncToServer()
                }
            } else {
                Log.w(TAG, "⚠️ No location available for forced ignition reason update")
                // Try to get a fresh location
                try {
                    val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
                    if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                        val freshLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                        if (freshLocation != null) {
                            val ignitionReason = calculateEnhancedReason(freshLocation, true, newIgStatus)
                            Log.d(TAG, "🚗 Saving forced ignition reason with fresh location: $ignitionReason")
                            saveLocationDataWithReason(freshLocation, ignitionReason)
                            syncExecutor.execute {
                                performSyncToServer()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error getting fresh location for forced ignition reason", e)
                }
            }
            
            Log.d(TAG, "✅ Forced ignition reason update completed")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in forced ignition reason update", e)
        }
    }

    // NEW METHOD: Test data collection and sync
    @SuppressLint("MissingPermission")
    private fun testDataCollection() {
        try {
            Log.d(TAG, "🧪 TESTING DATA COLLECTION AND SYNC")
            
            // Check current status
            Log.d(TAG, "📊 Current Status:")
            Log.d(TAG, "   - igStatus: $igStatus")
            Log.d(TAG, "   - isIgStatusReady: $isIgStatusReady")
            Log.d(TAG, "   - isCarPowerInitialized: $isCarPowerInitialized")
            Log.d(TAG, "   - Location client initialized: ${::fusedLocationClient.isInitialized}")
            
            // Check database stats
            val db = dbHelper.readableDatabase
            val totalCursor = db.rawQuery("SELECT COUNT(*) FROM location_data", null)
            totalCursor.moveToFirst()
            val totalRecords = totalCursor.getInt(0)
            totalCursor.close()
            
            val unsyncedCursor = db.rawQuery("SELECT COUNT(*) FROM location_data WHERE sync_status = 0", null)
            unsyncedCursor.moveToFirst()
            val unsyncedRecords = unsyncedCursor.getInt(0)
            unsyncedCursor.close()
            
            Log.d(TAG, "📊 Database Stats:")
            Log.d(TAG, "   - Total records: $totalRecords")
            Log.d(TAG, "   - Unsynced records: $unsyncedRecords")
            
            // Try to get current location and save it
            if (::fusedLocationClient.isInitialized) {
                Log.d(TAG, "📍 Requesting current location for test...")
                
                fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                    if (location != null) {
                        Log.d(TAG, "✅ Test location received: ${location.latitude}, ${location.longitude}")
                        
                        // Save test location
                        val reason = calculateEnhancedReason(location, false, -1)
                        saveLocationDataWithReason(location, reason)
                        
                        Log.d(TAG, "✅ Test location saved with reason: $reason")
                        
                        // Trigger immediate sync
                        syncExecutor.execute {
                            Log.d(TAG, "🔄 Triggering immediate sync for test data")
                            performSyncToServer()
                        }
                        
                    } else {
                        Log.w(TAG, "⚠️ No test location available")
                    }
                }.addOnFailureListener { e ->
                    Log.e(TAG, "❌ Error getting test location: $e")
                }
            } else {
                Log.e(TAG, "❌ Location client not initialized")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in test data collection: $e")
        }
    }

    // NEW METHOD: Ensure service persistence and continuous data collection
    private fun ensureServicePersistence() {
        try {
            Log.d(TAG, "🔒 Ensuring service persistence and continuous data collection")
            
            // Set a flag to indicate service should continue running
            val prefs = getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
            prefs.edit().apply {
                putBoolean("flutter.service_should_continue", true)
                putLong("flutter.service_start_time", System.currentTimeMillis())
                putString("flutter.service_status", "persistent")
                apply()
            }
            
            // Ensure location updates are active
            if (::fusedLocationClient.isInitialized) {
                Log.d(TAG, "✅ Location client is initialized")
            } else {
                Log.w(TAG, "⚠️ Location client not initialized - reinitializing")
                fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
            }
            
            // Ensure CarPowerManager is working
            if (isCarPowerInitialized && carPowerManager != null) {
                Log.d(TAG, "✅ CarPowerManager is initialized")
            } else {
                Log.w(TAG, "⚠️ CarPowerManager not initialized - reinitializing")
//                initializeCarPowerManager()
            }
            
            // Ensure wake locks are held
            if (wakeLock?.isHeld == true) {
                Log.d(TAG, "✅ Wake lock is held")
            } else {
                Log.w(TAG, "⚠️ Wake lock not held - reacquiring")
                acquireWakeLock()
            }
            
            // Ensure sync executor is running
            if (!syncExecutor.isShutdown) {
                Log.d(TAG, "✅ Sync executor is running")
            } else {
                Log.w(TAG, "⚠️ Sync executor is shutdown - recreating")
                syncExecutor = Executors.newSingleThreadScheduledExecutor()
                startPeriodicSync()
            }
            
            // Schedule periodic health checks
            schedulePeriodicHealthChecks()
            
            Log.d(TAG, "✅ Service persistence ensured")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error ensuring service persistence: $e")
        }
    }

    // NEW METHOD: Schedule periodic health checks
    private fun schedulePeriodicHealthChecks() {
        try {
            Log.d(TAG, "🏥 Scheduling periodic health checks")
            
            // Health check every 30 seconds
            syncExecutor.scheduleAtFixedRate({
                try {
                    Log.d(TAG, "🏥 PERIODIC HEALTH CHECK")
                    
                    // Check if service is still in foreground
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val notificationManager = getSystemService(NotificationManager::class.java)
                        val activeNotifications = notificationManager.activeNotifications
                        val isInForeground = activeNotifications.any { it.id == NOTIFICATION_ID }
                        
                        if (!isInForeground) {
                            Log.w(TAG, "⚠️ Service not in foreground - restarting foreground")
                            startForeground(NOTIFICATION_ID, createNotification())
                        }
                    }
                    
                    // Check if location updates are active
                    if (::fusedLocationClient.isInitialized) {
                        Log.d(TAG, "✅ Location client is initialized")
                    } else {
                        Log.w(TAG, "⚠️ Location client not initialized - reinitializing")
                        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
                    }
                    
                    // Check if CarPowerManager is working
                    if (isCarPowerInitialized && carPowerManager != null) {
                        Log.d(TAG, "✅ CarPowerManager is initialized")
                    } else {
                        Log.w(TAG, "⚠️ CarPowerManager not initialized - reinitializing")
//                        initializeCarPowerManager()
                    }
                    
                    // Check wake locks
                    if (wakeLock?.isHeld == true) {
                        Log.d(TAG, "✅ Wake lock is held")
                    } else {
                        Log.w(TAG, "⚠️ Wake lock not held - reacquiring")
                        acquireWakeLock()
                    }
                    
                    // Check if sync executor is running
                    if (!syncExecutor.isShutdown) {
                        Log.d(TAG, "✅ Sync executor is running")
                    } else {
                        Log.w(TAG, "⚠️ Sync executor is shutdown - recreating")
                        syncExecutor = Executors.newSingleThreadScheduledExecutor()
                        startPeriodicSync()
                    }
                    
                    // NEW: Detect if app was cleared from background
                    detectAppClearedAndEnsureService()
                    
                    // NEW: Check configuration integrity periodically
                    checkAndFixConfiguration()
                    
                    Log.d(TAG, "✅ Health check completed")
                    
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error in health check: $e")
                }
            }, 30, 30, TimeUnit.SECONDS)
            
            // NEW: Add periodic data collection test (every 2 minutes)
            syncExecutor.scheduleAtFixedRate({
                try {
                    Log.d(TAG, "🧪 PERIODIC DATA COLLECTION TEST")
                    
                    // Force a location collection test
                    testDataCollection()
                    
                    Log.d(TAG, "✅ Periodic data collection test completed")
                    
                } catch (e: Exception) {
                    Log.e(TAG, "ERROR: Exception in periodic data collection test", e)
                }
            }, 120, 120, TimeUnit.SECONDS)
            
            // NEW: Add periodic service health check (every 30 seconds)
            syncExecutor.scheduleAtFixedRate({
                try {
                    Log.d(TAG, "🏥 PERIODIC SERVICE HEALTH CHECK")
                    
                    // Check if service is still in foreground
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val notificationManager = getSystemService(NotificationManager::class.java)
                        val activeNotifications = notificationManager.activeNotifications
                        val isInForeground = activeNotifications.any { it.id == NOTIFICATION_ID }
                        
                        if (!isInForeground) {
                            Log.w(TAG, "⚠️ Service not in foreground - restarting foreground")
                            startForeground(NOTIFICATION_ID, createNotification())
                        } else {
                            Log.d(TAG, "✅ Service is in foreground")
                        }
                    }
                    
                    // Check if location updates are active
                    if (::fusedLocationClient.isInitialized) {
                        Log.d(TAG, "✅ Location client is initialized")
                    } else {
                        Log.w(TAG, "⚠️ Location client not initialized - reinitializing")
                        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
                    }
                    
                    // Check if CarPowerManager is working
                    if (isCarPowerInitialized && carPowerManager != null) {
                        Log.d(TAG, "✅ CarPowerManager is initialized")
                    } else {
                        Log.w(TAG, "⚠️ CarPowerManager not initialized - reinitializing")
//                        initializeCarPowerManager()
                    }
                    
                    // Check wake locks
                    if (wakeLock?.isHeld == true) {
                        Log.d(TAG, "✅ Wake lock is held")
                    } else {
                        Log.w(TAG, "⚠️ Wake lock not held - reacquiring")
                        acquireWakeLock()
                    }
                    
                    // Check if sync executor is running
                    if (!syncExecutor.isShutdown) {
                        Log.d(TAG, "✅ Sync executor is running")
                    } else {
                        Log.w(TAG, "⚠️ Sync executor is shutdown - recreating")
                        syncExecutor = Executors.newSingleThreadScheduledExecutor()
                        startPeriodicSync()
                    }
                    
                    // NEW: Detect if app was cleared from background
                    detectAppClearedAndEnsureService()
                    
                    // NEW: Check configuration integrity periodically
                    checkAndFixConfiguration()
                    
                    Log.d(TAG, "✅ Health check completed")
                    
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error in health check: $e")
                }
            }, 30, 30, TimeUnit.SECONDS)
            
            // NEW: Add periodic service persistence check (every 2 minutes)
            syncExecutor.scheduleAtFixedRate({
                try {
                    Log.d(TAG, "🔒 PERIODIC SERVICE PERSISTENCE CHECK")
                    
                    // Check if service needs restart flag is set
                    val prefs = getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
                    val needsRestart = prefs.getBoolean("flutter.service_needs_restart", false)
                    
                    if (needsRestart) {
                        Log.w(TAG, "⚠️ Service restart flag detected - clearing flag")
                        prefs.edit().remove("flutter.service_needs_restart").apply()
                    }
                    
                    // Ensure service is properly registered for auto-start
                    ensureAutoStartRegistration()
                    
                    Log.d(TAG, "✅ Service persistence check completed")
                    
                } catch (e: Exception) {
                    Log.e(TAG, "ERROR: Exception in periodic service persistence check", e)
                }
            }, 120, 120, TimeUnit.SECONDS)
            
            Log.e(TAG, "ERROR: Periodic sync started with uploadTimer: ${uploadTimer}s")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error scheduling health checks: $e")
        }
    }

    // NEW METHOD: Detect app cleared from background and ensure service continues
    private fun detectAppClearedAndEnsureService() {
        try {
            Log.d(TAG, "🔍 Detecting if app was cleared from background")
            
            // Check if MainActivity is still running
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val runningTasks = activityManager.getRunningTasks(10)
            val isMainActivityRunning = runningTasks.any { task ->
                task.topActivity?.className?.contains("MainActivity") == true
            }
            
            Log.d(TAG, "📱 MainActivity running: $isMainActivityRunning")
            
            if (!isMainActivityRunning) {
                Log.w(TAG, "⚠️ App cleared from background - ensuring service continues")
                
                // Set a flag to indicate app was cleared
                val prefs = getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
                prefs.edit().apply {
                    putBoolean("flutter.app_cleared_from_background", true)
                    putLong("flutter.app_cleared_time", System.currentTimeMillis())
                    apply()
                }
                
                // Ensure service continues running
                ensureServicePersistence()
                
                // Force a location collection test
                testDataCollection()
                
                Log.d(TAG, "✅ Service ensured to continue after app cleared")
            } else {
                Log.d(TAG, "✅ App still in foreground")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error detecting app cleared: $e")
        }
    }

    // NEW METHOD: Ensure auto-start registration
    private fun ensureAutoStartRegistration() {
        try {
            Log.d(TAG, "🔒 Ensuring auto-start registration...")
            
            // Check if we have all necessary permissions
            val hasBootPermission = checkSelfPermission(android.Manifest.permission.RECEIVE_BOOT_COMPLETED) == PackageManager.PERMISSION_GRANTED
            val hasWakeLockPermission = checkSelfPermission(android.Manifest.permission.WAKE_LOCK) == PackageManager.PERMISSION_GRANTED
            val hasForegroundPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                checkSelfPermission(android.Manifest.permission.FOREGROUND_SERVICE) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
            
            Log.d(TAG, "📋 Permission Status:")
            Log.d(TAG, "   - RECEIVE_BOOT_COMPLETED: $hasBootPermission")
            Log.d(TAG, "   - WAKE_LOCK: $hasWakeLockPermission")
            Log.d(TAG, "   - FOREGROUND_SERVICE: $hasForegroundPermission")
            
            if (!hasBootPermission) {
                Log.w(TAG, "⚠️ Missing RECEIVE_BOOT_COMPLETED permission")
            }
            
            if (!hasWakeLockPermission) {
                Log.w(TAG, "⚠️ Missing WAKE_LOCK permission")
            }
            
            // Set a flag to indicate service is running and should auto-restart
            val prefs = getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
            prefs.edit().apply {
                putBoolean("flutter.service_should_auto_start", true)
                putLong("flutter.service_last_alive", System.currentTimeMillis())
                putString("flutter.service_status", "running")
                apply()
            }
            
            Log.d(TAG, "✅ Auto-start registration ensured")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error ensuring auto-start registration: $e")
        }
    }

    // NEW METHOD: Force reload configuration and restart services
    private fun forceReloadConfigurationAndRestart() {
        try {
            Log.d(TAG, "🔄 FORCE RELOADING CONFIGURATION AND RESTARTING SERVICES")
            
            // Load configuration
            loadConfiguration()
            
            // Restart location updates with new GPS timer
            restartLocationUpdatesWithNewInterval()
            
            // Restart sync with new upload timer
            restartPeriodicSyncWithNewInterval()
            
            Log.d(TAG, "✅ Configuration reloaded and services restarted")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error force reloading configuration: ${e.message}")
        }
    }

    // NEW METHOD: Check and fix configuration issues
    private fun checkAndFixConfiguration() {
        try {
            Log.d(TAG, "🔍 CHECKING CONFIGURATION INTEGRITY")
            
            // Check if configuration values are reasonable
            val isGpsTimerValid = gpsTimer in 1..60
            val isUploadTimerValid = uploadTimer in 5..300
            val isDistanceThresholdValid = distanceThreshold in 10f..10000f
            val isOverSpeedingThresholdValid = overSpeedingThreshold in 10f..200f
            
            Log.d(TAG, "📊 Configuration Validation:")
            Log.d(TAG, "   - GPS Timer: $gpsTimer (valid: $isGpsTimerValid)")
            Log.d(TAG, "   - Upload Timer: $uploadTimer (valid: $isUploadTimerValid)")
            Log.d(TAG, "   - Distance Threshold: $distanceThreshold (valid: $isDistanceThresholdValid)")
            Log.d(TAG, "   - Over Speeding Threshold: $overSpeedingThreshold (valid: $isOverSpeedingThresholdValid)")
            
            // If any configuration is invalid, force reload
            if (!isGpsTimerValid || !isUploadTimerValid || !isDistanceThresholdValid || !isOverSpeedingThresholdValid) {
                Log.w(TAG, "⚠️ Invalid configuration detected - forcing reload")
                forceReloadConfigurationAndRestart()
                return
            }
            
            // Check if location request interval matches GPS timer
            if (::fusedLocationClient.isInitialized) {
                Log.d(TAG, "📍 Checking location request interval...")
                // Force restart location updates to ensure correct interval
                restartLocationUpdatesWithNewInterval()
            }
            
            Log.d(TAG, "✅ Configuration integrity check completed")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error checking configuration integrity: $e")
        }
    }

    // NEW METHOD: Enhanced service persistence for long intervals
    private fun ensureServicePersistenceForLongIntervals() {
        try {
            Log.d(TAG, "🔒 ENHANCED SERVICE PERSISTENCE FOR LONG INTERVALS")
            
            // Set multiple persistence flags
            val prefs = getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
            prefs.edit().apply {
                putBoolean("flutter.service_should_continue", true)
                putBoolean("flutter.service_persistent", true)
                putBoolean("flutter.service_auto_restart", true)
                putLong("flutter.service_start_time", System.currentTimeMillis())
                putLong("flutter.service_last_alive", System.currentTimeMillis())
                putString("flutter.service_status", "persistent_long_interval")
                putInt("flutter.service_restart_count", 0)
                apply()
            }
            
            // Schedule multiple restart mechanisms
            scheduleMultipleRestartMechanisms()
            
            // Ensure wake locks are held
            acquireWakeLock()
            
            // Ensure foreground service is active
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForeground(NOTIFICATION_ID, createNotification())
            }
            
            Log.d(TAG, "✅ Enhanced service persistence configured")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error ensuring enhanced service persistence: $e")
        }
    }

    // NEW METHOD: Schedule multiple restart mechanisms
    private fun scheduleMultipleRestartMechanisms() {
        try {
            Log.d(TAG, "🔄 SCHEDULING MULTIPLE RESTART MECHANISMS")
            
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            
            // Mechanism 1: Immediate restart (5 seconds)
            val immediateIntent = Intent(this, BackgroundService::class.java).apply {
                putExtra("started_by", "immediate_restart")
                putExtra("auto_started", true)
                putExtra("background_only", true)
            }
            val immediatePendingIntent = PendingIntent.getService(
                this, 997, immediateIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            // Mechanism 2: Backup restart (30 seconds)
            val backupIntent = Intent(this, BackgroundService::class.java).apply {
                putExtra("started_by", "backup_restart")
                putExtra("auto_started", true)
                putExtra("background_only", true)
            }
            val backupPendingIntent = PendingIntent.getService(
                this, 998, backupIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            // Mechanism 3: Long interval restart (5 minutes)
            val longIntervalIntent = Intent(this, BackgroundService::class.java).apply {
                putExtra("started_by", "long_interval_restart")
                putExtra("auto_started", true)
                putExtra("background_only", true)
            }
            val longIntervalPendingIntent = PendingIntent.getService(
                this, 999, longIntervalIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            // Schedule all mechanisms
            val currentTime = SystemClock.elapsedRealtime()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    currentTime + 5000, // 5 seconds
                    immediatePendingIntent
                )
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    currentTime + 30000, // 30 seconds
                    backupPendingIntent
                )
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    currentTime + 300000, // 5 minutes
                    longIntervalPendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    currentTime + 5000,
                    immediatePendingIntent
                )
                alarmManager.setExact(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    currentTime + 30000,
                    backupPendingIntent
                )
                alarmManager.setExact(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    currentTime + 300000,
                    longIntervalPendingIntent
                )
            }

            Log.d(TAG, "✅ Multiple restart mechanisms scheduled")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error scheduling multiple restart mechanisms: $e")
        }
    }

    // NEW: Get ignition state tracking status for debugging
    fun getIgnitionStateTrackingStatus(): Map<String, Any> {
        val status = mutableMapOf<String, Any>()
        
        status["currentIgStatus"] = igStatus
        status["previousIgnitionState"] = previousIgnitionState
        status["ignitionOnDetected"] = ignitionOnDetected
        status["ignitionOffDetected"] = ignitionOffDetected
        status["shouldSendData"] = shouldSendData
        status["pendingIgnitionOnReason"] = pendingIgnitionOnReason
        status["pendingIgnitionOffReason"] = pendingIgnitionOffReason
        status["lastIgnitionChangeTime"] = lastIgnitionChangeTime
        status["ignitionStateChangeBufferSize"] = ignitionStateChangeBuffer.size
        status["lastProcessedIgnitionChange"] = lastProcessedIgnitionChange
        
        // Add timing information
        status["lastIdleSyncTime"] = lastIdleSyncTime
        status["lastMoveSyncTime"] = lastMoveSyncTime
        status["lastOverSpeedingSyncTime"] = lastOverSpeedingSyncTime
        status["idleConsecutiveTimeout"] = idleConsecutiveTimeout
        status["moveConsecutiveTimeout"] = moveConsecutiveTimeout
        status["overSpeedingConsecutiveTimeout"] = overSpeedingConsecutiveTimeout
        
        if (ignitionStateChangeBuffer.isNotEmpty()) {
            val lastChange = ignitionStateChangeBuffer.last()
            status["lastBufferedChange"] = "${lastChange.first} at ${lastChange.second}"
        }
        
        Log.e(TAG, "ERROR: 📊 IGNITION STATE TRACKING STATUS:")
        status.forEach { (key, value) ->
            Log.e(TAG, "ERROR:    - $key: $value")
        }
        
        return status
    }

    // NEW: Enhanced ignition state change handling
    @SuppressLint("MissingPermission")
    private fun handleIgnitionStateChange(oldStatus: Int, newStatus: Int) {
        try {
            val currentTime = System.currentTimeMillis()
            val oldStatusName = if (oldStatus == 1) "ACC_ON" else "ACC_OFF"
            val newStatusName = if (newStatus == 1) "ACC_ON" else "ACC_OFF"
            
            // NEW: Debouncing to prevent duplicate ignition events
            val timeSinceLastChange = currentTime - lastIgnitionChangeTime
            if (timeSinceLastChange < 2000) { // 2 second debounce
                Log.e(TAG, "ERROR: 🚫 IGNITION EVENT DEBOUNCED")
                Log.e(TAG, "ERROR:    - Time since last change: ${timeSinceLastChange}ms")
                Log.e(TAG, "ERROR:    - Debounce threshold: 2000ms")
                Log.e(TAG, "ERROR:    - Skipping duplicate ignition event")
                return
            }
            
            Log.e(TAG, "ERROR: 🚗 ENHANCED IGNITION STATE CHANGE HANDLING")
            Log.e(TAG, "ERROR:    - Previous state: $previousIgnitionState")
            Log.e(TAG, "ERROR:    - Old status: $oldStatus ($oldStatusName)")
            Log.e(TAG, "ERROR:    - New status: $newStatus ($newStatusName)")
            Log.e(TAG, "ERROR:    - Time since last change: ${timeSinceLastChange}ms")
            
            // NEW: Check for duplicate ignition change
            val currentChange = "${oldStatus}_${newStatus}"
            if (currentChange == lastProcessedIgnitionChange) {
                Log.e(TAG, "ERROR: 🚫 DUPLICATE IGNITION CHANGE DETECTED")
                Log.e(TAG, "ERROR:    - Current change: $currentChange")
                Log.e(TAG, "ERROR:    - Last processed: $lastProcessedIgnitionChange")
                Log.e(TAG, "ERROR:    - Skipping duplicate ignition change")
                return
            }
            
            // Update tracking variables
            previousIgnitionState = oldStatus
            lastIgnitionChangeTime = currentTime
            lastProcessedIgnitionChange = currentChange
            
            // Handle ignition ON transition (0 -> 1)
            if (oldStatus == 0 && newStatus == 1) {
                Log.e(TAG, "ERROR: 🚗 IGNITION ON DETECTED (0 -> 1)")
                ignitionOnDetected = true
                ignitionOffDetected = false
                shouldSendData = true
                pendingIgnitionOnReason = true
                pendingIgnitionOffReason = false
                
                // Buffer the ignition on event
                ignitionStateChangeBuffer.add(Pair("Ignition On", currentTime))
                
                Log.e(TAG, "ERROR:    - Data transmission ENABLED")
                Log.e(TAG, "ERROR:    - Pending ignition on reason: true")
                Log.e(TAG, "ERROR:    - Next location will have 'Ignition On' reason")
                
                // NEW: Save ignition ON record immediately if location is available
                if (lastLocation != null) {
                    Log.e(TAG, "ERROR:    - Saving immediate ignition ON record")
                    saveLocationDataWithReason(lastLocation!!, "Ignition On")
                    syncExecutor.execute {
                        performSyncToServer()
                    }
                } else {
                    Log.e(TAG, "ERROR:    - No location available for ignition ON record")
                    // Try to get a fresh location for ignition ON
                    try {
                        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
                        if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                            val freshLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                            if (freshLocation != null) {
                                Log.e(TAG, "ERROR:    - Saving ignition ON record with fresh location")
                                saveLocationDataWithReason(freshLocation, "Ignition On")
                                syncExecutor.execute {
                                    performSyncToServer()
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "ERROR: ❌ Error getting fresh location for ignition ON: ${e.message}")
                    }
                }
                
            }
            // Handle ignition OFF transition (1 -> 0)
            else if (oldStatus == 1 && newStatus == 0) {
                Log.e(TAG, "ERROR: 🚗 IGNITION OFF DETECTED (1 -> 0)")
                ignitionOffDetected = true
                ignitionOnDetected = false
                shouldSendData = false
                pendingIgnitionOffReason = true
                pendingIgnitionOnReason = false
                
                // Buffer the ignition off event
                ignitionStateChangeBuffer.add(Pair("Ignition Off", currentTime))
                
                Log.e(TAG, "ERROR:    - Data transmission DISABLED")
                Log.e(TAG, "ERROR:    - Pending ignition off reason: true")
                Log.e(TAG, "ERROR:    - Next location will have 'Ignition Off' reason")
                
                // Immediately save ignition off record if location is available
                if (lastLocation != null) {
                    Log.e(TAG, "ERROR:    - Saving immediate ignition off record")
                    saveLocationDataWithReason(lastLocation!!, "Ignition Off")
                    syncExecutor.execute {
                        performSyncToServer()
                    }
                }
            }
            
            // Log the state change buffer
            Log.e(TAG, "ERROR:    - Ignition state change buffer size: ${ignitionStateChangeBuffer.size}")
            if (ignitionStateChangeBuffer.isNotEmpty()) {
                val lastChange = ignitionStateChangeBuffer.last()
                Log.e(TAG, "ERROR:    - Last buffered change: ${lastChange.first} at ${lastChange.second}")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "ERROR: ❌ Exception in handleIgnitionStateChange: ${e.message}")
        }
    }

    // NEW: Manually trigger ignition state change for testing
    fun triggerIgnitionStateChange(newStatus: Int) {
        try {
            Log.e(TAG, "ERROR: 🧪 MANUALLY TRIGGERING IGNITION STATE CHANGE")
            Log.e(TAG, "ERROR:    - Current igStatus: $igStatus")
            Log.e(TAG, "ERROR:    - New status: $newStatus")
            
            val oldStatus = igStatus
            igStatus = newStatus
            
            // Trigger the enhanced ignition state change handling
            handleIgnitionStateChange(oldStatus, newStatus)
            
            // Update notification
            val isAccOn = (newStatus == 1)
            updateNotificationWithAccState(isAccOn)
            
            Log.e(TAG, "ERROR: ✅ Manual ignition state change completed")
            
        } catch (e: Exception) {
            Log.e(TAG, "ERROR: ❌ Exception in manual ignition state change: ${e.message}")
        }
    }

    // NEW: Clear ignition state tracking for testing
    fun clearIgnitionStateTracking() {
        try {
            Log.e(TAG, "ERROR: 🧹 CLEARING IGNITION STATE TRACKING")
            
            previousIgnitionState = 0
            ignitionOnDetected = false
            ignitionOffDetected = false
            lastIgnitionChangeTime = 0L
            shouldSendData = true
            pendingIgnitionOnReason = false
            pendingIgnitionOffReason = false
            ignitionStateChangeBuffer.clear()
            lastProcessedIgnitionChange = ""
            
            // Clear timing variables
            lastIdleSyncTime = 0L
            lastMoveSyncTime = 0L
            lastOverSpeedingSyncTime = 0L
            
            Log.e(TAG, "ERROR: ✅ Ignition state tracking cleared")
            
        } catch (e: Exception) {
            Log.e(TAG, "ERROR: ❌ Exception clearing ignition state tracking: ${e.message}")
        }
    }

    // NEW: Get detailed timing status for debugging
    fun getTimingStatus(): Map<String, Any> {
        val currentTime = System.currentTimeMillis()
        val status = mutableMapOf<String, Any>()
        
        // Idle timing
        val idleTimeSinceLastSync = if (lastIdleSyncTime > 0) currentTime - lastIdleSyncTime else 0L
        status["idle"] = mapOf(
            "lastSyncTime" to lastIdleSyncTime,
            "timeSinceLastSync" to idleTimeSinceLastSync,
            "timeSinceLastSyncSeconds" to (idleTimeSinceLastSync / 1000),
            "timeout" to idleConsecutiveTimeout,
            "timeoutSeconds" to (idleConsecutiveTimeout / 1000),
            "canSave" to (idleTimeSinceLastSync >= idleConsecutiveTimeout || lastIdleSyncTime == 0L)
        )
        
        // Move timing
        val moveTimeSinceLastSync = if (lastMoveSyncTime > 0) currentTime - lastMoveSyncTime else 0L
        status["move"] = mapOf(
            "lastSyncTime" to lastMoveSyncTime,
            "timeSinceLastSync" to moveTimeSinceLastSync,
            "timeSinceLastSyncSeconds" to (moveTimeSinceLastSync / 1000),
            "timeout" to moveConsecutiveTimeout,
            "timeoutSeconds" to (moveConsecutiveTimeout / 1000),
            "canSave" to (moveTimeSinceLastSync >= moveConsecutiveTimeout || lastMoveSyncTime == 0L)
        )
        
        // Over Speeding timing
        val overSpeedingTimeSinceLastSync = if (lastOverSpeedingSyncTime > 0) currentTime - lastOverSpeedingSyncTime else 0L
        status["overSpeeding"] = mapOf(
            "lastSyncTime" to lastOverSpeedingSyncTime,
            "timeSinceLastSync" to overSpeedingTimeSinceLastSync,
            "timeSinceLastSyncSeconds" to (overSpeedingTimeSinceLastSync / 1000),
            "timeout" to overSpeedingConsecutiveTimeout,
            "timeoutSeconds" to (overSpeedingConsecutiveTimeout / 1000),
            "canSave" to (overSpeedingTimeSinceLastSync >= overSpeedingConsecutiveTimeout || lastOverSpeedingSyncTime == 0L)
        )
        
        Log.e(TAG, "ERROR: 📊 TIMING STATUS:")
        status.forEach { (key, value) ->
            Log.e(TAG, "ERROR:    - $key: $value")
        }
        
        return status
    }


}