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

    private var igStatus = 0
    private var isCarPowerAvailable = false
    private var serviceStartAttempts = 0
    private val MAX_START_ATTEMPTS = 3

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
            
            db.execSQL("CREATE INDEX idx_sync_status ON location_data(sync_status)")
            db.execSQL("CREATE INDEX idx_created_at ON location_data(created_at)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 3) {
                try {
                    db.execSQL("CREATE INDEX IF NOT EXISTS idx_sync_status ON location_data(sync_status)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS idx_created_at ON location_data(created_at)")
                } catch (e: Exception) {
                    Log.e(TAG, "Error creating indexes: ${e.message}")
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
                    
                    Log.d(TAG, "Deleted $recordsToDelete old synced records to maintain 1000 record limit")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error maintaining record limit: ${e.message}")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "=== BACKGROUND SERVICE CREATED (INDEPENDENT) ===")
        Log.d(TAG, "Process ID: ${android.os.Process.myPid()}")
        Log.d(TAG, "Thread: ${Thread.currentThread().name}")
        
        try {
            dbHelper = LocationDatabaseHelper(this)
            acquireWakeLock()
            createNotificationChannel()
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
            
            // CRITICAL: Initialize CarPowerManager INSIDE the service
            initializeServiceOwnedCarPowerManager()
            
            // CRITICAL: Ensure IMEI is available
            ensureImeiAvailable()
            
            val filter = IntentFilter(ACTION_RESTART_SERVICE)
            registerReceiver(restartReceiver, filter)
            
            loadConfiguration()
            setupLocationUpdates()
            startPeriodicSync()
            
            Log.d(TAG, "✅ Service onCreate completed successfully (INDEPENDENT)")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in service onCreate", e)
        }
    }

    /**
     * CRITICAL: Initialize CarPowerManager INSIDE the BackgroundService
     */
    private fun initializeServiceOwnedCarPowerManager() {
        try {
            Log.d(TAG, "=== INITIALIZING SERVICE-OWNED CAR POWER MANAGER ===")
            
            carPowerManager = CarPowerManager(this)
            
            carPowerManager?.setAccStateCallback { isAccOn ->
                val newIgStatus = if (isAccOn) 1 else 0
                
                if (newIgStatus != igStatus) {
                    val oldStatus = igStatus
                    igStatus = newIgStatus
                    Log.d(TAG, "🚗 ACC state changed from $oldStatus to $igStatus (Service-owned)")
                    
                    storeAccStateForFlutter(newIgStatus)
                    updateNotificationWithAccState(isAccOn)
                }
            }

            carPowerManager?.setSleepStateCallback { isSleeping ->
                Log.d(TAG, "🚗 Sleep state changed: $isSleeping (Service-owned)")
                
                if (isSleeping) {
                    Log.d(TAG, "🚗 AVN entering sleep state - service handling")
                    handleSleepStateChange(true)
                } else {
                    Log.d(TAG, "🚗 AVN exiting sleep state - service handling")
                    handleSleepStateChange(false)
                }
            }
            
            carPowerManager?.initialize()
            isCarPowerInitialized = true
            
            val initialState = carPowerManager?.getCurrentAccState() ?: false
            igStatus = if (initialState) 1 else 0
            storeAccStateForFlutter(igStatus)
            
            Log.d(TAG, "✅ Service-owned CarPowerManager initialized. Initial state: $igStatus")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize service-owned CarPowerManager: ${e.message}")
            isCarPowerInitialized = false
            igStatus = 1
            storeAccStateForFlutter(igStatus)
            Log.w(TAG, "⚠️ Using default ACC state: $igStatus")
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

    private fun storeAccStateForFlutter(igStatus: Int) {
        try {
            val prefs = getSharedPreferences("tracking_prefs", Context.MODE_PRIVATE)
            prefs.edit().apply {
                putInt("current_ig_status", igStatus)
                putLong("ig_status_timestamp", System.currentTimeMillis())
                apply()
            }
            Log.d(TAG, "✅ Stored ACC state for Flutter: $igStatus")
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
        
        Log.d(TAG, "Started by: $startedBy")
        Log.d(TAG, "Auto started: $isAutoStarted")
        Log.d(TAG, "Background only: $isBackgroundOnly")
        Log.d(TAG, "Car Power Triggered: $isCarPowerTriggered")
        
        val prefs = getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
        val wasSleeping = prefs.getBoolean("flutter.is_sleeping", false)
        
        when {
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
            initializeServiceOwnedCarPowerManager()
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
                initializeServiceOwnedCarPowerManager()
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
            
            val trigger = intent?.getStringExtra("started_by") ?: "unknown"
            val igStatus = intent?.getIntExtra("current_ig_status", 0) ?: 0
            val isSleeping = intent?.getBooleanExtra("is_sleeping", false) ?: false
            val isWakeUp = intent?.getBooleanExtra("wake_up_from_sleep", false) ?: false
            val isSleepKeepAlive = intent?.getBooleanExtra("sleep_keep_alive", false) ?: false
            
            this.igStatus = igStatus
            
            when {
                isWakeUp -> {
                    Log.d(TAG, "🚗 Car power wake-up - resuming background operation")
                    handleWakeUpFromSleep(true)
                    showCarPowerWakeUpNotification(trigger)
                }
                isSleepKeepAlive -> {
                    Log.d(TAG, "🚗 Car power sleep - maintaining service")
                    handleSleepKeepAlive()
                    showCarPowerSleepNotification()
                }
                else -> {
                    Log.d(TAG, "🚗 Car power normal start - background mode")
                    handleCarPowerNormalStart(trigger)
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling car power start", e)
        }
    }

    private fun handleCarPowerNormalStart(trigger: String) {
        try {
            Log.d(TAG, "=== CAR POWER NORMAL START ===")
            
            refreshWakeLocks()
            startLocationUpdates()
            startPeriodicSync()
            showCarPowerNotification(trigger)
            
            Log.d(TAG, "✅ Car power normal start completed")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in car power normal start", e)
        }
    }

    private fun showCarPowerWakeUpNotification(trigger: String) {
        try {
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("GPS Tracking - AVN Wake Up")
                .setContentText("Service resumed after AVN wake-up")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setAutoCancel(false)
                .build()

            startForeground(NOTIFICATION_ID, notification)
            Log.d(TAG, "✅ Car power wake-up notification shown")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error showing car power wake-up notification", e)
        }
    }

    private fun showCarPowerSleepNotification() {
        try {
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("GPS Tracking - AVN Sleep")
                .setContentText("Service maintained during AVN sleep")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .build()

            startForeground(NOTIFICATION_ID, notification)
            Log.d(TAG, "✅ Car power sleep notification shown")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error showing car power sleep notification", e)
        }
    }

    private fun showCarPowerNotification(trigger: String) {
        try {
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("GPS Tracking - Car Power")
                .setContentText("Started by car power system")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setAutoCancel(false)
                .build()

            startForeground(NOTIFICATION_ID, notification)
            Log.d(TAG, "✅ Car power notification shown")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error showing car power notification", e)
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
            
            Log.d(TAG, "Periodic sync started")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting periodic sync", e)
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
            val currentTime = System.currentTimeMillis()
            val imei = getImei()
            val reason = calculateEnhancedReason(location)
            
            var fixedSpeed = location.speed * 3.6f
            if (fixedSpeed < 0) fixedSpeed = 0f
            
            if (imei.isEmpty() || imei == "unknown") {
                Log.e(TAG, "❌ Cannot save location without valid IMEI!")
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
                put("gmtSettings", "GMT+${java.time.ZoneId.systemDefault().rules.getOffset(java.time.Instant.now()).totalSeconds / 3600}:00")
                put("igStatus", igStatus)
                put("localPrimaryId", currentTime % 100000)
                put("name", Build.MODEL)
                put("phoneNo", "unknown")
                put("provider", "fused")
                put("reason", reason)
                put("versionNo", "v ${Build.VERSION.RELEASE}")
                put("sync_status", 0)
                put("created_at", currentTime)
            }

            val db = dbHelper.writableDatabase
            val id = db.insert("location_data", null, values)
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
}