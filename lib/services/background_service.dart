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

  Position? lastPosition;

  // Switched back to Timer.periodic for reliable, time-based updates.
  Timer.periodic(Duration(seconds: gpsTimer), (timer) async {
    try {
      // 1. Check igStatus from native to ensure ignition is ON
      int currentIgStatus = 0;
      try {
        const serviceChannel = MethodChannel('com.example.twtracking/service');
        currentIgStatus = await serviceChannel.invokeMethod('getCurrentIgStatus');
      } catch (e) {
        print('⚠️ Could not get igStatus from native, assuming OFF. Error: $e');
        return; // Don't proceed if we can't get a reliable status
      }
      
      // 2. If ignition is off, skip this cycle.
      if (currentIgStatus == 0) {
        print('ℹ️ Ignition is off (igStatus: 0). Skipping timer-based location save.');
        return;
      }

      // 3. Get current position
      final position = await Geolocator.getCurrentPosition(
        desiredAccuracy: LocationAccuracy.bestForNavigation,
      );

      // 4. Determine reason
      String reason = "Timer";
      if (lastPosition == null) {
        reason = "Initial Position";
      } else {
        // Check for distance-based reason first
        final distance = Geolocator.distanceBetween(
          lastPosition!.latitude, 
          lastPosition!.longitude, 
          position.latitude, 
          position.longitude
        );
        
        if (distance >= distanceThreshold) {
          reason = "Distance";
        } else {
          // Check for turn detection if vehicle is moving
          if (position.speed * 3.6 >= 5) { // speed >= 5 km/h
            final bearingChange = (position.heading ?? 0) - (lastPosition!.heading ?? 0);
            final normalizedBearingChange = bearingChange.abs() > 180 ? 360 - bearingChange.abs() : bearingChange.abs();
            
            if (normalizedBearingChange >= angleThreshold) {
              reason = "Turn";
            } else if (position.speed > 1) { // speed is m/s. > 1 m/s is ~3.6 km/h
              reason = "Movement";
            }
          } else if (position.speed > 1) { // speed is m/s. > 1 m/s is ~3.6 km/h
            reason = "Movement";
          }
        }
      }

      // 5. Queue data for sync
      final prefs = await SharedPreferences.getInstance();
      final imei = prefs.getString('flutter.imei') ?? 'unknown';
      final now = DateTime.now();

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
        'igStatus': currentIgStatus,
        'localPrimaryId': now.millisecondsSinceEpoch % 100000,
        'name': 'A100',
        'phoneNo': 'unknown',
        'provider': 'fused',
        'reason': reason,
        'versionNo': 'v1.0.0', // Placeholder
        'sync_status': 0,
        'createAt':  DateFormat("dd/MM/yyyy HH:mm:ss.SSS").format(now),
      };

      await SyncService().queueLocationData(data);
      print('✅ Location data saved via timer. Reason: $reason, igStatus: $currentIgStatus');
      
      lastPosition = position;

    } catch (e) {
      print('❌ Error in periodic location timer: $e');
    }
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
      
      // Load configuration values with defaults and handle type conversion
      gpsTimer = _getIntValue(prefs, 'flutter.gpsTimer', 5);
      uploadTimer = _getIntValue(prefs, 'flutter.uploadTimer', 10);
      angleThreshold = _getDoubleValue(prefs, 'flutter.angleThreshold', 45.0);
      overSpeedingThreshold = _getDoubleValue(prefs, 'flutter.overSpeedingThreshold', 60.0);
      distanceThreshold = _getDoubleValue(prefs, 'flutter.distanceThreshold', 1000.0);
      movingTimer = _getIntValue(prefs, 'flutter.movingTimer', 60);
      stopTimer = _getIntValue(prefs, 'flutter.stopTimer', 130);

      print('Enhanced configuration loaded:');
      print('GPS Timer: ${gpsTimer}s, Speed Threshold: ${overSpeedingThreshold} km/h');
      print('Distance Threshold: ${distanceThreshold}m, Angle Threshold: ${angleThreshold}°');
    } catch (e) {
      print('Error loading configuration: $e');
    }
  }
  
  int _getIntValue(SharedPreferences prefs, String key, int defaultValue) {
    try {
      return prefs.getInt(key) ?? defaultValue;
    } catch (e) {
      // If getInt fails, try to parse as string
      final stringValue = prefs.getString(key);
      if (stringValue != null) {
        return int.tryParse(stringValue) ?? defaultValue;
      }
      return defaultValue;
    }
  }
  
  double _getDoubleValue(SharedPreferences prefs, String key, double defaultValue) {
    try {
      return prefs.getDouble(key) ?? defaultValue;
    } catch (e) {
      // If getDouble fails, try to parse as string
      final stringValue = prefs.getString(key);
      if (stringValue != null) {
        return double.tryParse(stringValue) ?? defaultValue;
      }
      return defaultValue;
    }
  }
