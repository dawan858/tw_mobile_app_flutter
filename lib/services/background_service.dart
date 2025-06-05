import 'dart:async';
import 'dart:ui';
import 'package:flutter_background_service/flutter_background_service.dart';
import 'package:flutter_background_service_android/flutter_background_service_android.dart';
import 'package:geolocator/geolocator.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:http/http.dart' as http;
import 'dart:convert';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:device_info_plus/device_info_plus.dart';
import 'package:intl/intl.dart';
import 'sync_service.dart';

@pragma('vm:entry-point')
Future<void> initializeService() async {
  final service = FlutterBackgroundService();
  final FlutterLocalNotificationsPlugin flutterLocalNotificationsPlugin = FlutterLocalNotificationsPlugin();
  final syncService = SyncService();

  // Initialize notification channel
  const AndroidNotificationChannel channel = AndroidNotificationChannel(
    'tracking_service',
    'GPS Tracking Service',
    description: 'This channel is used for GPS tracking notifications',
    importance: Importance.high,
    enableVibration: false,
    playSound: false,
  );

  await flutterLocalNotificationsPlugin
      .resolvePlatformSpecificImplementation<AndroidFlutterLocalNotificationsPlugin>()
      ?.createNotificationChannel(channel);

  await service.configure(
    androidConfiguration: AndroidConfiguration(
      onStart: onStart,
      autoStart: true,
      autoStartOnBoot: true,
      isForegroundMode: true,
      notificationChannelId: 'tracking_service',
      initialNotificationTitle: 'GPS Tracking',
      initialNotificationContent: 'Initializing...',
      foregroundServiceNotificationId: 888,
    ),
    iosConfiguration: IosConfiguration(
      autoStart: false,
      onForeground: onStart,
      onBackground: onIosBackground,
    ),
  );

  // Start the service immediately
  await service.startService();
  await syncService.startPeriodicSync();
}

@pragma('vm:entry-point')
Future<bool> onIosBackground(ServiceInstance service) async {
  return true;
}

@pragma('vm:entry-point')
void onStart(ServiceInstance service) async {
  DartPluginRegistrant.ensureInitialized();

  if (service is AndroidServiceInstance) {
    service.on('setAsForeground').listen((event) {
      service.setAsForegroundService();
    });

    service.on('setAsBackground').listen((event) {
      service.setAsBackgroundService();
    });
  }

  service.on('stopService').listen((event) {
    service.stopSelf();
  });

  // Initialize location settings
  const LocationSettings locationSettings = LocationSettings(
    accuracy: LocationAccuracy.bestForNavigation,
  );

  // Get last known position
  Position? lastPosition;
  DateTime? lastUpdateTime;
  const minUpdateInterval = Duration(seconds: 3);
  const minDistance = 5.0;
  const minBearingChange = 10.0; // Minimum bearing change to consider it a turn
  const minSpeedForMovement = 1.0; // Minimum speed in m/s to consider it movement

  String calculateReason(Position current, Position? previous) {
    if (previous == null) return 'Initial Position';
    
    // Calculate speed in m/s
    final speed = current.speed;
    
    // Calculate bearing change
    final bearingChange = (current.heading - previous.heading).abs();
    final normalizedBearingChange = bearingChange > 180 ? 360 - bearingChange : bearingChange;
    
    // Calculate distance moved
    final distance = Geolocator.distanceBetween(
      previous.latitude,
      previous.longitude,
      current.latitude,
      current.longitude,
    );

    // Determine reason based on movement patterns
    if (speed < minSpeedForMovement && distance < minDistance) {
      return 'Idle';
    } else if (normalizedBearingChange > minBearingChange) {
      return 'Turn';
    } else {
      return 'Move';
    }
  }

  // Start periodic task
  Timer.periodic(const Duration(seconds: 5), (timer) async {
    if (service is AndroidServiceInstance) {
      try {
        // Check if we should update location
        final now = DateTime.now();
        final shouldUpdate = lastPosition == null ||
            lastUpdateTime == null ||
            now.difference(lastUpdateTime!) > minUpdateInterval;

        if (shouldUpdate) {
          // Get current position
          final position = await Geolocator.getCurrentPosition(
            desiredAccuracy: LocationAccuracy.bestForNavigation,
          );

          // Check if we've moved enough
          if (lastPosition == null ||
              Geolocator.distanceBetween(
                lastPosition!.latitude,
                lastPosition!.longitude,
                position.latitude,
                position.longitude,
              ) > minDistance) {
            
            // Calculate reason for movement
            final reason = calculateReason(position, lastPosition);
            
            // Update last position and time
            lastPosition = position;
            lastUpdateTime = now;

            // Queue for sync with calculated reason
            await _queueLocationData(position, reason);
          }
        }

        // Update notification
        service.setForegroundNotificationInfo(
          title: "GPS Tracking Active",
          content: "Last update: ${lastUpdateTime?.toString() ?? 'Never'}",
        );
      } catch (e) {
        print('Error in background service: $e');
        // Try to restart the service if it fails
        if (service is AndroidServiceInstance) {
          service.setForegroundNotificationInfo(
            title: "GPS Tracking Error",
            content: "Attempting to restart...",
          );
          // Wait a bit before restarting
          await Future.delayed(const Duration(seconds: 5));
          service.setAsForegroundService();
        }
      }
    }
  });
}

Future<void> _queueLocationData(Position position, String reason) async {
  try {
    final prefs = await SharedPreferences.getInstance();
    final imei = prefs.getString('imei') ?? 'unknown';
    
    final locationData = {
      'latitude': position.latitude,
      'longitude': position.longitude,
      'accuracy': position.accuracy,
      'altitude': position.altitude,
      'speed': position.speed,
      'bearing': position.heading,
      'imei': imei,
      'timestamp': DateTime.now().toIso8601String(),
      'deviceRDT': DateFormat("dd/MM/yyyy HH:mm:ss.SSS").format(DateTime.now()),
      'gmtSettings': "GMT+${DateTime.now().timeZoneOffset.inHours}:00 ${DateTime.now().year}",
      'igStatus': 1,
      'localPrimaryId': DateTime.now().millisecondsSinceEpoch % 100000,
      'name': (await DeviceInfoPlugin().androidInfo).model,
      'phoneNo': (await DeviceInfoPlugin().androidInfo).serialNumber,
      'provider': 'fused',
      'reason': reason,
      'versionNo': 'v ${(await DeviceInfoPlugin().androidInfo).version.release}',
    };

    // Queue the data for sync
    await SyncService().queueLocationData(locationData);
  } catch (e) {
    print('Error queueing location data: $e');
  }
} 