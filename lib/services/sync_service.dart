import 'dart:async';
import 'dart:io';
import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:http/http.dart' as http;
import 'package:intl/intl.dart';
import 'dart:convert';
import 'database_helper.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tracking_world/services/car_power_service.dart';

class SyncService {
  static final SyncService _instance = SyncService._internal();
  final DatabaseHelper _dbHelper = DatabaseHelper();
  final CarPowerService _carPowerService = CarPowerService();
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

  factory SyncService() => _instance;

  SyncService._internal() {
    _initConnectivityListener();
    _carPowerService.onAccStateChanged = (bool isAccOn) {
      // Update igStatus in the database when ACC state changes
      _updateIgStatus(isAccOn ? 1 : 0);
    };
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
      final testData = {
        'latitude': 33.6383,
        'longitude': 73.1335,
        'accuracy': 10.0,
        'altitude': 100.0,
        'speed': 0.0,
        'bearing': 0.0,
        'imei': 'TEST_DEVICE',
        'timestamp': DateTime.now().toIso8601String(),
        'deviceRDT': DateFormat("dd/MM/yyyy HH:mm:ss.SSS").format(DateTime.now()),
        'gmtSettings': "GMT+5:00 2024",
        'igStatus': 1,
        'localPrimaryId': 12345,
        'name': 'Test Device',
        'phoneNo': 'TEST123',
        'provider': 'test',
        'reason': 'Test',
        'versionNo': 'v1.0',
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
      // Check internet connectivity
      if (!await _hasInternetConnection()) {
        print('No internet connection available');
        return;
      }

      // Start car power monitoring
      await _carPowerService.startMonitoring();

      // Get initial ACC state
      final initialAccState = await _carPowerService.getCurrentAccState();
      await _updateIgStatus(initialAccState ? 1 : 0);

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
                print('✅ Successfully synced record ID: ${data['id']}');
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
      await _carPowerService.stopMonitoring();
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
      // Check if entry already exists to prevent duplicates
      final exists = await _dbHelper.locationEntryExists(data);
      if (!exists) {
        await _dbHelper.insertLocationData(data);
        print('Location data queued successfully');
        
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
    return {
      ...dbStats,
      'isSyncing': _isSyncing,
      'syncInterval': _syncIntervalSeconds,
      'hasInternet': await _hasInternetConnection(),
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

  Future<void> _updateIgStatus(int status) async {
    try {
      final db = await _dbHelper.database;
      await db.update(
        'location_data',
        {'igStatus': status},
        where: 'syncStatus = ?',
        whereArgs: [0],
      );
    } catch (e) {
      print('Error updating igStatus: $e');
    }
  }

  String? get lastError => _lastError;
  int get retryCount => _retryCount;
}