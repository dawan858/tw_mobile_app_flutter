import 'package:cloud_firestore/cloud_firestore.dart';
import 'package:firebase_core/firebase_core.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'database_helper.dart';

class FirebaseService {
  static final FirebaseService _instance = FirebaseService._internal();
  factory FirebaseService() => _instance;
  FirebaseService._internal();

  late FirebaseFirestore _firestore;
  bool _isInitialized = false;
  bool _isSyncing = false;
  DateTime? _lastSyncTime;

  Future<void> initialize() async {
    if (_isInitialized) return;
    
    try {
      await Firebase.initializeApp();
      _firestore = FirebaseFirestore.instance;
      _isInitialized = true;
      print('✅ Firebase initialized successfully');
    } catch (e) {
      print('❌ Error initializing Firebase: $e');
      rethrow;
    }
  }

  Future<String?> getImei() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      return prefs.getString('imei');
    } catch (e) {
      print('Error getting IMEI: $e');
      return null;
    }
  }

  Future<void> sendIgnitionLog({
    required String message,
    String details = '',
    String logType = 'info',
    String timestamp = '',
  }) async {
    try {
      // Only send ACC ON/OFF related logs to Firebase
      final lowerMessage = message.toLowerCase();
      final isAccEvent = lowerMessage.contains('acc_on') || 
                        lowerMessage.contains('acc_off') ||
                        lowerMessage.contains('acc on') || 
                        lowerMessage.contains('acc off') ||
                        lowerMessage.contains('ignition on') ||
                        lowerMessage.contains('ignition off') ||
                        lowerMessage.contains('ignition_on') ||
                        lowerMessage.contains('ignition_off') ||
                        lowerMessage.contains('engine start') ||
                        lowerMessage.contains('engine stop');
      
      if (!isAccEvent) {
        print('ℹ️ Skipping non-ACC ignition log: $message');
        return;
      }

      print('🎯 ACC event detected: $message');

      print('🔄 Sending ACC ignition log to Firebase: $message');

      if (!_isInitialized) await initialize();
      
      final imei = await getImei();
      if (imei == null || imei.isEmpty || imei == 'unknown') {
        print('⚠️ Cannot send ignition log: IMEI not available');
        return;
      }

      final logData = {
        'message': message,
        'details': details,
        'logType': logType,
        'timestamp': timestamp.isNotEmpty ? timestamp : DateTime.now().toIso8601String(),
        'deviceImei': imei,
        'createdAt': FieldValue.serverTimestamp(),
      };

      final docRef = await _firestore
          .collection('devices')
          .doc(imei)
          .collection('ignition_logs')
          .add(logData);

      print('✅ ACC ignition log sent to Firebase for IMEI: $imei, Document ID: ${docRef.id}');
    } catch (e) {
      print('❌ Error sending ignition log to Firebase: $e');
      print('❌ Error details: ${e.toString()}');
      // Don't rethrow to avoid breaking the app
    }
  }

  Future<void> sendExceptionLog({
    required String main,
    String details = '',
    String timestamp = '',
  }) async {
    try {
      print('🔄 Sending exception log to Firebase: $main');

      if (!_isInitialized) await initialize();
      
      final imei = await getImei();
      if (imei == null || imei.isEmpty || imei == 'unknown') {
        print('⚠️ Cannot send exception log: IMEI not available');
        return;
      }

      final logData = {
        'main': main,
        'details': details,
        'timestamp': timestamp.isNotEmpty ? timestamp : DateTime.now().toIso8601String(),
        'deviceImei': imei,
        'createdAt': FieldValue.serverTimestamp(),
      };

      final docRef = await _firestore
          .collection('devices')
          .doc(imei)
          .collection('exception_logs')
          .add(logData);

      print('✅ Exception log sent to Firebase for IMEI: $imei, Document ID: ${docRef.id}');
    } catch (e) {
      print('❌ Error sending exception log to Firebase: $e');
      print('❌ Error details: ${e.toString()}');
      // Don't rethrow to avoid breaking the app
    }
  }

  Future<void> syncExistingLogs() async {
    // Prevent multiple sync operations running simultaneously
    if (_isSyncing) {
      print('⚠️ Sync already in progress, skipping...');
      return;
    }

    // Prevent sync from running too frequently (max once per 5 minutes)
    if (_lastSyncTime != null) {
      final timeSinceLastSync = DateTime.now().difference(_lastSyncTime!);
      if (timeSinceLastSync.inMinutes < 5) {
        print('⚠️ Sync ran recently (${timeSinceLastSync.inMinutes} minutes ago), skipping...');
        return;
      }
    }

    try {
      _isSyncing = true;
      _lastSyncTime = DateTime.now();
      print('🔄 Starting sync of existing logs to Firebase...');
      
      if (!_isInitialized) await initialize();
      
      final imei = await getImei();
      if (imei == null || imei.isEmpty || imei == 'unknown') {
        print('⚠️ Cannot sync logs: IMEI not available');
        return;
      }

      // Import database helper to get existing logs
      final DatabaseHelper dbHelper = DatabaseHelper();
      
      // Check if logs already exist in Firebase
      final ignitionLogsExist = await _logsAlreadyExist(imei, 'ignition');
      final exceptionLogsExist = await _logsAlreadyExist(imei, 'exception');
      
      // Get recent ignition logs and filter for ACC events only
      final allIgnitionLogs = await dbHelper.getIgnitionLogs(limit: 100);
      print('📊 Found ${allIgnitionLogs.length} total ignition logs');
      
      final accIgnitionLogs = allIgnitionLogs.where((log) {
        final message = (log['message'] as String? ?? '').toLowerCase();
        final isAcc = message.contains('acc_on') || 
               message.contains('acc_off') ||
               message.contains('acc on') || 
               message.contains('acc off') ||
               message.contains('ignition on') ||
               message.contains('ignition off') ||
               message.contains('ignition_on') ||
               message.contains('ignition_off') ||
               message.contains('engine start') ||
               message.contains('engine stop');
        
        if (isAcc) {
          print('🎯 ACC log found: ${log['message']}');
        }
        
        return isAcc;
      }).toList();
      
      if (accIgnitionLogs.isNotEmpty && !ignitionLogsExist) {
        await sendBatchLogs(accIgnitionLogs, 'ignition');
        print('✅ Synced ${accIgnitionLogs.length} ACC ignition logs to Firebase');
      } else if (ignitionLogsExist) {
        print('ℹ️ Ignition logs already exist in Firebase, skipping sync');
      } else {
        print('ℹ️ No ACC ignition logs found to sync');
      }

      // Get recent exception logs (all exceptions are sent)
      final exceptionLogs = await dbHelper.getExceptionLogs(limit: 50);
      if (exceptionLogs.isNotEmpty && !exceptionLogsExist) {
        await sendBatchLogs(exceptionLogs, 'exception');
        print('✅ Synced ${exceptionLogs.length} exception logs to Firebase');
      } else if (exceptionLogsExist) {
        print('ℹ️ Exception logs already exist in Firebase, skipping sync');
      } else {
        print('ℹ️ No exception logs found to sync');
      }

      print('✅ Existing logs sync completed for IMEI: $imei');
    } catch (e) {
      print('❌ Error syncing existing logs to Firebase: $e');
      print('❌ Error details: ${e.toString()}');
    } finally {
      _isSyncing = false;
    }
  }

  // Check if logs already exist in Firebase to prevent duplicates
  Future<bool> _logsAlreadyExist(String imei, String logType) async {
    try {
      final collection = logType == 'ignition' ? 'ignition_logs' : 'exception_logs';
      final snapshot = await _firestore
          .collection('devices')
          .doc(imei)
          .collection(collection)
          .limit(1)
          .get();
      
      return snapshot.docs.isNotEmpty;
    } catch (e) {
      print('⚠️ Error checking existing logs: $e');
      return false;
    }
  }

  Future<void> sendBatchLogs(List<Map<String, dynamic>> logs, String logType) async {
    try {
      if (!_isInitialized) await initialize();
      
      final imei = await getImei();
      if (imei == null || imei.isEmpty || imei == 'unknown') {
        print('⚠️ Cannot send batch logs: IMEI not available');
        return;
      }

      print('🔄 Starting batch upload of ${logs.length} $logType logs for IMEI: $imei');
      
      final batch = _firestore.batch();
      final collection = logType == 'ignition' ? 'ignition_logs' : 'exception_logs';

      for (final log in logs) {
        // Create a new document reference with auto-generated ID
        final docRef = _firestore
            .collection('devices')
            .doc(imei)
            .collection(collection)
            .doc();

        // Clean the log data to avoid conflicts
        final cleanLogData = Map<String, dynamic>.from(log);
        
        // Remove any potentially problematic fields
        cleanLogData.remove('id'); // Remove SQLite ID to avoid conflicts
        cleanLogData.remove('_id'); // Remove any MongoDB-style IDs
        
        final logData = {
          ...cleanLogData,
          'deviceImei': imei,
          'createdAt': FieldValue.serverTimestamp(),
          'syncedAt': DateTime.now().toIso8601String(),
        };

        // Use add() instead of set() to ensure we don't overwrite anything
        batch.set(docRef, logData);
      }

      await batch.commit();
      print('✅ Successfully uploaded ${logs.length} $logType logs to Firebase for IMEI: $imei');
    } catch (e) {
      print('❌ Error sending batch logs to Firebase: $e');
      print('❌ Error details: ${e.toString()}');
    }
  }


} 