import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:fluttertoast/fluttertoast.dart';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';
import 'database_helper.dart';
import 'logs.dart';

class LogUploadService {
  static final LogUploadService _instance = LogUploadService._internal();
  final DatabaseHelper _dbHelper = DatabaseHelper();
  
  // Endpoint configuration
  static const String _logEndpoint = 'https://3cvgvesw25.execute-api.ap-south-1.amazonaws.com/device-logs';
  
  // Upload queue and processing
  final List<Map<String, dynamic>> _uploadQueue = [];
  Timer? _uploadTimer;
  bool _isUploading = false;
  String? _deviceImei;
  
  // Track last uploaded logs to avoid duplicates
  String? _lastUploadedExceptionLog;
  String? _lastUploadedIgnitionLog;
  
  // Retry configuration
  static const int _maxRetries = 3;
  static const Duration _retryDelay = Duration(seconds: 30);
  static const Duration _uploadInterval = Duration(seconds: 10); // Upload every 10 seconds
  
  factory LogUploadService() => _instance;
  
  LogUploadService._internal() {
    _initialize();
  }
  
  Future<void> _initialize() async {
    await _loadDeviceImei();
    _startUploadTimer();
  }
  
  Future<void> _loadDeviceImei() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      _deviceImei = prefs.getString('imei') ?? 'unknown';
      print('📱 LogUploadService: Device IMEI loaded: $_deviceImei');
    } catch (e) {
      print('❌ Error loading device IMEI: $e');
      _deviceImei = 'unknown';
    }
  }
  
  void _startUploadTimer() {
    _uploadTimer?.cancel();
    _uploadTimer = Timer.periodic(_uploadInterval, (timer) {
      _processUploadQueue();
    });
    print('🔄 LogUploadService: Upload timer started (${_uploadInterval.inSeconds}s interval)');
  }
  
  /// Upload exception log to the endpoint
  Future<void> uploadExceptionLog({
    required String main,
    required String details,
    required String timestamp,
  }) async {
    if (_deviceImei == null || _deviceImei == 'unknown') {
      await _loadDeviceImei();
    }
    
    // Check if this is a duplicate of the last uploaded exception log
    if (_lastUploadedExceptionLog == main) {
      print('⏭️ Skipping duplicate exception log: ${main.substring(0, main.length > 30 ? 30 : main.length)}...');
      return;
    }
    
    final logData = {
      'id': _deviceImei ?? 'unknown',
      'message': main,
      'details': details,
      'log_type': 'Exception',
      'timestamp': timestamp,
    };
    
    _uploadQueue.add(logData);
    _lastUploadedExceptionLog = main;
    print('📤 Exception log queued for upload: ${main.substring(0, main.length > 50 ? 50 : main.length)}...');
    
    // Try immediate upload (unawaited to prevent blocking)
    unawaited(_processUploadQueue());
  }
  
  /// Upload ignition log to the endpoint
  Future<void> uploadIgnitionLog({
    required String message,
    required String details,
    required String logType,
    required String timestamp,
  }) async {
    if (_deviceImei == null || _deviceImei == 'unknown') {
      await _loadDeviceImei();
    }

    // Check if this is a duplicate of the last uploaded ignition log
    if (_lastUploadedIgnitionLog == message) {
      print('⏭️ Skipping duplicate ignition log: ${message.substring(0, message.length > 30 ? 30 : message.length)}...');
      return;
    }
    
    final logData = {
      'id': _deviceImei ?? 'unknown',
      'message': message,
      'details': details,
      'log_type': 'Ignition',
      'timestamp': timestamp,
    };

    
    _uploadQueue.add(logData);
    _lastUploadedIgnitionLog = message;
    print('📤 Ignition log queued for upload: ${message.substring(0, message.length > 50 ? 50 : message.length)}...');
    
    // Try immediate upload (unawaited to prevent blocking)
    unawaited(_processUploadQueue());
  }
  
  /// Process the upload queue
  Future<void> _processUploadQueue() async {
    if (_isUploading || _uploadQueue.isEmpty) {
      return;
    }


    // Small delay to prevent rapid successive calls
    await Future.delayed(const Duration(milliseconds: 100));

    // Double-check after delay
    if (_isUploading || _uploadQueue.isEmpty) {
      return;
    }
    
    _isUploading = true;
    List<Map<String, dynamic>> logsToUpload = [];
    
    try {
      logsToUpload = List<Map<String, dynamic>>.from(_uploadQueue);
      _uploadQueue.clear();
      
      print('📤 Uploading ${logsToUpload.length} logs to endpoint...');
      for (final logData in logsToUpload) {
        await _uploadSingleLog(logData);
      }
      
      print('✅ Successfully uploaded ${logsToUpload.length} logs');
      
    } catch (e) {
      Fluttertoast.showToast(
          msg: "❌ Error processing upload queue: $e",
          toastLength: Toast.LENGTH_SHORT,
          gravity: ToastGravity.CENTER,
          timeInSecForIosWeb: 1,
          backgroundColor: Colors.red,
          textColor: Colors.white,
          fontSize: 16.0
      );
      print('❌ Error processing upload queue: $e');
      // Re-add failed logs to queue for retry
      if (logsToUpload.isNotEmpty) {
        _uploadQueue.addAll(logsToUpload);
      }
    } finally {
      _isUploading = false;
    }
  }
  
  /// Upload a single log with retry mechanism
  Future<void> _uploadSingleLog(Map<String, dynamic> logData, {int retryCount = 0}) async {
    try {
      print('📤 Attempting to upload log (attempt ${retryCount + 1}/$_maxRetries):');
      print('📤 Endpoint: $_logEndpoint');
      print('📤 Data: ${jsonEncode(logData)}');
      
      final response = await http.post(
        Uri.parse(_logEndpoint),
        headers: {
          'Content-Type': 'application/json',
        },
        body: jsonEncode(logData),
      ).timeout(const Duration(seconds: 30));
      appendLog('📤 :_uploadSingleLog\n $_logEndpoint ${response.body} :::: $logData');


      print('📤 Response status: ${response.statusCode}');
      print('📤 Response body: ${response.body}');
      
      if (response.statusCode == 200 || response.statusCode == 201) {
        return;
      } else {
        throw Exception('HTTP ${response.statusCode}: ${response.body}');
      }
      
    } catch (e) {
      print('❌ Error uploading log (attempt ${retryCount + 1}/$_maxRetries): $e');
      
      if (retryCount < _maxRetries - 1) {
        print('⏳ Waiting ${_retryDelay.inSeconds} seconds before retry...');
        // Wait before retry
        await Future.delayed(_retryDelay);
        return _uploadSingleLog(logData, retryCount: retryCount + 1);
      } else {
        // Max retries reached, add back to queue
        _uploadQueue.add(logData);
      }
    }
  }
  
  /// Upload only the latest logs from database
  Future<void> uploadExistingLogs() async {
    try {
      print('🔄 Uploading latest logs from database...');
      
      // Upload only the latest exception log
      final exceptionLogs = await _dbHelper.getExceptionLogs(limit: 1);
      if (exceptionLogs.isNotEmpty) {
        final latestException = exceptionLogs.first;
        await uploadExceptionLog(
          main: latestException['main'] ?? '',
          details: latestException['details'] ?? '',
          timestamp: _formatTimestampForApi(latestException['created_at'] ?? ''),
        );
        print('📤 Latest exception log queued for upload');
      }
      
      // Upload only the latest ignition log
      final ignitionLogs = await _dbHelper.getIgnitionLogs(limit: 1);
      if (ignitionLogs.isNotEmpty) {
        final latestIgnition = ignitionLogs.first;
        await uploadIgnitionLog(
          message: latestIgnition['message'] ?? '',
          details: latestIgnition['details'] ?? '',
          logType: latestIgnition['log_type'] ?? 'info',
          timestamp: _formatTimestampForApi(latestIgnition['timestamp'] ?? ''),
        );
        print('📤 Latest ignition log queued for upload');
      }
      
      print('✅ Latest logs queued for upload');
      
    } catch (e) {
      print('❌ Error uploading latest logs: $e');
    }
  }
  
  /// Format timestamp for API
  String _formatTimestampForApi(String timestamp) {
    try {
      // Handle different timestamp formats
      if (timestamp.contains('/')) {
        // Format: dd/MM/yyyy HH:mm:ss.SSS
        final parts = timestamp.split(' ');
        if (parts.length >= 2) {
          final datePart = parts[0].split('/');
          final timePart = parts[1].split('.')[0]; // Remove milliseconds
          if (datePart.length == 3) {
            final day = datePart[0].padLeft(2, '0');
            final month = datePart[1].padLeft(2, '0');
            final year = datePart[2];
            return '${year}-${month}-${day}T${timePart}.000Z';
          }
        }
      } else {
        // Try to parse ISO format
        final dateTime = DateTime.parse(timestamp);
        return dateTime.toUtc().toIso8601String();
      }
    } catch (e) {
      print('❌ Error formatting timestamp: $e');
    }
    
    // Fallback to current time
    return DateTime.now().toUtc().toIso8601String();
  }
  
  /// Test connection to the endpoint
  Future<bool> testConnection() async {
    try {
      print('🔍 Testing connection to endpoint: $_logEndpoint');
      
      final testData = {
        'id': 'test-device',
        'message': 'Connection test',
        'details': 'Testing if endpoint is reachable',
        'log_type': 'Test',
        'timestamp': DateTime.now().toIso8601String(),
      };
      
      print('🔍 Sending test data: ${jsonEncode(testData)}');
      
      final response = await http.post(
        Uri.parse(_logEndpoint),
        headers: {
          'Content-Type': 'application/json',
        },
        body: jsonEncode(testData),
      ).timeout(const Duration(seconds: 10));
      
      print('🔍 Test response status: ${response.statusCode}');
      print('🔍 Test response body: ${response.body}');
      
      return response.statusCode == 200 || response.statusCode == 201;
    } catch (e) {
      print('❌ Connection test failed: $e');
      return false;
    }
  }
  
  /// Get upload service status
  Map<String, dynamic> getStatus() {
    return {
      'isUploading': _isUploading,
      'queueSize': _uploadQueue.length,
      'deviceImei': _deviceImei,
      'endpoint': _logEndpoint,
      'uploadInterval': _uploadInterval.inSeconds,
      'lastExceptionLog': _lastUploadedExceptionLog,
      'lastIgnitionLog': _lastUploadedIgnitionLog,
    };
  }
  
  /// Upload only the latest logs from database
  Future<void> uploadLatestLogs() async {
    try {
      print('🔄 Uploading latest logs from database...');
      
      // Upload only the latest exception log
      final exceptionLogs = await _dbHelper.getExceptionLogs(limit: 1);
      if (exceptionLogs.isNotEmpty) {
        final latestException = exceptionLogs.first;
        await uploadExceptionLog(
          main: latestException['main'] ?? '',
          details: latestException['details'] ?? '',
          timestamp: _formatTimestampForApi(latestException['created_at'] ?? ''),
        );
        print('📤 Latest exception log queued for upload');
      }
      
      // Upload only the latest ignition log
      final ignitionLogs = await _dbHelper.getIgnitionLogs(limit: 1);
      if (ignitionLogs.isNotEmpty) {
        final latestIgnition = ignitionLogs.first;
        await uploadIgnitionLog(
          message: latestIgnition['message'] ?? '',
          details: latestIgnition['details'] ?? '',
          logType: latestIgnition['log_type'] ?? 'info',
          timestamp: _formatTimestampForApi(latestIgnition['timestamp'] ?? ''),
        );
        print('📤 Latest ignition log queued for upload');
      }
      
      print('✅ Latest logs queued for upload');
      
    } catch (e) {
      print('❌ Error uploading latest logs: $e');
    }
  }
  
  /// Force upload all queued logs
  Future<void> forceUpload() async {
    print('🔄 Force upload requested');
    await _processUploadQueue();
  }
  
  /// Get the singleton instance
  static LogUploadService get instance => _instance;
  
  /// Clear duplicate tracking (useful for testing or when you want to upload same log again)
  void clearDuplicateTracking() {
    _lastUploadedExceptionLog = null;
    _lastUploadedIgnitionLog = null;
    print('🔄 Duplicate tracking cleared');
  }
  
  /// Stop the upload service
  void dispose() {
    _uploadTimer?.cancel();
    _uploadTimer = null;
    print('🛑 LogUploadService disposed');
  }
} 