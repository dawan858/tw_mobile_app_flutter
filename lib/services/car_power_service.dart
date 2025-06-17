import 'package:flutter/services.dart';
import 'dart:async';

class CarPowerService {
  static const MethodChannel _channel = MethodChannel('com.trackingWorld.tracking/car_power');
  static final CarPowerService _instance = CarPowerService._internal();
  
  factory CarPowerService() {
    return _instance;
  }

  CarPowerService._internal() {
    _channel.setMethodCallHandler(_handleMethodCall);
  }

  bool _isAccOn = false;
  bool _isInitialized = false;
  Function(bool)? onAccStateChanged;
  final StreamController<bool> _accStateController = StreamController<bool>.broadcast();

  bool get isAccOn => _isAccOn;
  bool get isInitialized => _isInitialized;
  Stream<bool> get accStateStream => _accStateController.stream;

  Future<bool> initialize() async {
    try {
      print('CarPowerService: Initializing...');
      
      // Start monitoring
      await startMonitoring();
      
      // Get initial state
      await getCurrentAccState();
      
      _isInitialized = true;
      print('CarPowerService: Initialized successfully. Initial ACC state: $_isAccOn');
      return true;
    } catch (e) {
      print('CarPowerService: Initialization failed: $e');
      _isInitialized = false;
      return false;
    }
  }

  Future<void> startMonitoring() async {
    try {
      print('CarPowerService: Starting monitoring...');
      await _channel.invokeMethod('startMonitoring');
      print('CarPowerService: Monitoring started successfully');
    } catch (e) {
      print('CarPowerService: Error starting car power monitoring: $e');
      throw e;
    }
  }

  Future<void> stopMonitoring() async {
    try {
      print('CarPowerService: Stopping monitoring...');
      await _channel.invokeMethod('stopMonitoring');
      _isInitialized = false;
      print('CarPowerService: Monitoring stopped successfully');
    } catch (e) {
      print('CarPowerService: Error stopping car power monitoring: $e');
      throw e;
    }
  }

  Future<bool> getCurrentAccState() async {
    try {
      print('CarPowerService: Getting current ACC state...');
      final bool state = await _channel.invokeMethod('getCurrentAccState');
      
      if (state != _isAccOn) {
        _updateAccState(state);
      }
      
      print('CarPowerService: Current ACC state: $state');
      return state;
    } catch (e) {
      print('CarPowerService: Error getting current ACC state: $e');
      throw e;
    }
  }

  void _updateAccState(bool newState) {
    if (newState != _isAccOn) {
      final oldState = _isAccOn;
      _isAccOn = newState;
      
      print('CarPowerService: ACC state changed from $oldState to $newState');
      
      // Notify listeners
      onAccStateChanged?.call(_isAccOn);
      _accStateController.add(_isAccOn);
    }
  }

  Future<dynamic> _handleMethodCall(MethodCall call) async {
    print('CarPowerService: Received method call: ${call.method}');
    
    switch (call.method) {
      case 'onAccStateChanged':
        final bool newState = call.arguments as bool;
        print('CarPowerService: ACC state changed to: $newState');
        _updateAccState(newState);
        break;
      default:
        print('CarPowerService: Unknown method called: ${call.method}');
    }
  }

  void dispose() {
    try {
      stopMonitoring();
      _accStateController.close();
    } catch (e) {
      print('CarPowerService: Error during dispose: $e');
    }
  }

  // Utility method to check if car power service is available
  Future<bool> isServiceAvailable() async {
    try {
      await _channel.invokeMethod('getCurrentAccState');
      return true;
    } catch (e) {
      print('CarPowerService: Service not available: $e');
      return false;
    }
  }

  // Method to retry initialization if failed
  Future<bool> retryInitialization({int maxRetries = 3}) async {
    for (int i = 0; i < maxRetries; i++) {
      print('CarPowerService: Initialization attempt ${i + 1}/$maxRetries');
      
      if (await initialize()) {
        return true;
      }
      
      if (i < maxRetries - 1) {
        await Future.delayed(Duration(seconds: 2));
      }
    }
    
    print('CarPowerService: All initialization attempts failed');
    return false;
  }
}