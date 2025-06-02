import 'package:flutter/services.dart';

class AutoStartService {
  static const MethodChannel _launchChannel = MethodChannel('com.trackingWorld.tracking/launch');
  static const MethodChannel _serviceChannel = MethodChannel('com.trackingWorld.tracking/service');
  static const MethodChannel _deviceChannel = MethodChannel('com.trackingWorld.tracking/device_info');
  
  static Future<Map<String, dynamic>> getLaunchDetails() async {
    try {
      final Map<dynamic, dynamic> details = await _launchChannel.invokeMethod('getLaunchDetails');
      return {
        'autoStart': details['autoStart'] ?? false,
        'startedBy': details['startedBy'] ?? '',
      };
    } on PlatformException catch (e) {
      print('Error getting launch details: ${e.message}');
      return {
        'autoStart': false,
        'startedBy': '',
      };
    }
  }

  static Future<bool> startService() async {
    try {
      return await _serviceChannel.invokeMethod('startService');
    } on PlatformException catch (e) {
      print('Error starting service: ${e.message}');
      return false;
    }
  }

  static Future<bool> stopService() async {
    try {
      return await _serviceChannel.invokeMethod('stopService');
    } on PlatformException catch (e) {
      print('Error stopping service: ${e.message}');
      return false;
    }
  }

  static Future<bool> isServiceRunning() async {
    try {
      return await _serviceChannel.invokeMethod('isServiceRunning');
    } on PlatformException catch (e) {
      print('Error checking service status: ${e.message}');
      return false;
    }
  }

  static Future<String?> getImei() async {
    try {
      return await _deviceChannel.invokeMethod('getImei');
    } on PlatformException catch (e) {
      print('Error getting IMEI: ${e.message}');
      return null;
    }
  }
} 