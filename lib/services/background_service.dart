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
import 'package:tracking_world/services/database_helper.dart';
import 'dart:math';
import 'sync_service.dart';
import 'package:package_info_plus/package_info_plus.dart';
import 'package:flutter/services.dart';

@pragma('vm:entry-point')
Future<void> initializeService() async {
  final service = FlutterBackgroundService();
  final FlutterLocalNotificationsPlugin flutterLocalNotificationsPlugin = FlutterLocalNotificationsPlugin();
  final syncService = SyncService();

  // Initialize method channels for background isolate
  const MethodChannel _serviceChannel = MethodChannel('com.example.twtracking/service');
  const MethodChannel _avnSleepChannel = MethodChannel('com.example.twtracking/avn_sleep');

  // Method channel handlers for background service
  _serviceChannel.setMethodCallHandler((call) async {
    switch (call.method) {
      case 'getCurrentIgStatus':
        try {
          print('🔄 Getting current igStatus from background service channel');
          // Get igStatus directly from native BackgroundService (no SharedPreferences)
          final igStatus = await _serviceChannel.invokeMethod('getCurrentIgStatus');
          print('✅ Current igStatus from background: $igStatus');
          return igStatus;
        } catch (e) {
          print('❌ Error getting igStatus from background: $e');
          return 0;
        }
      case 'triggerPowerStateCheck':
        try {
          print('🔄 Triggering power state check from background service channel');
          final result = await _serviceChannel.invokeMethod('triggerPowerStateCheck');
          print('✅ Power state check result: $result');
          return result;
        } catch (e) {
          print('❌ Error triggering power state check: $e');
          return {'success': false, 'error': e.toString()};
        }
      case 'forceLogs':
        try {
          print('🔄 Forcing logs from background service channel');
          final result = await _serviceChannel.invokeMethod('forceLogs');
          print('✅ Force logs result: $result');
          return result;
        } catch (e) {
          print('❌ Error forcing logs: $e');
          return {'success': false, 'error': e.toString()};
        }
      case 'testBwicIgnition':
        try {
          print('🔄 Testing BWIC ignition from background service channel');
          final result = await _serviceChannel.invokeMethod('testBwicIgnition');
          print('✅ BWIC ignition test result: $result');
          return result;
        } catch (e) {
          print('❌ Error testing BWIC ignition: $e');
          return {'success': false, 'error': e.toString()};
        }
      case 'testServerSync':
        try {
          print('🔄 Testing server sync from background service channel');
          final result = await _serviceChannel.invokeMethod('testServerSync');
          print('✅ Server sync test result: $result');
          return result;
        } catch (e) {
          print('❌ Error testing server sync: $e');
          return {'success': false, 'error': e.toString()};
        }
      case 'sendIgStatusDirectly':
        try {
          print('🔄 Sending igStatus directly from background service channel');
          final result = await _serviceChannel.invokeMethod('sendIgStatusDirectly');
          print('✅ Direct igStatus send result: $result');
          return result;
        } catch (e) {
          print('❌ Error sending igStatus directly: $e');
          return {'success': false, 'error': e.toString()};
        }
      default:
        return {'success': false, 'error': 'Unknown method: ${call.method}'};
    }
  });

  _avnSleepChannel.setMethodCallHandler((call) async {
    switch (call.method) {
      case 'getCurrentIgStatus':
        try {
          print('🔄 Getting current igStatus from background AVN sleep channel');
          // Get igStatus directly from native BackgroundService (no SharedPreferences)
          final igStatus = await _serviceChannel.invokeMethod('getCurrentIgStatus');
          print('✅ Current igStatus from background AVN: $igStatus');
          return igStatus;
        } catch (e) {
          print('❌ Error getting igStatus from background AVN: $e');
          return 0;
        }
      case 'checkPowerState':
        try {
          print('🔄 Checking power state from background AVN sleep channel');
          // Get igStatus directly from native BackgroundService (no SharedPreferences)
          final currentIgStatus = await _serviceChannel.invokeMethod('getCurrentIgStatus');
          print('✅ Background AVN power state check - current igStatus: $currentIgStatus');
          
          // Don't change the igStatus, just return the current state
          return {
            'success': true,
            'currentIgStatus': currentIgStatus,
            'source': 'BackgroundService (direct)',
          };
        } catch (e) {
          print('❌ Error checking power state from background AVN: $e');
          return {'success': false, 'error': e.toString()};
        }
      default:
        return {'success': false, 'error': 'Unknown method: ${call.method}'};
    }
  });

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
  print('=== FLUTTER BACKGROUND SERVICE STARTED ===');
  
  DartPluginRegistrant.ensureInitialized();
  await loadConfiguration();

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

  // Enhanced position tracking variables
  Position? lastPosition;
  DateTime? lastUpdateTime;
  bool _isMoving = false;
  int _lastMovementTime = 0;
  int _lastStopTime = 0;
  
  // Enhanced stationary detection
  List<Position> recentPositions = [];
  final int positionBufferSize = 5;
  int consecutiveStationaryCount = 0;

  StreamSubscription<Position>? positionStream;

  // Listen to position updates
  try {
    positionStream = Geolocator.getPositionStream(locationSettings: locationSettings).listen((Position position) async {
      try {
        final prefs = await SharedPreferences.getInstance();
        final now = DateTime.now();
        final imei = prefs.getString('flutter.imei') ?? 'unknown';

        if (imei == 'unknown') {
            print('⚠️ IMEI is unknown, cannot process location.');
            return;
        }

        // CRITICAL FIX: Ensure igStatus is confirmed from native side before saving any data.
        int currentIgStatus = 0;
        try {
            const serviceChannel = MethodChannel('com.example.twtracking/service');
            currentIgStatus = await serviceChannel.invokeMethod('getCurrentIgStatus');
        } catch (e) {
            print('⚠️ Could not get igStatus from native, will retry: $e');
        }

        // If ignition is off, we don't need to check for movement or save location.
        // This also prevents saving "Initial Position" with igStatus 0.
        if (currentIgStatus == 0) {
            print('ℹ️ Ignition is off (igStatus: 0). Skipping location save.');
            lastPosition = position; // Still update last position for distance calculations
            return;
        }

        String reason = 'Idle'; // Default reason
        bool shouldSave = false;

        if (lastPosition == null) {
          shouldSave = true;
          reason = 'Initial Position';
          print('📍 Initial Position. Saving with confirmed igStatus: $currentIgStatus');
        } else {
          // ... existing movement detection logic ...
          final distance = Geolocator.distanceBetween(
              lastPosition!.latitude, lastPosition!.longitude,
              position.latitude, position.longitude);

          if (distance > (prefs.getDouble('distanceThreshold') ?? 200.0)) {
            shouldSave = true;
            reason = 'Distance';
          }
        }

        if (shouldSave) {
          final data = {
            'latitude': position.latitude,
            'longitude': position.longitude,
            'accuracy': position.accuracy,
            'altitude': position.altitude,
            'speed': position.speed,
            'bearing': position.heading,
            'imei': imei,
            'timestamp': position.timestamp?.toIso8601String() ?? now.toIso8601String(),
            'deviceRDT': DateFormat("dd/MM/yyyy HH:mm:ss.SSS").format(now),
            'gmtSettings': "GMT+${now.timeZoneOffset.inHours}:00",
            'igStatus': currentIgStatus, // Use the confirmed igStatus
            'localPrimaryId': now.millisecondsSinceEpoch % 100000,
            'name': 'A100',
            'phoneNo': 'unknown',
            'provider': 'fused',
            'reason': reason,
            'versionNo': 'v1.0.0', // Replace with dynamic version
            'sync_status': 0,
            'created_at': now.millisecondsSinceEpoch,
          };

          await SyncService().queueLocationData(data);
          print('✅ Location data saved with reason: $reason, igStatus: $currentIgStatus');
        }

        lastPosition = position;
        lastUpdateTime = now;
      } catch (e) {
        print('❌ Error in position stream listener: $e');
      }
    });
  } catch (e) {
    print('❌ Error setting up position stream: $e');
  }

  // Final check to ensure service keeps running
  Timer.periodic(const Duration(minutes: 2), (timer) {
      service.invoke('update', {});
      print('Service invoked to keep alive.');
  });
}

Future<void> _queueLocationData(Position position, String reason, double accurateSpeed) async {
  try {
    print('=== QUEUING LOCATION DATA ===');
    
    final prefs = await SharedPreferences.getInstance();
    final imei = prefs.getString('imei') ?? 'unknown';
    
    // Use the accurate speed we calculated
    final speedToSave = accurateSpeed < 0 ? 0.0 : accurateSpeed;
    
    final locationData = {
      'latitude': position.latitude,
      'longitude': position.longitude,
      'accuracy': position.accuracy,
      'altitude': position.altitude,
      'speed': speedToSave, // Use enhanced accurate speed
      'bearing': position.heading ?? 0.0, // Handle null heading
      'imei': imei,
      'timestamp': DateTime.now().toIso8601String(),
      'deviceRDT': DateFormat("dd/MM/yyyy HH:mm:ss.SSS").format(DateTime.now()),
      'gmtSettings': "GMT+${DateTime.now().timeZoneOffset.inHours}:00 ${DateTime.now().year}",
      'igStatus': 0, // Default to ACC OFF, will be updated by sync service
      'localPrimaryId': DateTime.now().millisecondsSinceEpoch % 100000,
      'name': (await DeviceInfoPlugin().androidInfo).model,
      'phoneNo': (await DeviceInfoPlugin().androidInfo).serialNumber,
      'provider': 'fused',
      'reason': reason,
      'versionNo': 'v${(await PackageInfo.fromPlatform()).version}',
    };

    print('Enhanced location data:');
    print('Lat: ${locationData['latitude']}, Lng: ${locationData['longitude']}');
    print('Speed: ${locationData['speed']}, Reason: ${locationData['reason']}');
    print('Accuracy: ${position.accuracy.toStringAsFixed(1)}m');

    // Queue the data for sync
    await SyncService().queueLocationData(locationData);
    print('Location data queued successfully with enhanced speed: ${speedToSave.toStringAsFixed(1)} km/h');
    
  } catch (e) {
    print('Error queueing location data: $e');
    print('Stack trace: ${StackTrace.current}');
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

    print('Enhanced configuration loaded:');
    print('GPS Timer: ${gpsTimer}s, Speed Threshold: ${overSpeedingThreshold} km/h');
    print('Distance Threshold: ${distanceThreshold}m, Angle Threshold: ${angleThreshold}°');
  } catch (e) {
    print('Error loading configuration: $e');
  }
}
