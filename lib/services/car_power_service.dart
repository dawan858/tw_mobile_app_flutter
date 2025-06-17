import 'package:flutter/services.dart';

class CarPowerService {
  static const MethodChannel _channel = MethodChannel('com.trackingWorld/car_power');
  static final CarPowerService _instance = CarPowerService._internal();
  
  factory CarPowerService() {
    return _instance;
  }

  CarPowerService._internal() {
    _channel.setMethodCallHandler(_handleMethodCall);
  }

  bool _isAccOn = false;
  Function(bool)? onAccStateChanged;

  bool get isAccOn => _isAccOn;

  Future<void> startMonitoring() async {
    try {
      await _channel.invokeMethod('startMonitoring');
    } catch (e) {
      print('Error starting car power monitoring: $e');
    }
  }

  Future<void> stopMonitoring() async {
    try {
      await _channel.invokeMethod('stopMonitoring');
    } catch (e) {
      print('Error stopping car power monitoring: $e');
    }
  }

  Future<bool> getCurrentAccState() async {
    try {
      final bool state = await _channel.invokeMethod('getCurrentAccState');
      _isAccOn = state;
      return state;
    } catch (e) {
      print('Error getting current ACC state: $e');
      return false;
    }
  }

  Future<dynamic> _handleMethodCall(MethodCall call) async {
    switch (call.method) {
      case 'onAccStateChanged':
        _isAccOn = call.arguments as bool;
        onAccStateChanged?.call(_isAccOn);
        break;
      default:
        print('Unknown method called: ${call.method}');
    }
  }
} 