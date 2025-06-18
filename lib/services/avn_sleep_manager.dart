import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'dart:async';

class AvnSleepManager {
  static const MethodChannel _channel = MethodChannel('com.trackingWorld.tracking/avn_sleep');
  static final AvnSleepManager _instance = AvnSleepManager._internal();
  
  factory AvnSleepManager() {
    return _instance;
  }

  AvnSleepManager._internal() {
    _channel.setMethodCallHandler(_handleMethodCall);
  }

  bool _isSleeping = false;
  bool _isInitialized = false;
  Function(bool)? onSleepStateChanged;
  final StreamController<bool> _sleepStateController = StreamController<bool>.broadcast();

  bool get isSleeping => _isSleeping;
  bool get isInitialized => _isInitialized;
  Stream<bool> get sleepStateStream => _sleepStateController.stream;

  Future<bool> initialize() async {
    try {
      print('AvnSleepManager: Initializing...');
      
      // Load current sleep state from SharedPreferences
      await _loadSleepState();
      
      // Start monitoring
      await startMonitoring();
      
      _isInitialized = true;
      print('AvnSleepManager: Initialized successfully. Current sleep state: $_isSleeping');
      return true;
    } catch (e) {
      print('AvnSleepManager: Initialization failed: $e');
      _isInitialized = false;
      return false;
    }
  }

  Future<void> startMonitoring() async {
    try {
      print('AvnSleepManager: Starting sleep monitoring...');
      await _channel.invokeMethod('startSleepMonitoring');
      print('AvnSleepManager: Sleep monitoring started successfully');
    } catch (e) {
      print('AvnSleepManager: Error starting sleep monitoring: $e');
      throw e;
    }
  }

  Future<void> stopMonitoring() async {
    try {
      print('AvnSleepManager: Stopping sleep monitoring...');
      await _channel.invokeMethod('stopSleepMonitoring');
      _isInitialized = false;
      print('AvnSleepManager: Sleep monitoring stopped successfully');
    } catch (e) {
      print('AvnSleepManager: Error stopping sleep monitoring: $e');
      throw e;
    }
  }

  Future<bool> getCurrentSleepState() async {
    try {
      print('AvnSleepManager: Getting current sleep state...');
      final bool state = await _channel.invokeMethod('getCurrentSleepState');
      
      if (state != _isSleeping) {
        _updateSleepState(state);
      }
      
      print('AvnSleepManager: Current sleep state: $state');
      return state;
    } catch (e) {
      print('AvnSleepManager: Error getting current sleep state: $e');
      throw e;
    }
  }

  void _updateSleepState(bool newState) {
    if (newState != _isSleeping) {
      final oldState = _isSleeping;
      _isSleeping = newState;
      
      print('AvnSleepManager: Sleep state changed from $oldState to $newState');
      
      // Store in SharedPreferences
      _storeSleepState(newState);
      
      // Notify listeners
      onSleepStateChanged?.call(_isSleeping);
      _sleepStateController.add(_isSleeping);
    }
  }

  Future<void> _loadSleepState() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final isSleeping = prefs.getBool('flutter.is_sleeping') ?? false;
      _isSleeping = isSleeping;
      print('AvnSleepManager: Loaded sleep state: $isSleeping');
    } catch (e) {
      print('AvnSleepManager: Error loading sleep state: $e');
    }
  }

  Future<void> _storeSleepState(bool isSleeping) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool('flutter.is_sleeping', isSleeping);
      await prefs.setInt('flutter.sleep_state_timestamp', DateTime.now().millisecondsSinceEpoch);
      print('AvnSleepManager: Stored sleep state: $isSleeping');
    } catch (e) {
      print('AvnSleepManager: Error storing sleep state: $e');
    }
  }

  Future<dynamic> _handleMethodCall(MethodCall call) async {
    print('AvnSleepManager: Received method call: ${call.method}');
    
    switch (call.method) {
      case 'onSleepStateChanged':
        final bool newState = call.arguments as bool;
        print('AvnSleepManager: Sleep state changed to: $newState');
        _updateSleepState(newState);
        break;
      default:
        print('AvnSleepManager: Unknown method called: ${call.method}');
    }
  }

  void dispose() {
    try {
      stopMonitoring();
      _sleepStateController.close();
    } catch (e) {
      print('AvnSleepManager: Error during dispose: $e');
    }
  }

  // Utility method to check if AVN sleep service is available
  Future<bool> isServiceAvailable() async {
    try {
      await _channel.invokeMethod('getCurrentSleepState');
      return true;
    } catch (e) {
      print('AvnSleepManager: Service not available: $e');
      return false;
    }
  }

  // Method to get sleep statistics
  Future<Map<String, dynamic>> getSleepStats() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final isSleeping = prefs.getBool('flutter.is_sleeping') ?? false;
      final sleepTimestamp = prefs.getInt('flutter.sleep_state_timestamp') ?? 0;
      
      return {
        'isSleeping': isSleeping,
        'sleepTimestamp': sleepTimestamp,
        'sleepDuration': isSleeping ? DateTime.now().millisecondsSinceEpoch - sleepTimestamp : 0,
        'lastSleepState': isSleeping ? 'Sleeping' : 'Awake',
      };
    } catch (e) {
      print('AvnSleepManager: Error getting sleep stats: $e');
      return {
        'isSleeping': false,
        'sleepTimestamp': 0,
        'sleepDuration': 0,
        'lastSleepState': 'Unknown',
      };
    }
  }
} 