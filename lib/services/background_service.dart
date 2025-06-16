// import 'dart:async';
// import 'dart:ui';
// import 'package:flutter_background_service/flutter_background_service.dart';
// import 'package:flutter_background_service_android/flutter_background_service_android.dart';
// import 'package:geolocator/geolocator.dart';
// import 'package:shared_preferences/shared_preferences.dart';
// import 'package:http/http.dart' as http;
// import 'dart:convert';
// import 'package:flutter_local_notifications/flutter_local_notifications.dart';
// import 'package:device_info_plus/device_info_plus.dart';
// import 'package:intl/intl.dart';
// import 'sync_service.dart';

// @pragma('vm:entry-point')
// Future<void> initializeService() async {
//   final service = FlutterBackgroundService();
//   final FlutterLocalNotificationsPlugin flutterLocalNotificationsPlugin = FlutterLocalNotificationsPlugin();
//   final syncService = SyncService();

//   // Initialize notification channel
//   const AndroidNotificationChannel channel = AndroidNotificationChannel(
//     'tracking_service',
//     'GPS Tracking Service',
//     description: 'This channel is used for GPS tracking notifications',
//     importance: Importance.high,
//     enableVibration: false,
//     playSound: false,
//   );

//   await flutterLocalNotificationsPlugin
//       .resolvePlatformSpecificImplementation<AndroidFlutterLocalNotificationsPlugin>()
//       ?.createNotificationChannel(channel);

//   await service.configure(
//     androidConfiguration: AndroidConfiguration(
//       onStart: onStart,
//       autoStart: true,
//       autoStartOnBoot: true,
//       isForegroundMode: true,
//       notificationChannelId: 'tracking_service',
//       initialNotificationTitle: 'GPS Tracking',
//       initialNotificationContent: 'Initializing...',
//       foregroundServiceNotificationId: 888,
//     ),
//     iosConfiguration: IosConfiguration(
//       autoStart: false,
//       onForeground: onStart,
//       onBackground: onIosBackground,
//     ),
//   );

//   // Start the service immediately
//   await service.startService();
//   await syncService.startPeriodicSync();
// }

// @pragma('vm:entry-point')
// Future<bool> onIosBackground(ServiceInstance service) async {
//   return true;
// }

// @pragma('vm:entry-point')
// void onStart(ServiceInstance service) async {
//   DartPluginRegistrant.ensureInitialized();
//   await loadConfiguration(); // Load configuration before setting up location updates

//   if (service is AndroidServiceInstance) {
//     service.on('setAsForeground').listen((event) {
//       service.setAsForegroundService();
//     });

//     service.on('setAsBackground').listen((event) {
//       service.setAsBackgroundService();
//     });
//   }

//   service.on('stopService').listen((event) {
//     service.stopSelf();
//   });

//   // Initialize location settings
//   const LocationSettings locationSettings = LocationSettings(
//     accuracy: LocationAccuracy.bestForNavigation,
//   );

//   // Get last known position
//   Position? lastPosition;
//   DateTime? lastUpdateTime;
//   const minUpdateInterval = Duration(seconds: 3);
//   const minDistance = 5.0;
//   const minBearingChange = 10.0; // Minimum bearing change to consider it a turn
//   const minSpeedForMovement = 1.0; // Minimum speed in m/s to consider it movement

//   bool _isMoving = false;
//   int _lastMovementTime = 0;
//   int _lastStopTime = 0;
//   int stopTimer = 130; // Default 130 seconds
//   double overSpeedingThreshold = 60.0; // Default 60 km/h
//   double angleThreshold = 45.0; // Default 45 degrees

//   String calculateReason(Position currentLocation) {
//     if (lastPosition == null) {
//       return 'Initial Position';
//     }

//     var speed = currentLocation.speed * 3.6;
//     if (speed < 1.0) speed = 0; // Clamp to 0 if less than 1.0 km/h

//     final bearingChange = (currentLocation.heading - lastPosition!.heading).abs();
//     final normalizedBearingChange = bearingChange > 180 ? 360 - bearingChange : bearingChange;
//     final distance = Geolocator.distanceBetween(
//       lastPosition!.latitude,
//       lastPosition!.longitude,
//       currentLocation.latitude,
//       currentLocation.longitude,
//     );

//     // Update movement status
//     if (speed >= 1.0 || distance > 5) {
//       if (!_isMoving) {
//         _isMoving = true;
//         _lastMovementTime = DateTime.now().millisecondsSinceEpoch;
//       }
//       _lastStopTime = DateTime.now().millisecondsSinceEpoch;
//     } else {
//       if (_isMoving && (DateTime.now().millisecondsSinceEpoch - _lastStopTime) > stopTimer * 1000) {
//         _isMoving = false;
//       }
//     }

//     if (speed == 0) {
//       return 'Idle';
//     }
//     if (speed > overSpeedingThreshold) {
//       return 'Over Speeding';
//     }
//     if (speed >= 5 && normalizedBearingChange > angleThreshold) {
//       return 'Turn';
//     }
//     if (_isMoving) {
//       return 'Move';
//     }
//     return 'Idle';
//   }

//   // Start periodic task for instant updates (every second)
//   Timer.periodic(const Duration(seconds: 1), (timer) async {
//     if (service is AndroidServiceInstance) {
//       try {
//         // Get current position
//         final position = await Geolocator.getCurrentPosition(
//           desiredAccuracy: LocationAccuracy.bestForNavigation,
//         );
//         // Calculate reason for movement (for analytics/logging, not filtering)
//         final reason = calculateReason(position);
//         // Configuration checks
//         final prefs = await SharedPreferences.getInstance();
//         final gpsTimer = prefs.getInt('flutter.gpsTimer') ?? 5;
//         final distanceThreshold = prefs.getDouble('flutter.distanceThreshold') ?? 1000.0;
//         final now = DateTime.now();
//         final timeSinceLastUpdate = lastUpdateTime == null ? null : now.difference(lastUpdateTime!).inSeconds;
//         final distance = lastPosition == null ? null : Geolocator.distanceBetween(
//           lastPosition!.latitude, lastPosition!.longitude,
//           position.latitude, position.longitude,
//         );
//         final speed = position.speed * 3.6; // m/s to km/h
//         final clampedSpeed = speed < 0 ? 0 : speed;
//         if (
//           (timeSinceLastUpdate != null && timeSinceLastUpdate >= gpsTimer) ||
//           (distance != null && distance >= distanceThreshold) ||
//           clampedSpeed >= overSpeedingThreshold ||
//           lastPosition == null || lastUpdateTime == null
//         ) {
//           await _queueLocationData(position, reason);
//           lastPosition = position;
//           lastUpdateTime = now;
//         }
//         // Update notification
//         service.setForegroundNotificationInfo(
//           title: "GPS Tracking Active",
//           content: "Last update: ${lastUpdateTime?.toString() ?? 'Never'}",
//         );
//       } catch (e) {
//         print('Error in background service: $e');
//         // Try to restart the service if it fails
//         if (service is AndroidServiceInstance) {
//           service.setForegroundNotificationInfo(
//             title: "GPS Tracking Error",
//             content: "Attempting to restart...",
//           );
//           // Wait a bit before restarting
//           await Future.delayed(const Duration(seconds: 5));
//           service.setAsForegroundService();
//         }
//       }
//     }
//   });
// }

// Future<void> _queueLocationData(Position position, String reason) async {
//   try {
//     final prefs = await SharedPreferences.getInstance();
//     final imei = prefs.getString('imei') ?? 'unknown';
    
//     // Convert speed from m/s to km/h and ensure it's not negative or near zero
//     var speed = position.speed * 3.6;
//     if (speed < 1.0) speed = 0; // Clamp to 0 if less than 1.0 km/h
    
//     final locationData = {
//       'latitude': position.latitude,
//       'longitude': position.longitude,
//       'accuracy': position.accuracy,
//       'altitude': position.altitude,
//       'speed': speed, // Use the clamped value
//       'bearing': position.heading,
//       'imei': imei,
//       'timestamp': DateTime.now().toIso8601String(),
//       'deviceRDT': DateFormat("dd/MM/yyyy HH:mm:ss.SSS").format(DateTime.now()),
//       'gmtSettings': "GMT+${DateTime.now().timeZoneOffset.inHours}:00 ${DateTime.now().year}",
//       'igStatus': 1,
//       'localPrimaryId': DateTime.now().millisecondsSinceEpoch % 100000,
//       'name': (await DeviceInfoPlugin().androidInfo).model,
//       'phoneNo': (await DeviceInfoPlugin().androidInfo).serialNumber,
//       'provider': 'fused',
//       'reason': reason,
//       'versionNo': 'v ${(await DeviceInfoPlugin().androidInfo).version.release}',
//     };

//     // Queue the data for sync
//     await SyncService().queueLocationData(locationData);
//   } catch (e) {
//     print('Error queueing location data: $e');
//   }
// }

// // Configuration parameters with defaults
// int gpsTimer = 5; // Default 5 seconds
// int uploadTimer = 10; // Default 10 seconds
// double angleThreshold = 45.0; // Default 45 degrees
// double overSpeedingThreshold = 60.0; // Default 60 km/h
// double distanceThreshold = 1000.0; // Default 1000 meters
// int movingTimer = 60; // Default 60 seconds
// int stopTimer = 130; // Default 130 seconds

// // Load configuration from SharedPreferences
// Future<void> loadConfiguration() async {
//   try {
//     final prefs = await SharedPreferences.getInstance();
    
//     // Load configuration values with defaults
//     gpsTimer = prefs.getInt('flutter.gpsTimer') ?? 5;
//     uploadTimer = prefs.getInt('flutter.uploadTimer') ?? 10; // Now in seconds
//     angleThreshold = prefs.getDouble('flutter.angleThreshold') ?? 45.0;
//     overSpeedingThreshold = prefs.getDouble('flutter.overSpeedingThreshold') ?? 60.0;
//     distanceThreshold = prefs.getDouble('flutter.distanceThreshold') ?? 1000.0;
//     movingTimer = prefs.getInt('flutter.movingTimer') ?? 60;
//     stopTimer = prefs.getInt('flutter.stopTimer') ?? 130;

//     print('Configuration loaded successfully');
//   } catch (e) {
//     print('Error loading configuration: $e');
//   }
// } 

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
import 'dart:math';
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

// Configuration parameters with defaults
int gpsTimer = 5; // Default 5 seconds
int uploadTimer = 10; // Default 10 seconds
double angleThreshold = 45.0; // Default 45 degrees
double overSpeedingThreshold = 60.0; // Default 60 km/h
double distanceThreshold = 1000.0; // Default 1000 meters
int movingTimer = 60; // Default 60 seconds
int stopTimer = 130; // Default 130 seconds

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
  bool _isMoving = false;
  int _lastMovementTime = 0;
  int _lastStopTime = 0;

  // Calculate speed from distance and time (more accurate than GPS speed for slow movements)
  double calculateSpeedFromDistance(Position current, Position? previous) {
    if (previous == null) return 0.0;
    
    final distance = Geolocator.distanceBetween(
      previous.latitude, previous.longitude,
      current.latitude, current.longitude,
    ); // meters
    
    final timeDiff = current.timestamp!.difference(previous.timestamp!).inMilliseconds / 1000.0; // seconds
    
    if (timeDiff <= 0) return 0.0;
    
    return (distance / timeDiff) * 3.6; // Convert m/s to km/h
  }

  // Get accurate speed using both GPS and calculated methods
  double getAccurateSpeed(Position position, Position? previousPosition) {
    // Method 1: GPS speed (may be unreliable at low speeds)
    double gpsSpeed = position.speed * 3.6; // Convert m/s to km/h
    if (gpsSpeed < 0) gpsSpeed = 0;
    
    // Method 2: Calculate speed from distance/time
    double calculatedSpeed = calculateSpeedFromDistance(position, previousPosition);
    
    // Filter out GPS noise - if both speeds are very low, consider it stationary
    if (gpsSpeed < 3.0 && calculatedSpeed < 3.0) {
      return 0.0; // Device is likely stationary, GPS noise
    }
    
    // Use GPS speed if it's reasonable (> 3 km/h), otherwise use calculated speed
    if (gpsSpeed >= 3.0 && gpsSpeed < 200.0) {
      return gpsSpeed;
    } else if (calculatedSpeed >= 3.0) {
      return calculatedSpeed;
    } else {
      return 0.0;
    }
  }

  // Calculate reason for movement (matching Kotlin logic)
  String calculateReason(Position currentLocation, Position? lastPosition, double accurateSpeed) {
    if (lastPosition == null) {
      return 'Initial Position';
    }

    // Calculate bearing change (handle null heading values)
    double bearingChange = 0.0;
    if (currentLocation.heading != null && lastPosition.heading != null) {
      bearingChange = (currentLocation.heading! - lastPosition.heading!).abs();
      if (bearingChange > 180) bearingChange = 360 - bearingChange;
    }

    // Calculate distance moved
    final distance = Geolocator.distanceBetween(
      lastPosition.latitude,
      lastPosition.longitude,
      currentLocation.latitude,
      currentLocation.longitude,
    );

    // Enhanced movement detection - require significant movement to be considered "moving"
    final isSignificantMovement = accurateSpeed >= 3.0 || // Speed threshold increased
                                 (distance > 10 && accurateSpeed > 1.0); // Distance + minimal speed

    // Update movement status with stricter criteria
    if (isSignificantMovement) {
      if (!_isMoving) {
        _isMoving = true;
        _lastMovementTime = DateTime.now().millisecondsSinceEpoch;
      }
      _lastStopTime = DateTime.now().millisecondsSinceEpoch;
    } else {
      // More aggressive stop detection - shorter timer for stationary detection
      if (_isMoving && (DateTime.now().millisecondsSinceEpoch - _lastStopTime) > 30000) { // 30 seconds instead of stopTimer
        _isMoving = false;
      }
    }

    // Determine reason based on movement patterns
    if (accurateSpeed > overSpeedingThreshold) {
      return 'Over Speeding';
    } else if (accurateSpeed < 2.0) { // If speed is very low, always consider idle
      return 'Idle';
    } else if (!_isMoving) {
      return 'Idle';
    } else if (accurateSpeed >= 5 && bearingChange > angleThreshold) {
      return 'Turn';
    } else if (_isMoving && accurateSpeed >= 3.0) { // Only "Move" if actually moving at reasonable speed
      return 'Move';
    } else {
      return 'Idle';
    }
  }

  // Should we process this location update?
  bool shouldProcessLocationUpdate(Position position, Position? lastPosition, 
      DateTime? lastUpdateTime, double accurateSpeed) {
    if (lastPosition == null || lastUpdateTime == null) return true;
    
    final timeSinceLastUpdate = DateTime.now().difference(lastUpdateTime).inSeconds;
    final distance = Geolocator.distanceBetween(
      lastPosition.latitude, lastPosition.longitude,
      position.latitude, position.longitude,
    );
    
    return timeSinceLastUpdate >= gpsTimer ||
           distance >= distanceThreshold ||
           accurateSpeed >= overSpeedingThreshold ||
           (accurateSpeed >= 3.0 && distance > 10); // Only process if actually moving with reasonable speed/distance
  }

  // Start periodic task based on GPS timer (not every second)
  Timer.periodic(Duration(seconds: gpsTimer), (timer) async {
    if (service is AndroidServiceInstance) {
      try {
        // Get current position
        final position = await Geolocator.getCurrentPosition(
          desiredAccuracy: LocationAccuracy.bestForNavigation,
        );

        // Skip if accuracy is too poor
        if (position.accuracy > 50) {
          print('Skipping inaccurate location: accuracy = ${position.accuracy}');
          return;
        }

        // Get accurate speed
        final accurateSpeed = getAccurateSpeed(position, lastPosition);
        
        // Calculate reason for movement
        final reason = calculateReason(position, lastPosition, accurateSpeed);
        
        // Log speed information for debugging
        final gpsSpeed = position.speed * 3.6;
        final calculatedSpeed = calculateSpeedFromDistance(position, lastPosition);
        print('Speed Info - GPS: ${gpsSpeed.toStringAsFixed(1)} km/h, '
              'Calculated: ${calculatedSpeed.toStringAsFixed(1)} km/h, '
              'Final: ${accurateSpeed.toStringAsFixed(1)} km/h, '
              'Reason: $reason');

        // Check if we should process this location update
        if (shouldProcessLocationUpdate(position, lastPosition, lastUpdateTime, accurateSpeed)) {
          print('Processing location update: ${position.latitude.toStringAsFixed(6)}, '
                '${position.longitude.toStringAsFixed(6)}, '
                'Speed: ${accurateSpeed.toStringAsFixed(1)} km/h');

          // Create position with corrected speed for saving
          await _queueLocationData(position, reason, accurateSpeed);
          lastPosition = position;
          lastUpdateTime = DateTime.now();

          // Update notification with speed info
          service.setForegroundNotificationInfo(
            title: "GPS Tracking Active",
            content: "Speed: ${accurateSpeed.toStringAsFixed(1)} km/h\n"
                    "Location: ${position.latitude.toStringAsFixed(6)}, ${position.longitude.toStringAsFixed(6)}\n"
                    "Reason: $reason",
          );
        } else {
          print('Skipping location update - no trigger met');
          
          // Still update notification to show we're active
          service.setForegroundNotificationInfo(
            title: "GPS Tracking Active",
            content: "Monitoring... Speed: ${accurateSpeed.toStringAsFixed(1)} km/h",
          );
        }

      } catch (e) {
        print('Error in background service: $e');
        if (service is AndroidServiceInstance) {
          service.setForegroundNotificationInfo(
            title: "GPS Tracking Error",
            content: "Error: $e",
          );
        }
      }
    }
  });
}

Future<void> _queueLocationData(Position position, String reason, double accurateSpeed) async {
  try {
    final prefs = await SharedPreferences.getInstance();
    final imei = prefs.getString('imei') ?? 'unknown';
    
    // Use the accurate speed we calculated
    final speedToSave = accurateSpeed < 0 ? 0.0 : accurateSpeed;
    
    final locationData = {
      'latitude': position.latitude,
      'longitude': position.longitude,
      'accuracy': position.accuracy,
      'altitude': position.altitude,
      'speed': speedToSave, // Use accurate speed
      'bearing': position.heading ?? 0.0, // Handle null heading
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
    print('Location data queued successfully with speed: ${speedToSave.toStringAsFixed(1)} km/h');
  } catch (e) {
    print('Error queueing location data: $e');
  }
}

// Load configuration from SharedPreferences
Future<void> loadConfiguration() async {
  try {
    final prefs = await SharedPreferences.getInstance();
    
    // Load configuration values with defaults
    gpsTimer = prefs.getInt('flutter.gpsTimer') ?? 5;
    uploadTimer = prefs.getInt('flutter.uploadTimer') ?? 10;
    angleThreshold = prefs.getDouble('flutter.angleThreshold') ?? 45.0;
    overSpeedingThreshold = prefs.getDouble('flutter.overSpeedingThreshold') ?? 60.0;
    distanceThreshold = prefs.getDouble('flutter.distanceThreshold') ?? 1000.0;
    movingTimer = prefs.getInt('flutter.movingTimer') ?? 60;
    stopTimer = prefs.getInt('flutter.stopTimer') ?? 130;

    print('Configuration loaded successfully - GPS Timer: ${gpsTimer}s, Speed Threshold: ${overSpeedingThreshold} km/h');
  } catch (e) {
    print('Error loading configuration: $e');
  }
}