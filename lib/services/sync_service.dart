import 'dart:async';
import 'dart:io';
import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:http/http.dart' as http;
import 'package:intl/intl.dart';
import 'dart:convert';
import 'database_helper.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:package_info_plus/package_info_plus.dart';
import 'package:flutter/services.dart';
// REMOVED: import 'package:tracking_world/services/car_power_service.dart';

class SyncService {
  static final SyncService _instance = SyncService._internal();
  final DatabaseHelper _dbHelper = DatabaseHelper();
  // REMOVED: final CarPowerService _carPowerService = CarPowerService();
  final _connectivity = Connectivity();
  Timer? _syncTimer;
  bool _isSyncing = false;
  String? _lastError;
  int _retryCount = 0;
  static const String serverUrl = 'http://twca.trackingworld.com.pk:3000/api/location';
  int _syncIntervalSeconds = 30; // Default sync interval
  static const int maxRetries = 3;
  static const Duration defaultSyncInterval = Duration(minutes: 1);
  static const Duration retryDelay = Duration(seconds: 30);
  int _batchSize = 50; // Sync in smaller batches
  
  // NEW: Phase-based retry logic for server downtime
  int _consecutiveFailures = 0;
  int _currentPhase = 0;
  DateTime? _lastServerDownTime;
  bool _isServerDown = false;
  
  // Phase intervals in minutes
  static const List<int> _retryPhases = [5, 15, 30, 45, 60, 240, 480]; // 5min, 15min, 30min, 45min, 1hr, 4hr, 8hr
  static const int _maxPhases = 7;
  
  // REMOVED: Method channels since we're using SharedPreferences instead
  // static const MethodChannel _avnSleepChannel = MethodChannel('com.example.twtracking/avn_sleep');
  // static const MethodChannel _serviceChannel = MethodChannel('com.example.twtracking/service');

  factory SyncService() => _instance;

  SyncService._internal() {
    _initConnectivityListener();
    // REMOVED: CarPowerService callback - now handled by Kotlin BackgroundService
  }

  void _initConnectivityListener() {
    _connectivity.onConnectivityChanged.listen((ConnectivityResult result) {
      if (result != ConnectivityResult.none) {
        print('Network connection restored, starting sync...');
        _startSync();
      } else {
        print('Network connection lost');
      }
    });
  }

  Future<bool> _hasInternetConnection() async {
    try {
      final result = await InternetAddress.lookup('google.com');
      return result.isNotEmpty && result[0].rawAddress.isNotEmpty;
    } on SocketException catch (_) {
      return false;
    }
  }

  // NEW: Server health check
  Future<bool> _isServerHealthy() async {
    try {
      print('🔍 Testing server health at: ${serverUrl.replaceAll('/api/location', '/health')}');
      
      final response = await http.get(
        Uri.parse(serverUrl.replaceAll('/api/location', '/health')),
        headers: {
          'Content-Type': 'application/json',
          'User-Agent': 'TrackingWorld-Mobile-App',
        },
      ).timeout(const Duration(seconds: 10));
      
      print('Health check response: ${response.statusCode} - ${response.body}');
      
      // Accept both 200 and 404 (if health endpoint doesn't exist, try the main endpoint)
      if (response.statusCode == 200) {
        return true;
      } else if (response.statusCode == 404) {
        // Health endpoint doesn't exist, try the main endpoint
        print('Health endpoint not found, testing main endpoint...');
        return await _testMainEndpoint();
      }
      
      return false;
    } catch (e) {
      print('Server health check failed: $e');
      // If health check fails, try the main endpoint as fallback
      return await _testMainEndpoint();
    }
  }

  // NEW: Test main endpoint as fallback
  Future<bool> _testMainEndpoint() async {
    try {
      print('🔍 Testing main endpoint at: $serverUrl');
      
      final response = await http.get(
        Uri.parse(serverUrl),
        headers: {
          'Content-Type': 'application/json',
          'User-Agent': 'TrackingWorld-Mobile-App',
        },
      ).timeout(const Duration(seconds: 10));
      
      print('Main endpoint response: ${response.statusCode}');
      
      // Accept 200, 201, 404, 405 (GET not allowed but server is reachable)
      return response.statusCode == 200 || 
             response.statusCode == 201 || 
             response.statusCode == 404 || 
             response.statusCode == 405;
    } catch (e) {
      print('Main endpoint test failed: $e');
      return false;
    }
  }

  // NEW: Get current retry interval based on phase
  Duration _getCurrentRetryInterval() {
    if (_currentPhase >= _retryPhases.length) {
      return Duration(minutes: _retryPhases.last); // Use last phase (8 hours)
    }
    return Duration(minutes: _retryPhases[_currentPhase]);
  }

  // NEW: Advance to next phase
  void _advancePhase() {
    if (_currentPhase < _maxPhases - 1) {
      _currentPhase++;
      print('🔄 Advancing to retry phase $_currentPhase: ${_retryPhases[_currentPhase]} minutes');
    }
  }

  // NEW: Reset phases on successful sync
  void _resetPhases() {
    print('🔄 Resetting phases - current: phase $_currentPhase, isServerDown: $_isServerDown');
    
    if (_currentPhase > 0 || _isServerDown) {
      print('✅ Server back online! Resetting from phase $_currentPhase to 0');
      _currentPhase = 0;
      _consecutiveFailures = 0;
      _isServerDown = false;
      _lastServerDownTime = null;
      print('✅ Phase reset complete - new state: phase $_currentPhase, isServerDown: $_isServerDown');
    } else {
      print('ℹ️ Server already in normal state, no reset needed');
    }
  }

  // NEW: Handle server down scenario
  Future<void> _handleServerDown() async {
    if (!_isServerDown) {
      _isServerDown = true;
      _lastServerDownTime = DateTime.now();
      print('🚨 SERVER DOWN DETECTED - Switching to phase-based retry mode');
      
      await _dbHelper.insertExceptionLog(
        main: 'Server Down',
        details: 'Server unavailable, switching to phase $_currentPhase (${_retryPhases[_currentPhase]} min intervals)',
      );
    }
    
    _consecutiveFailures++;
    _advancePhase();
    
    final nextInterval = _getCurrentRetryInterval();
    print('⏰ Next retry in ${nextInterval.inMinutes} minutes (phase $_currentPhase)');
    
    // Log the phase change
    await _dbHelper.insertExceptionLog(
      main: 'Retry Phase Change',
      details: 'Phase $_currentPhase: ${nextInterval.inMinutes} min intervals, consecutive failures: $_consecutiveFailures',
    );
  }

  // NEW: Get prioritized unsynced data (critical data first)
  Future<List<Map<String, dynamic>>> _getPrioritizedUnsyncedData({int limit = 50}) async {
    try {
      final db = await _dbHelper.database;
      
      // Priority 1: High-speed events (>60 km/h) - most critical
      var highSpeedData = await db.rawQuery('''
        SELECT * FROM location_data 
        WHERE sync_status = 0 AND speed > 16.67 
        ORDER BY createAt ASC 
        LIMIT ?
      ''', [limit ~/ 3]);
      
      // Priority 2: Distance-based events (>1000m) - important
      var distanceData = await db.rawQuery('''
        SELECT * FROM location_data 
        WHERE sync_status = 0 AND reason = 'Distance' 
        ORDER BY createAt ASC 
        LIMIT ?
      ''', [limit ~/ 3]);
      
      // Priority 3: Regular timer-based events - normal priority
      var regularData = await db.rawQuery('''
        SELECT * FROM location_data 
        WHERE sync_status = 0 AND reason = 'Timer' 
        ORDER BY createAt ASC 
        LIMIT ?
      ''', [limit ~/ 3]);
      
      // Combine and sort by priority
      final allData = <Map<String, dynamic>>[];
      allData.addAll(highSpeedData);
      allData.addAll(distanceData);
      allData.addAll(regularData);
      
      // Remove duplicates and limit
      final uniqueData = <Map<String, dynamic>>[];
      final seenIds = <int>{};
      
      for (var data in allData) {
        if (uniqueData.length >= limit) break;
        if (!seenIds.contains(data['id'])) {
          seenIds.add(data['id']);
          uniqueData.add(data);
        }
      }
      
      print('📊 Prioritized data: ${highSpeedData.length} high-speed, ${distanceData.length} distance, ${regularData.length} regular');
      return uniqueData;
      
    } catch (e) {
      print('Error getting prioritized data: $e');
      // Fallback to regular query
      return await _dbHelper.getUnsyncedData(limit: limit);
    }
  }

  // NEW: Adaptive batch size based on server status
  int _getAdaptiveBatchSize() {
    if (_isServerDown) {
      // Smaller batches when server is down to reduce load
      return _batchSize ~/ 2;
    }
    return _batchSize;
  }

  Future<void> testServerConnectivity() async {
    print('=== TESTING SERVER CONNECTIVITY ===');
    print('Testing URL: $serverUrl');
    
    try {
      // Test basic connectivity
      final response = await http.get(
        Uri.parse(serverUrl.replaceAll('/api/location', '/health')), // Try health endpoint
        headers: {
          'Content-Type': 'application/json',
          'User-Agent': 'TrackingWorld-Mobile-App',
        },
      ).timeout(const Duration(seconds: 10));
      
      print('Health check response: ${response.statusCode}');
      print('Health check body: ${response.body}');
    } catch (e) {
      print('Health check failed: $e');
    }
    
    // Test POST to actual endpoint with dummy data
    try {
      // Get app version
      final packageInfo = await PackageInfo.fromPlatform();
      final appVersion = 'v${packageInfo.version}';
      
      final testData = {
        'latitude': 12.9716,
        'longitude': 77.5946,
        'accuracy': 5.0,
        'altitude': 448.70001220703125,
        'speed': 0.0,
        'bearing': 193.2200927734375,
        'imei': '359750106646000',
        'timestamp': DateTime.now().toIso8601String(),
        'deviceRDT': DateFormat("dd/MM/yyyy HH:mm:ss.SSS").format(DateTime.now()),
        'gmtSettings': "GMT+5:00 2024",
        'igStatus': 0, // Test with ACC OFF
        'localPrimaryId': 12345,
        'name': 'Test Device',
        'phoneNo': 'TEST123',
        'provider': 'test',
        'reason': 'Test',
        'versionNo': appVersion,
      };

      print('Testing POST with data: ${jsonEncode(testData)}');

      final response = await http.post(
        Uri.parse(serverUrl),
        headers: {
          'Content-Type': 'application/json',
          'User-Agent': 'TrackingWorld-Mobile-App',
        },
        body: jsonEncode(testData),
      ).timeout(const Duration(seconds: 30));

      print('POST test response status: ${response.statusCode}');
      print('POST test response body: ${response.body}');
      print('POST test response headers: ${response.headers}');

      if (response.statusCode == 200 || response.statusCode == 201) {
        print('✅ Server is accepting data correctly');
      } else {
        print('❌ Server rejected test data');
      }

    } catch (e) {
      print('❌ POST test failed: $e');
    }
  }

  Future<void> _startSync() async {
    if (_isSyncing) {
      print('Sync already in progress, skipping...');
      return;
    }

    _isSyncing = true;
    print('Starting sync process... (Phase: $_currentPhase, Server Down: $_isServerDown)');

    try {
      // Check internet connectivity
      if (!await _hasInternetConnection()) {
        print('No internet connection available');
        return;
      }

      // NEW: Check server health before attempting sync
      if (_isServerDown) {
        print('🔍 Checking server health before retry...');
        final isHealthy = await _isServerHealthy();
        if (isHealthy) {
          print('✅ Server is back online!');
          _resetPhases();
        } else {
          print('❌ Server still down, continuing with phase $_currentPhase');
        }
      }

      // NEW: Use prioritized data when server is down
      final adaptiveBatchSize = _getAdaptiveBatchSize();
      final unsyncedData = _isServerDown 
          ? await _getPrioritizedUnsyncedData(limit: adaptiveBatchSize)
          : await _dbHelper.getUnsyncedData(limit: adaptiveBatchSize);
          
      if (unsyncedData.isEmpty) {
        print('No unsynced data to upload');
        return;
      }

      print('Found ${unsyncedData.length} records to sync (batch size: $adaptiveBatchSize)');
      final List<int> syncedIds = [];
      int successCount = 0;
      int failureCount = 0;

      for (var data in unsyncedData) {
        try {
          // Remove the id field from data before sending
          final dataToSend = Map<String, dynamic>.from(data);
          dataToSend.remove('id');
          dataToSend.remove('sync_status');
          
          // Convert createdAt to createAt (camelCase) and format properly
          if (dataToSend.containsKey('created_at')) {
            DateTime createdAtDateTime;
            
            // Handle both int (milliseconds) and String (formatted) cases
            if (dataToSend['created_at'] is int) {
              final createdAtMillis = dataToSend['created_at'] as int;
              createdAtDateTime = DateTime.fromMillisecondsSinceEpoch(createdAtMillis);
            } else if (dataToSend['created_at'] is String) {
              // If it's already a formatted string, try to parse it
              try {
                createdAtDateTime = DateFormat("dd/MM/yyyy HH:mm:ss.SSS").parse(dataToSend['created_at'] as String);
              } catch (e) {
                // If parsing fails, use current time as fallback
                print('Warning: Could not parse created_at string: ${dataToSend['created_at']}, using current time');
                createdAtDateTime = DateTime.now();
              }
            } else {
              // Fallback to current time
              print('Warning: created_at is neither int nor String, using current time');
              createdAtDateTime = DateTime.now();
            }
            
            // Remove the snake_case key and add camelCase key
            dataToSend.remove('created_at');
            dataToSend['createAt'] = DateFormat("dd/MM/yyyy HH:mm:ss.SSS").format(createdAtDateTime);
          }

          print('=== SENDING DATA TO SERVER ===');
          print('Server URL: $serverUrl');
          print('Record ID: ${data['id']}');
          print('igStatus: ${dataToSend['igStatus']}');
          print('Data to send: ${jsonEncode(dataToSend)}');

          bool syncSuccess = false;
          // NEW: Adaptive retry logic based on server status
          final maxRetriesForRecord = _isServerDown ? 2 : maxRetries; // Fewer retries when server is down
          
          for (int retry = 0; retry < maxRetriesForRecord; retry++) {
            try {
              print('Attempt ${retry + 1}/$maxRetriesForRecord for record ${data['id']} (Phase: $_currentPhase)');
              
              final response = await http.post(
                Uri.parse(serverUrl),
                headers: {
                  'Content-Type': 'application/json',
                  'User-Agent': 'TrackingWorld-Mobile-App',
                },
                body: jsonEncode(dataToSend),
              ).timeout(const Duration(seconds: 30));

              print('Server response status: ${response.statusCode}');
              print('Server response body: ${response.body}');
              print('Server response headers: ${response.headers}');

              if (response.statusCode == 200 || response.statusCode == 201) {
                syncedIds.add(data['id']);
                successCount++;
                syncSuccess = true;
                print('✅ Successfully synced record ID: ${data['id']} with igStatus: ${dataToSend['igStatus']}');
                break;
              } else {
                print('❌ Server error for record ${data['id']}: ${response.statusCode}');
                print('Error response body: ${response.body}');
                if (retry == maxRetriesForRecord - 1) {
                  failureCount++;
                  await _dbHelper.insertExceptionLog(
                    main: 'Sync Server Error',
                    details: 'Record ID: ${data['id']}, Status: ${response.statusCode}, Body: ${response.body}, Phase: $_currentPhase',
                  );
                }
              }
            } catch (e) {
              print('❌ Network error for record ${data['id']} (attempt ${retry + 1}): $e');
              if (retry == maxRetriesForRecord - 1) {
                failureCount++;
                await _dbHelper.insertExceptionLog(
                  main: 'Sync Network Error',
                  details: 'Record ID: ${data['id']}, Error: $e, Phase: $_currentPhase',
                );
              }
              
              // NEW: Adaptive delay based on phase
              if (retry < maxRetriesForRecord - 1) {
                final delay = _isServerDown ? Duration(seconds: 5) : Duration(seconds: (retry + 1) * 2);
                await Future.delayed(delay);
              }
            }
          }

          // NEW: Continue processing other records even if this one fails
          if (!syncSuccess) {
            print('⚠️ Record ${data['id']} failed to sync, continuing with next record...');
            // Don't break - continue with other records
          }

        } catch (e) {
          print('Unexpected error syncing record ${data['id']}: $e');
          failureCount++;
          await _dbHelper.insertExceptionLog(
            main: 'Sync Unexpected Error',
            details: 'Record ID: ${data['id']}, Error: $e',
          );
          break; // Stop on unexpected errors
        }
      }

      // Mark successfully synced records
      if (syncedIds.isNotEmpty) {
        await _dbHelper.markAsSynced(syncedIds);
        print('Marked ${syncedIds.length} records as synced');
        
        // Clean up old synced data periodically
        await _dbHelper.deleteOldSyncedData(keepRecentCount: 100);
      }

      print('Sync completed: $successCount successful, $failureCount failed');

      // NEW: Handle phase transitions based on sync results
      if (failureCount > 0 && successCount == 0) {
        // All records failed - advance to next phase
        await _handleServerDown();
      } else if (successCount > 0) {
        // Some or all records succeeded - reset phases
        _resetPhases();
      }

      // Get updated stats
      final stats = await _dbHelper.getDatabaseStats();
      print('Database stats: ${stats['totalRecords']} total, ${stats['unsyncedRecords']} unsynced');

    } catch (e) {
      print('Error in sync process: $e');
      await _dbHelper.insertExceptionLog(
        main: 'Sync Process Error',
        details: e.toString(),
      );
      
      // NEW: Handle sync process errors
      if (!_isServerDown) {
        await _handleServerDown();
      }
    } finally {
      _isSyncing = false;
      // REMOVED: CarPowerService.stopMonitoring() call
      print('Sync process completed');
    }
  }

  // NEW METHOD: Update unsynced records with current igStatus from Kotlin service
  Future<void> _updateUnsyncedRecordsIgStatus(int igStatus) async {
    try {
      final db = await _dbHelper.database;
      final updatedRows = await db.update(
        'location_data',
        {'igStatus': igStatus},
        where: 'sync_status = ?', // Use sync_status not syncStatus
        whereArgs: [0],
      );
      print('Updated $updatedRows unsynced records with igStatus: $igStatus');
    } catch (e) {
      print('Error updating igStatus: $e');
    }
  }

  Future<void> startPeriodicSync({int intervalSeconds = 30}) async {
    _syncIntervalSeconds = intervalSeconds;
    _syncTimer?.cancel();
    
    print('Starting periodic sync with interval: ${_syncIntervalSeconds}s');
    
    // Initial sync
    _startSync();
    
    // NEW: Adaptive periodic sync based on server status
    _syncTimer = Timer.periodic(Duration(seconds: _syncIntervalSeconds), (timer) async {
      // Check if we need to adjust interval based on phase
      if (_isServerDown) {
        final phaseInterval = _getCurrentRetryInterval();
        if (phaseInterval.inSeconds != _syncIntervalSeconds) {
          print('🔄 Adjusting sync interval to ${phaseInterval.inMinutes} minutes (phase $_currentPhase)');
          _syncIntervalSeconds = phaseInterval.inSeconds;
          timer.cancel();
          _syncTimer = Timer.periodic(phaseInterval, (newTimer) {
            _startSync();
          });
        }
      }
      _startSync();
    });
  }

  Future<void> stopPeriodicSync() async {
    _syncTimer?.cancel();
    _syncTimer = null;
    print('Periodic sync stopped');
  }

  Future<void> queueLocationData(Map<String, dynamic> data) async {
    try {
      // Get the most up-to-date igStatus directly from native BackgroundService (no SharedPreferences)
      int igStatusFromNative = 0;
      try {
        const serviceChannel = MethodChannel('com.example.twtracking/service');
        igStatusFromNative = await serviceChannel.invokeMethod('getCurrentIgStatus');
        print('Read igStatus directly from native BackgroundService for new location: $igStatusFromNative');
      } catch (e) {
        print('❌ Error getting igStatus from native service for new location: $e');
        // Fallback to SharedPreferences if method channel fails
        final prefs = await SharedPreferences.getInstance();
        igStatusFromNative = prefs.getInt('current_ig_status') ?? 0;
        print('Fallback to SharedPreferences igStatus for new location: $igStatusFromNative');
      }

      // Use the igStatus from native service (no default fallback to 0)
      data['igStatus'] = igStatusFromNative;
      
      print('Queueing new location with igStatus: ${data['igStatus']} (from native: $igStatusFromNative)');

      // Check if entry already exists to prevent duplicates
      final exists = await _dbHelper.locationEntryExists(data);
      if (!exists) {
        await _dbHelper.insertLocationData(data);
        print('Location data queued successfully with igStatus: ${data['igStatus']}');
        
        // Try to sync immediately if we have connection
        if (await _hasInternetConnection()) {
          _startSync();
        }
      } else {
        print('Duplicate location entry detected, skipping insert');
      }
    } catch (e) {
      print('Error queueing location data: $e');
      await _dbHelper.insertExceptionLog(
        main: 'Queue Error',
        details: e.toString(),
      );
    }
  }

  Future<void> forcSync() async {
    print('Force sync requested');
    await _startSync();
  }

  Future<void> forceSyncWithDebug() async {
    print('=== FORCE SYNC WITH DEBUG ===');
    
    // First test server connectivity
    await testServerConnectivity();
    
    // Test igStatus functionality using SharedPreferences
    print('=== TESTING IGSTATUS FUNCTIONALITY ===');
    final igStatusTestResults = await testIgStatusFunctionality();
    print('igStatus test results: $igStatusTestResults');
    
    // Then run normal sync
    await _startSync();
  }

  Future<Map<String, dynamic>> getSyncStats() async {
    final dbStats = await _dbHelper.getDatabaseStats();
    
    // Get current ACC state directly from native BackgroundService (no SharedPreferences)
    int currentIgStatus = 0;
    int igStatusTimestamp = 0;
    try {
      const serviceChannel = MethodChannel('com.example.twtracking/service');
      currentIgStatus = await serviceChannel.invokeMethod('getCurrentIgStatus');
      print('Read igStatus directly from native BackgroundService for stats: $currentIgStatus');
      
      // Get timestamp from SharedPreferences as fallback
      final prefs = await SharedPreferences.getInstance();
      igStatusTimestamp = prefs.getInt('ig_status_timestamp') ?? 0;
    } catch (e) {
      print('❌ Error getting igStatus from native service for stats: $e');
      // Fallback to SharedPreferences if method channel fails
      final prefs = await SharedPreferences.getInstance();
      currentIgStatus = prefs.getInt('current_ig_status') ?? 0;
      igStatusTimestamp = prefs.getInt('ig_status_timestamp') ?? 0;
      print('Fallback to SharedPreferences for stats - igStatus: $currentIgStatus');
    }
    
    // NEW: Add phase-based retry information
    final currentPhaseInterval = _getCurrentRetryInterval();
    final nextPhaseInterval = _currentPhase < _retryPhases.length - 1 
        ? Duration(minutes: _retryPhases[_currentPhase + 1])
        : currentPhaseInterval;
    
    return {
      ...dbStats,
      'isSyncing': _isSyncing,
      'syncInterval': _syncIntervalSeconds,
      'hasInternet': await _hasInternetConnection(),
      'currentIgStatus': currentIgStatus,
      'igStatusLastUpdated': DateTime.fromMillisecondsSinceEpoch(igStatusTimestamp).toString(),
      'igStatusSource': 'BackgroundService (direct)',
      // NEW: Phase-based retry stats
      'isServerDown': _isServerDown,
      'currentPhase': _currentPhase,
      'consecutiveFailures': _consecutiveFailures,
      'currentPhaseInterval': '${currentPhaseInterval.inMinutes} minutes',
      'nextPhaseInterval': '${nextPhaseInterval.inMinutes} minutes',
      'lastServerDownTime': _lastServerDownTime?.toIso8601String(),
      'serverDownDuration': _lastServerDownTime != null 
          ? '${DateTime.now().difference(_lastServerDownTime!).inMinutes} minutes'
          : null,
    };
  }

  Future<void> updateSyncInterval(int seconds) async {
    _syncIntervalSeconds = seconds;
    if (_syncTimer != null) {
      await startPeriodicSync(intervalSeconds: seconds);
    }
  }

  Future<void> clearSyncErrors() async {
    await _dbHelper.clearExceptionLogs();
    print('Sync error logs cleared');
  }

  bool get isSyncing => _isSyncing;
  
  int get syncInterval => _syncIntervalSeconds;

  Future<void> cleanupDatabase() async {
    await _dbHelper.cleanupDatabase();
  }

  Future<void> maintainRecordLimit() async {
    await _dbHelper.forceMaintainRecordLimit();
  }

  // NEW: Manual phase reset for testing/debugging
  Future<void> resetPhaseSystem() async {
    print('🔄 Manually resetting phase system...');
    _currentPhase = 0;
    _consecutiveFailures = 0;
    _isServerDown = false;
    _lastServerDownTime = null;
    
    await _dbHelper.insertExceptionLog(
      main: 'Manual Phase Reset',
      details: 'Phase system reset by user/admin',
    );
    
    print('✅ Phase system reset to normal mode');
  }

  // NEW: Force server status check and reset
  Future<void> forceServerStatusCheck() async {
    print('🔄 Forcing server status check...');
    await checkServerStatus();
    print('✅ Server status check completed');
  }

  // NEW: Force advance to specific phase for testing
  Future<void> forcePhase(int phase) async {
    if (phase >= 0 && phase < _retryPhases.length) {
      print('🔄 Forcing phase to $phase (${_retryPhases[phase]} minutes)');
      _currentPhase = phase;
      _isServerDown = true;
      _lastServerDownTime = DateTime.now();
      
      await _dbHelper.insertExceptionLog(
        main: 'Force Phase',
        details: 'Phase forced to $phase (${_retryPhases[phase]} minutes) for testing',
      );
      
      print('✅ Phase forced to $phase');
    } else {
      print('❌ Invalid phase: $phase (must be 0-${_retryPhases.length - 1})');
    }
  }

  // NEW: Force server down detection for testing
  Future<void> forceServerDownDetection() async {
    print('🔄 Forcing server down detection for testing...');
    _isServerDown = true;
    _currentPhase = 0;
    _consecutiveFailures = 1;
    _lastServerDownTime = DateTime.now();
    
    await _dbHelper.insertExceptionLog(
      main: 'Force Server Down Detection',
      details: 'Server down detection forced for testing purposes',
    );
    
    print('✅ Server down detection forced');
  }

  // NEW: Check if server is actually down by testing connectivity
  Future<bool> checkServerStatus() async {
    try {
      print('🔍 === CHECKING SERVER STATUS ===');
      print('Current state - isServerDown: $_isServerDown, phase: $_currentPhase');
      
      // First check internet connectivity
      if (!await _hasInternetConnection()) {
        print('❌ No internet connection available');
        return false;
      }
      
      // Then check server health
      final isHealthy = await _isServerHealthy();
      
      if (!isHealthy) {
        print('❌ Server health check failed - server appears to be down');
        if (!_isServerDown) {
          await _handleServerDown();
        }
        return false;
      } else {
        print('✅ Server is healthy');
        // Always reset phases if server is healthy, regardless of current state
        if (_isServerDown || _currentPhase > 0) {
          print('🔄 Resetting server status from down to healthy...');
          _resetPhases();
          print('✅ Server status reset to normal mode - isServerDown: $_isServerDown, phase: $_currentPhase');
        } else {
          print('✅ Server already in healthy state');
        }
        return true;
      }
    } catch (e) {
      print('❌ Error checking server status: $e');
      if (!_isServerDown) {
        await _handleServerDown();
      }
      return false;
    }
  }

  // NEW METHOD: Get current ACC state (for debugging/monitoring)
  Future<Map<String, dynamic>> getAccState() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      int currentIgStatus = prefs.getInt('current_ig_status') ?? 0;
      int igStatusTimestamp = prefs.getInt('ig_status_timestamp') ?? 0;
      
      return {
        'igStatus': currentIgStatus,
        'isAccOn': currentIgStatus == 1,
        'lastUpdated': DateTime.fromMillisecondsSinceEpoch(igStatusTimestamp).toString(),
        'source': 'FlutterSharedPreferences',
        'flutterSharedPrefs': currentIgStatus,
        'flutterTimestamp': igStatusTimestamp,
      };
    } catch (e) {
      print('Error getting ACC state: $e');
      return {
        'igStatus': 0, // Default to ACC OFF
        'isAccOn': false,
        'lastUpdated': 'unknown',
        'source': 'default fallback',
        'error': e.toString(),
      };
    }
  }

  String? get lastError => _lastError;
  int get retryCount => _retryCount;

  // NEW METHOD: Comprehensive test for igStatus functionality
  Future<Map<String, dynamic>> testIgStatusFunctionality() async {
    final results = <String, dynamic>{};
    
    try {
      print('🧪 === STARTING IGSTATUS FUNCTIONALITY TEST ===');
      
      // Test 1: Get current igStatus from SharedPreferences
      print('🧪 Test 1: Getting current igStatus from SharedPreferences...');
      final accState = await getAccState();
      results['currentIgStatus'] = accState['igStatus'];
      results['accState'] = accState;
      print('✅ Current igStatus: ${accState['igStatus']}');
      
      // Test 2: Manually set igStatus to 1 (ACC ON)
      print('🧪 Test 2: Manually setting igStatus to 1 (ACC ON)...');
      await _updateUnsyncedRecordsIgStatus(1);
      results['manualSetAccOn'] = true;
      print('✅ igStatus set to 1 (ACC ON)');
      
      // Test 3: Get igStatus after setting to ACC ON
      await Future.delayed(const Duration(seconds: 1));
      final accStateAfterOn = await getAccState();
      results['igStatusAfterAccOn'] = accStateAfterOn['igStatus'];
      print('✅ igStatus after ACC ON: ${accStateAfterOn['igStatus']}');
      
      // Test 4: Manually set igStatus to 0 (ACC OFF)
      print('🧪 Test 4: Manually setting igStatus to 0 (ACC OFF)...');
      await _updateUnsyncedRecordsIgStatus(0);
      results['manualSetAccOff'] = true;
      print('✅ igStatus set to 0 (ACC OFF)');
      
      // Test 5: Get igStatus after setting to ACC OFF
      await Future.delayed(const Duration(seconds: 1));
      final accStateAfterOff = await getAccState();
      results['igStatusAfterAccOff'] = accStateAfterOff['igStatus'];
      print('✅ igStatus after ACC OFF: ${accStateAfterOff['igStatus']}');
      
      print('✅ === IGSTATUS FUNCTIONALITY TEST COMPLETED ===');
      
    } catch (e) {
      print('❌ Error during igStatus functionality test: $e');
      results['error'] = e.toString();
    }
    
    return results;
  }
}