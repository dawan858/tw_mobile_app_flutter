import 'dart:async';
import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:http/http.dart' as http;
import 'dart:convert';
import 'database_helper.dart';

class SyncService {
  static final SyncService _instance = SyncService._internal();
  final DatabaseHelper _dbHelper = DatabaseHelper();
  final _connectivity = Connectivity();
  Timer? _syncTimer;
  bool _isSyncing = false;
  static const String serverUrl = 'http://ec2-3-83-201-132.compute-1.amazonaws.com:3000/api/location';

  factory SyncService() => _instance;

  SyncService._internal() {
    _initConnectivityListener();
  }

  void _initConnectivityListener() {
    _connectivity.onConnectivityChanged.listen((ConnectivityResult result) {
      if (result != ConnectivityResult.none) {
        _startSync();
      }
    });
  }

  Future<void> _startSync() async {
    if (_isSyncing) return;
    _isSyncing = true;

    try {
      final unsyncedData = await _dbHelper.getUnsyncedData();
      if (unsyncedData.isEmpty) {
        _isSyncing = false;
        return;
      }

      final List<int> syncedIds = [];
      for (var data in unsyncedData) {
        try {
          final response = await http.post(
            Uri.parse(serverUrl),
            headers: {'Content-Type': 'application/json'},
            body: jsonEncode(data),
          );

          if (response.statusCode == 200 || response.statusCode == 201) {
            syncedIds.add(data['id']);
          }
        } catch (e) {
          print('Error syncing data: $e');
          break; // Stop on first error
        }
      }

      if (syncedIds.isNotEmpty) {
        await _dbHelper.markAsSynced(syncedIds);
        await _dbHelper.deleteSyncedData(); // Clean up old synced data
      }
    } finally {
      _isSyncing = false;
    }
  }

  Future<void> startPeriodicSync() async {
    _syncTimer?.cancel();
    _syncTimer = Timer.periodic(const Duration(minutes: 5), (timer) {
      _startSync();
    });
  }

  Future<void> stopPeriodicSync() async {
    _syncTimer?.cancel();
    _syncTimer = null;
  }

  Future<void> queueLocationData(Map<String, dynamic> data) async {
    // Check if entry already exists (from background service)
    final exists = await _dbHelper.locationEntryExists(data);
    if (!exists) {
      await _dbHelper.insertLocationData(data);
      _startSync(); // Try to sync immediately
    } else {
      // Optionally log or handle duplicate
      print('Duplicate location entry detected, skipping insert.');
    }
  }
} 