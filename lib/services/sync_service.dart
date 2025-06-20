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
  static const String serverUrl = 'http://ec2-52-66-236-101.ap-south-1.compute.amazonaws.com:3000/api/location';
  int _syncIntervalSeconds = 30; // Default sync interval
  static const int maxRetries = 3;
  static const Duration defaultSyncInterval = Duration(minutes: 1);
  static const Duration retryDelay = Duration(seconds: 30);
  int _batchSize = 50; // Sync in smaller batches
  
  // Method channel for power state check
  static const MethodChannel _avnSleepChannel = MethodChannel('com.example.twtracking/avn_sleep');
  static const MethodChannel _serviceChannel = MethodChannel('com.example.twtracking/service');

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
    print('Starting sync process...');

    try {
      // NEW: Test method channel connectivity first (for debugging)
      try {
        await testMethodChannel();
      } catch (e) {
        print('⚠️ Method channel test failed: $e');
      }
      
      // NEW: Check CarPowerManager status for debugging
      try {
        await getCarPowerManagerStatus();
      } catch (e) {
        print('⚠️ CarPowerManager status check failed: $e');
      }
      
      // NEW: Try to trigger power state check, but don't fail if it doesn't work
      try {
        await triggerPowerStateCheck();
      } catch (e) {
        print('⚠️ Power state check failed, continuing with sync: $e');
        // Don't fail the sync if power state check fails
      }
      
      // Check internet connectivity
      if (!await _hasInternetConnection()) {
        print('No internet connection available');
        return;
      }

      // Get current ACC state from SharedPreferences (set by Kotlin BackgroundService)
      final prefs = await SharedPreferences.getInstance();
      int currentIgStatus = prefs.getInt('current_ig_status') ?? 0; // Default to ACC OFF
      
      // Also check tracking_prefs for consistency
      final trackingPrefs = await SharedPreferences.getInstance();
      final trackingIgStatus = trackingPrefs.getInt('current_ig_status');
      
      // Use the most recent igStatus (prefer tracking_prefs if available)
      if (trackingIgStatus != null) {
        currentIgStatus = trackingIgStatus;
        print('Using igStatus from tracking_prefs: $currentIgStatus');
      } else {
        print('Using igStatus from FlutterSharedPreferences: $currentIgStatus');
      }

      // NEW: Try to get igStatus from native side first
      try {
        final nativeIgStatus = await getIgStatusFromNative();
        if (nativeIgStatus != currentIgStatus) {
          print('🔄 Native igStatus ($nativeIgStatus) differs from SharedPreferences ($currentIgStatus)');
          print('   - Using native igStatus: $nativeIgStatus');
          currentIgStatus = nativeIgStatus;
          
          // Update SharedPreferences with native value
          await prefs.setInt('current_ig_status', currentIgStatus);
          await prefs.setInt('ig_status_timestamp', DateTime.now().millisecondsSinceEpoch);
        } else {
          print('✅ Native igStatus matches SharedPreferences: $currentIgStatus');
        }
      } catch (e) {
        print('⚠️ Could not get native igStatus, using SharedPreferences: $e');
      }

      // Update any unsynced records with current igStatus
      await _updateUnsyncedRecordsIgStatus(currentIgStatus);

      final unsyncedData = await _dbHelper.getUnsyncedData(limit: _batchSize);
      if (unsyncedData.isEmpty) {
        print('No unsynced data to upload');
        return;
      }

      print('Found ${unsyncedData.length} records to sync');
      final List<int> syncedIds = [];
      int successCount = 0;
      int failureCount = 0;

      for (var data in unsyncedData) {
        try {
          // Remove the id field from data before sending
          final dataToSend = Map<String, dynamic>.from(data);
          dataToSend.remove('id');
          dataToSend.remove('sync_status');
          dataToSend.remove('created_at');

          print('=== SENDING DATA TO SERVER ===');
          print('Server URL: $serverUrl');
          print('Record ID: ${data['id']}');
          print('igStatus: ${dataToSend['igStatus']}');
          print('Data to send: ${jsonEncode(dataToSend)}');

          bool syncSuccess = false;
          for (int retry = 0; retry < maxRetries; retry++) {
            try {
              print('Attempt ${retry + 1}/$maxRetries for record ${data['id']}');
              
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
                if (retry == maxRetries - 1) {
                  failureCount++;
                  await _dbHelper.insertExceptionLog(
                    main: 'Sync Server Error',
                    details: 'Record ID: ${data['id']}, Status: ${response.statusCode}, Body: ${response.body}',
                  );
                }
              }
            } catch (e) {
              print('❌ Network error for record ${data['id']} (attempt ${retry + 1}): $e');
              if (retry == maxRetries - 1) {
                failureCount++;
                await _dbHelper.insertExceptionLog(
                  main: 'Sync Network Error',
                  details: 'Record ID: ${data['id']}, Error: $e',
                );
              }
              
              // Wait before retrying
              if (retry < maxRetries - 1) {
                await Future.delayed(Duration(seconds: (retry + 1) * 2));
              }
            }
          }

          if (!syncSuccess) {
            break; // Stop syncing on persistent failures
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

      // Get updated stats
      final stats = await _dbHelper.getDatabaseStats();
      print('Database stats: ${stats['totalRecords']} total, ${stats['unsyncedRecords']} unsynced');

    } catch (e) {
      print('Error in sync process: $e');
      await _dbHelper.insertExceptionLog(
        main: 'Sync Process Error',
        details: e.toString(),
      );
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
    
    // Periodic sync
    _syncTimer = Timer.periodic(Duration(seconds: _syncIntervalSeconds), (timer) {
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
      // Get current ACC state from SharedPreferences for new location data
      final prefs = await SharedPreferences.getInstance();
      int currentIgStatus = prefs.getInt('current_ig_status') ?? 0;
      
      // Also check tracking_prefs for consistency
      final trackingPrefs = await SharedPreferences.getInstance();
      final trackingIgStatus = trackingPrefs.getInt('current_ig_status');
      
      // Use the most recent igStatus (prefer tracking_prefs if available)
      if (trackingIgStatus != null) {
        currentIgStatus = trackingIgStatus;
        print('Using igStatus from tracking_prefs for new location: $currentIgStatus');
      } else {
        print('Using igStatus from FlutterSharedPreferences for new location: $currentIgStatus');
      }
      
      // Ensure the data has the current igStatus
      data['igStatus'] = currentIgStatus;
      
      // Check if entry already exists to prevent duplicates
      final exists = await _dbHelper.locationEntryExists(data);
      if (!exists) {
        await _dbHelper.insertLocationData(data);
        print('Location data queued successfully with igStatus: $currentIgStatus');
        
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
    
    // Then run normal sync
    await _startSync();
  }

  Future<Map<String, dynamic>> getSyncStats() async {
    final dbStats = await _dbHelper.getDatabaseStats();
    
    // Get current ACC state from SharedPreferences
    final prefs = await SharedPreferences.getInstance();
    int currentIgStatus = prefs.getInt('current_ig_status') ?? 0;
    int igStatusTimestamp = prefs.getInt('ig_status_timestamp') ?? 0;
    
    return {
      ...dbStats,
      'isSyncing': _isSyncing,
      'syncInterval': _syncIntervalSeconds,
      'hasInternet': await _hasInternetConnection(),
      'currentIgStatus': currentIgStatus,
      'igStatusLastUpdated': DateTime.fromMillisecondsSinceEpoch(igStatusTimestamp).toString(),
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

  // UPDATED METHOD: Now reads from SharedPreferences instead of calling CarPowerService
  Future<void> _updateIgStatus(int status) async {
    try {
      // Store in SharedPreferences (this will be set by Kotlin BackgroundService)
      final prefs = await SharedPreferences.getInstance();
      await prefs.setInt('current_ig_status', status);
      await prefs.setInt('ig_status_timestamp', DateTime.now().millisecondsSinceEpoch);
      
      // Update unsynced records
      await _updateUnsyncedRecordsIgStatus(status);
      
      print('Updated igStatus to: $status');
    } catch (e) {
      print('Error updating igStatus: $e');
    }
  }

  // NEW METHOD: Get current ACC state (for debugging/monitoring)
  Future<Map<String, dynamic>> getAccState() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      int currentIgStatus = prefs.getInt('current_ig_status') ?? 0;
      int igStatusTimestamp = prefs.getInt('ig_status_timestamp') ?? 0;
      
      // Also check tracking_prefs for consistency
      final trackingPrefs = await SharedPreferences.getInstance();
      final trackingIgStatus = trackingPrefs.getInt('current_ig_status');
      final trackingTimestamp = trackingPrefs.getInt('ig_status_timestamp') ?? 0;
      
      // Use the most recent igStatus
      String source = 'FlutterSharedPreferences';
      if (trackingIgStatus != null && trackingTimestamp > igStatusTimestamp) {
        currentIgStatus = trackingIgStatus;
        igStatusTimestamp = trackingTimestamp;
        source = 'tracking_prefs';
      }
      
      return {
        'igStatus': currentIgStatus,
        'isAccOn': currentIgStatus == 1,
        'lastUpdated': DateTime.fromMillisecondsSinceEpoch(igStatusTimestamp).toString(),
        'source': source,
        'flutterSharedPrefs': prefs.getInt('current_ig_status'),
        'trackingPrefs': trackingIgStatus,
        'flutterTimestamp': prefs.getInt('ig_status_timestamp'),
        'trackingTimestamp': trackingTimestamp,
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

  // NEW METHOD: Get current igStatus from method channel
  Future<int> getIgStatusFromNative() async {
    try {
      print('🔄 Getting igStatus from native side...');
      
      // Try service channel first
      try {
        final result = await _serviceChannel.invokeMethod('getCurrentIgStatus');
        print('✅ Got igStatus from service channel: $result');
        return result ?? 0;
      } catch (e) {
        print('⚠️ Service channel failed, trying AVN sleep channel: $e');
        
        // Fallback to AVN sleep channel
        final result = await _avnSleepChannel.invokeMethod('getCurrentIgStatus');
        print('✅ Got igStatus from AVN sleep channel: $result');
        return result ?? 0;
      }
    } catch (e) {
      print('❌ Error getting igStatus from native side: $e');
      return 0; // Default to ACC OFF
    }
  }

  // NEW METHOD: Trigger power state check
  Future<void> triggerPowerStateCheck() async {
    try {
      print('🔄 Triggering power state check from Flutter sync service');
      print('   - Channel: com.example.twtracking/service');
      print('   - Method: triggerPowerStateCheck');
      
      // Try using the service channel first (simpler approach)
      final result = await _serviceChannel.invokeMethod('triggerPowerStateCheck');
      print('✅ Power state check triggered successfully via service channel');
      print('   - Result: $result');
    } catch (e) {
      print('❌ Error triggering power state check via service channel: $e');
      print('   - Error type: ${e.runtimeType}');
      print('   - Error details: $e');
      
      // Fallback: try the AVN sleep channel
      try {
        print('🔄 Trying fallback via AVN sleep channel...');
        final result = await _avnSleepChannel.invokeMethod('triggerPowerStateCheck');
        print('✅ Power state check triggered successfully via AVN sleep channel');
        print('   - Result: $result');
      } catch (e2) {
        print('❌ Error triggering power state check via AVN sleep channel: $e2');
        print('   - Error type: ${e2.runtimeType}');
        print('   - Error details: $e2');
      }
    }
  }

  // NEW METHOD: Test method channel connection
  Future<void> testMethodChannel() async {
    try {
      print('🧪 Testing method channel connection...');
      final result = await _serviceChannel.invokeMethod('testMethodChannel');
      print('✅ Method channel test result: $result');
    } catch (e) {
      print('❌ Method channel test failed: $e');
    }
  }

  // NEW METHOD: Manually set igStatus (for debugging)
  Future<void> setIgStatusManually(int status) async {
    try {
      print('🔧 Setting igStatus manually to: $status');
      await _serviceChannel.invokeMethod('setIgStatusManually', {'status': status});
      print('✅ igStatus set manually to: $status');
    } catch (e) {
      print('❌ Error setting igStatus manually: $e');
    }
  }

  // NEW METHOD: Check CarPowerManager status for debugging
  static Future<Map<String, dynamic>?> getCarPowerManagerStatus() async {
    try {
      print('🔄 Getting CarPowerManager status from native side...');
      
      final result = await _serviceChannel.invokeMethod('getCarPowerManagerStatus');
      
      if (result != null) {
        print('✅ CarPowerManager status received:');
        final status = result['status'] as Map<String, dynamic>;
        final isProperlyInitialized = result['isProperlyInitialized'] as bool;
        
        status.forEach((key, value) {
          print('   - $key: $value');
        });
        print('   - isProperlyInitialized: $isProperlyInitialized');
        
        return result;
      } else {
        print('⚠️ CarPowerManager status result is null');
        return null;
      }
    } catch (e) {
      print('❌ Error getting CarPowerManager status: $e');
      print('   - Error type: ${e.runtimeType}');
      return null;
    }
  }
}