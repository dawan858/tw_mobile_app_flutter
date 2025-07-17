import 'dart:async';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'database_helper.dart';

class IgnitionMonitorService {
  static final IgnitionMonitorService _instance = IgnitionMonitorService._internal();
  final DatabaseHelper _dbHelper = DatabaseHelper();
  Timer? _monitoringTimer;
  bool _isMonitoring = false;
  int _lastIgStatus = -1; // Track last known igStatus
  
  // Method channel for native service communication
  static const MethodChannel _serviceChannel = MethodChannel('com.example.twtracking/service');

  factory IgnitionMonitorService() => _instance;

  IgnitionMonitorService._internal() {
    _initMonitoring();
  }

  void _initMonitoring() {
    // Start monitoring when service is created
    startMonitoring();
  }

  Future<void> startMonitoring() async {
    if (_isMonitoring) {
      print('Ignition monitoring already active');
      return;
    }

    _isMonitoring = true;
    print('🚀 Starting ignition monitoring service');

    // Log the start of monitoring
    await _dbHelper.insertIgnitionLog(
      message: 'Ignition monitoring started',
      details: 'Continuous monitoring of igStatus and power state changes',
      logType: 'monitoring',
    );

    // Start periodic monitoring
    _monitoringTimer = Timer.periodic(const Duration(seconds: 5), (timer) async {
      await _checkIgnitionStatus();
    });

    // Initial check
    await _checkIgnitionStatus();
  }

  Future<void> stopMonitoring() async {
    if (!_isMonitoring) {
      return;
    }

    _isMonitoring = false;
    _monitoringTimer?.cancel();
    _monitoringTimer = null;

    print('🛑 Stopping ignition monitoring service');

    await _dbHelper.insertIgnitionLog(
      message: 'Ignition monitoring stopped',
      details: 'Monitoring service deactivated',
      logType: 'monitoring',
    );
  }

  Future<void> _checkIgnitionStatus() async {
    try {
      // Get current igStatus from native service
      int currentIgStatus = 0;
      try {
        currentIgStatus = await _serviceChannel.invokeMethod('getCurrentIgStatus');
      } catch (e) {
        print('❌ Error getting igStatus from native service: $e');
        // Fallback to SharedPreferences
        final prefs = await SharedPreferences.getInstance();
        currentIgStatus = prefs.getInt('current_ig_status') ?? 0;
      }

      // Check if igStatus has changed
      if (currentIgStatus != _lastIgStatus) {
        await _logIgnitionChange(_lastIgStatus, currentIgStatus);
        _lastIgStatus = currentIgStatus;
      }

      // Get power state from native service if available
      await _checkPowerState();

    } catch (e) {
      print('❌ Error in ignition status check: $e');
      await _dbHelper.insertIgnitionLog(
        message: 'Ignition status check error',
        details: 'Error: $e',
        logType: 'error',
      );
    }
  }

  Future<void> _checkPowerState() async {
    try {
      // Try to get power state from native service
      final powerState = await _serviceChannel.invokeMethod('getPowerState');
      
      if (powerState != null) {
        final state = powerState['state'] ?? 0;
        final isAccOn = powerState['isAccOn'] ?? false;
        
        await _dbHelper.insertIgnitionLog(
          message: 'Power state analysis: state=$state (${_getPowerStateName(state)}), isAccOn=$isAccOn',
          details: 'Power state: $state, ACC Status: $isAccOn',
          logType: state == 1 ? 'power_state_on' : 'power_state_off',
        );
      }
    } catch (e) {
      // Power state check failed, this is normal if the method doesn't exist
      print('ℹ️ Power state check not available: $e');
    }
  }

  Future<void> _logIgnitionChange(int oldStatus, int newStatus) async {
    final oldStatusName = _getIgStatusName(oldStatus);
    final newStatusName = _getIgStatusName(newStatus);
    
    await _dbHelper.insertIgnitionLog(
      message: 'Ignition status changed: $oldStatusName → $newStatusName',
      details: 'igStatus: $oldStatus → $newStatus',
      logType: newStatus == 1 ? 'acc_on' : 'acc_off',
    );

    print('🔄 Ignition status changed: $oldStatusName ($oldStatus) → $newStatusName ($newStatus)');
  }

  String _getIgStatusName(int status) {
    switch (status) {
      case 0:
        return 'ACC_OFF';
      case 1:
        return 'ACC_ON';
      default:
        return 'UNKNOWN';
    }
  }

  String _getPowerStateName(int state) {
    switch (state) {
      case 0:
        return 'POWER_STATE_OFF';
      case 1:
        return 'POWER_STATE_ON';
      case 2:
        return 'POWER_STATE_SUSPEND';
      case 3:
        return 'POWER_STATE_HIBERNATE';
      default:
        return 'POWER_STATE_UNKNOWN';
    }
  }

  // Manual logging methods for external use
  Future<void> logPowerStateChange(int state, bool isAccOn) async {
    await _dbHelper.insertIgnitionLog(
      message: 'Power state analysis: state=$state (${_getPowerStateName(state)}), isAccOn=$isAccOn',
      details: 'Power state: $state, ACC Status: $isAccOn',
      logType: state == 1 ? 'power_state_on' : 'power_state_off',
    );
  }

  Future<void> logIgStatusMonitoring(String message, {String details = ''}) async {
    await _dbHelper.insertIgnitionLog(
      message: message,
      details: details,
      logType: 'monitoring',
    );
  }

  Future<void> logIgnitionEvent(String message, {String details = '', String logType = 'info'}) async {
    await _dbHelper.insertIgnitionLog(
      message: message,
      details: details,
      logType: logType,
    );
  }

  // Get current monitoring status
  bool get isMonitoring => _isMonitoring;
  
  int get lastIgStatus => _lastIgStatus;

  // Cleanup
  void dispose() {
    stopMonitoring();
  }
} 