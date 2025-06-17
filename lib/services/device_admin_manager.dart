import 'package:flutter/services.dart';

class DeviceAdminManager {
  static const MethodChannel _channel = MethodChannel('device_admin_channel');

  static Future<void> requestDeviceAdmin() async {
    try {
      await _channel.invokeMethod('requestDeviceAdmin');
    } on PlatformException catch (e) {
      print("Failed to request device admin: '${e.message}'");
    }
  }

  static Future<bool> isDeviceAdminActive() async {
    try {
      final bool result = await _channel.invokeMethod('isDeviceAdminActive');
      return result;
    } on PlatformException catch (e) {
      print("Failed to check device admin status: '${e.message}'");
      return false;
    }
  }
}