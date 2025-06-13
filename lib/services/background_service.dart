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
  await loadConfiguration(); // Load configuration before setting up location updates

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

  bool _isMoving = false;
  int _lastMovementTime = 0;
  int _lastStopTime = 0;
  int stopTimer = 130; // Default 130 seconds
  double overSpeedingThreshold = 60.0; // Default 60 km/h
  double angleThreshold = 45.0; // Default 45 degrees

  String calculateReason(Position currentLocation) {
    if (lastPosition == null) {
      return 'Initial Position';
    }

    // Calculate speed in km/h and clamp to zero if very small or negative
    var speed = currentLocation.speed * 3.6;
    if (speed < 0.5) speed = 0;

    // Calculate bearing change
    final bearingChange = (currentLocation.heading - lastPosition!.heading).abs();
    final normalizedBearingChange = bearingChange > 180 ? 360 - bearingChange : bearingChange;

    // Calculate distance moved
    final distance = Geolocator.distanceBetween(
      lastPosition!.latitude,
      lastPosition!.longitude,
      currentLocation.latitude,
      currentLocation.longitude,
    );

    // Update movement status
    if (speed >= 1.0 || distance > 5) {
      if (!_isMoving) {
        _isMoving = true;
        _lastMovementTime = DateTime.now().millisecondsSinceEpoch;
      }
      _lastStopTime = DateTime.now().millisecondsSinceEpoch;
    } else {
      if (_isMoving && (DateTime.now().millisecondsSinceEpoch - _lastStopTime) > stopTimer * 1000) {
        _isMoving = false;
      }
    }

    // If speed is less than 1.0, set reason to Idle
    if (speed < 1.0) {
      return 'Idle';
    } else if (speed > overSpeedingThreshold) {
      return 'Over Speeding';
    } else if (!_isMoving) {
      return 'Idle';
    } else if (speed >= 5 && normalizedBearingChange > angleThreshold) {
      return 'Turn';
    } else if (_isMoving) {
      return 'Move';
    }
    
    return 'Idle'; // Default case
  }

  // Start periodic task for instant updates (every second)
  Timer.periodic(const Duration(seconds: 1), (timer) async {
    if (service is AndroidServiceInstance) {
      try {
        // Get current position
        final position = await Geolocator.getCurrentPosition(
          desiredAccuracy: LocationAccuracy.bestForNavigation,
        );
        // Calculate reason for movement (for analytics/logging, not filtering)
        final reason = calculateReason(position);
        // Configuration checks
        final prefs = await SharedPreferences.getInstance();
        final gpsTimer = prefs.getInt('flutter.gpsTimer') ?? 5;
        final distanceThreshold = prefs.getDouble('flutter.distanceThreshold') ?? 1000.0;
        final now = DateTime.now();
        final timeSinceLastUpdate = lastUpdateTime == null ? null : now.difference(lastUpdateTime!).inSeconds;
        final distance = lastPosition == null ? null : Geolocator.distanceBetween(
          lastPosition!.latitude, lastPosition!.longitude,
          position.latitude, position.longitude,
        );
        final speed = position.speed * 3.6; // m/s to km/h
        final clampedSpeed = speed < 0 ? 0 : speed;
        if (
          (timeSinceLastUpdate != null && timeSinceLastUpdate >= gpsTimer) ||
          (distance != null && distance >= distanceThreshold) ||
          clampedSpeed >= overSpeedingThreshold ||
          lastPosition == null || lastUpdateTime == null
        ) {
          await _queueLocationData(position, reason);
          lastPosition = position;
          lastUpdateTime = now;
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
    
    // Convert speed from m/s to km/h and ensure it's not negative or near zero
    var speed = position.speed * 3.6;
    if (speed < 0.5) speed = 0;
    
    final locationData = {
      'latitude': position.latitude,
      'longitude': position.longitude,
      'accuracy': position.accuracy,
      'altitude': position.altitude,
      'speed': speed, // Now storing speed in km/h and ensuring non-negative
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

// Configuration parameters with defaults
int gpsTimer = 5; // Default 5 seconds
int uploadTimer = 10; // Default 10 seconds
double angleThreshold = 45.0; // Default 45 degrees
double overSpeedingThreshold = 60.0; // Default 60 km/h
double distanceThreshold = 1000.0; // Default 1000 meters
int movingTimer = 60; // Default 60 seconds
int stopTimer = 130; // Default 130 seconds

// Load configuration from SharedPreferences
Future<void> loadConfiguration() async {
  try {
    final prefs = await SharedPreferences.getInstance();
    
    // Load configuration values with defaults
    gpsTimer = prefs.getInt('flutter.gpsTimer') ?? 5;
    uploadTimer = prefs.getInt('flutter.uploadTimer') ?? 10; // Now in seconds
    angleThreshold = prefs.getDouble('flutter.angleThreshold') ?? 45.0;
    overSpeedingThreshold = prefs.getDouble('flutter.overSpeedingThreshold') ?? 60.0;
    distanceThreshold = prefs.getDouble('flutter.distanceThreshold') ?? 1000.0;
    movingTimer = prefs.getInt('flutter.movingTimer') ?? 60;
    stopTimer = prefs.getInt('flutter.stopTimer') ?? 130;

    print('Configuration loaded successfully');
  } catch (e) {
    print('Error loading configuration: $e');
  }
} 