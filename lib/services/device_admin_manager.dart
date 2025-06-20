import 'package:flutter/services.dart';
import 'package:flutter/material.dart';

class DeviceAdminManager {
  static const MethodChannel _channel = MethodChannel('device_admin_channel');

  /// Request device admin permission
  static Future<bool> requestDeviceAdmin() async {
    try {
      print('🔄 Requesting device admin permission...');
      final result = await _channel.invokeMethod('requestDeviceAdmin');
      print('✅ Device admin permission request initiated');
      return result == true;
    } on PlatformException catch (e) {
      print("❌ Failed to request device admin: '${e.message}'");
      print("   - Error code: ${e.code}");
      print("   - Error details: ${e.details}");
      return false;
    } catch (e) {
      print("❌ Unexpected error requesting device admin: $e");
      return false;
    }
  }

  /// Check if device admin is currently active
  static Future<bool> isDeviceAdminActive() async {
    try {
      print('🔄 Checking device admin status...');
      final bool result = await _channel.invokeMethod('isDeviceAdminActive');
      print('✅ Device admin status: $result');
      return result;
    } on PlatformException catch (e) {
      print("❌ Failed to check device admin status: '${e.message}'");
      print("   - Error code: ${e.code}");
      print("   - Error details: ${e.details}");
      return false;
    } catch (e) {
      print("❌ Unexpected error checking device admin status: $e");
      return false;
    }
  }

  /// Request device admin permission with user-friendly dialog
  static Future<bool> requestDeviceAdminWithDialog(BuildContext context) async {
    try {
      // First check if already active
      final isActive = await isDeviceAdminActive();
      if (isActive) {
        print('✅ Device admin already active');
        return true;
      }

      // Show explanation dialog
      final shouldProceed = await _showDeviceAdminDialog(context);
      if (!shouldProceed) {
        print('❌ User cancelled device admin request');
        return false;
      }

      // Request permission
      final success = await requestDeviceAdmin();
      if (success) {
        print('✅ Device admin permission request successful');
        return true;
      } else {
        print('❌ Device admin permission request failed');
        // Show error dialog
        await _showDeviceAdminErrorDialog(context);
        return false;
      }
    } catch (e) {
      print('❌ Error in device admin request flow: $e');
      return false;
    }
  }

  /// Show dialog explaining device admin permission
  static Future<bool> _showDeviceAdminDialog(BuildContext context) async {
    return await showDialog<bool>(
      context: context,
      barrierDismissible: false,
      builder: (BuildContext context) {
        return AlertDialog(
          title: const Text('Device Admin Permission Required'),
          content: const Text(
            'This app requires device admin privileges for security features and reliable background operation. '
            'Without this permission, the app may not work properly in the background.\n\n'
            'Device admin allows the app to:\n'
            '• Run reliably in the background\n'
            '• Maintain GPS tracking when the screen is off\n'
            '• Ensure the app cannot be easily stopped\n'
            '• Provide better security for your tracking data'
          ),
          actions: <Widget>[
            TextButton(
              child: const Text('Cancel'),
              onPressed: () {
                Navigator.of(context).pop(false);
              },
            ),
            ElevatedButton(
              child: const Text('Grant Permission'),
              onPressed: () {
                Navigator.of(context).pop(true);
              },
            ),
          ],
        );
      },
    ) ?? false;
  }

  /// Show error dialog when device admin request fails
  static Future<void> _showDeviceAdminErrorDialog(BuildContext context) async {
    await showDialog<void>(
      context: context,
      barrierDismissible: false,
      builder: (BuildContext context) {
        return AlertDialog(
          title: const Text('Device Admin Permission Failed'),
          content: const Text(
            'Failed to request device admin permission. This permission is important for reliable background operation.\n\n'
            'You can try again or manually enable device admin in your device settings.'
          ),
          actions: <Widget>[
            TextButton(
              child: const Text('Try Again'),
              onPressed: () {
                Navigator.of(context).pop();
                requestDeviceAdminWithDialog(context);
              },
            ),
            TextButton(
              child: const Text('Skip'),
              onPressed: () {
                Navigator.of(context).pop();
              },
            ),
          ],
        );
      },
    );
  }

  /// Get detailed device admin status
  static Future<Map<String, dynamic>> getDeviceAdminStatus() async {
    try {
      final isActive = await isDeviceAdminActive();
      return {
        'isActive': isActive,
        'isRequired': true,
        'description': 'Device admin privileges are required for reliable background operation',
        'lastChecked': DateTime.now().toIso8601String(),
      };
    } catch (e) {
      return {
        'isActive': false,
        'isRequired': true,
        'description': 'Failed to check device admin status',
        'error': e.toString(),
        'lastChecked': DateTime.now().toIso8601String(),
      };
    }
  }
}