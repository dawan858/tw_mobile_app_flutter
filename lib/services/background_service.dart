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
double overSpeedingThreshold = 70.0; // Default 70 km/h (updated)
double distanceThreshold = 500.0; // Default 500 meters (updated)
int movingTimer = 60; // Default 60 seconds
int stopTimer = 130; // Default 130 seconds

// Add configuration reload timer
Timer? _configReloadTimer;
int _testCounter = 0; // Counter for testing data collection
int _configReloadCounter = 0; // Counter for configuration reload

@pragma('vm:entry-point')
void onStart(ServiceInstance service) async {
  print('=== FLUTTER BACKGROUND SERVICE STARTED (UI COORDINATOR ONLY) ===');
  
  DartPluginRegistrant.ensureInitialized();
  await loadConfiguration();

  // Start configuration reload timer to keep config updated
  _configReloadTimer = Timer.periodic(const Duration(minutes: 5), (timer) async {
    await loadConfiguration();
    print('🔄 Configuration reloaded from SharedPreferences');
  });

  if (service is AndroidServiceInstance) {
    service.on('setAsForeground').listen((event) {
      service.setAsForegroundService();
    });

    service.on('setAsBackground').listen((event) {
      service.setAsBackgroundService();
    });
  }

  service.on('stopService').listen((event) {
    _configReloadTimer?.cancel();
    service.stopSelf();
  });

  // REMOVED: Location data collection - Native BackgroundService handles this
  // REMOVED: Timer.periodic location tracking - No longer needed
  
  // Only handle UI-related tasks and service coordination
  Timer.periodic(const Duration(seconds: 30), (timer) async {
    try {
      // Reload configuration before each cycle to ensure we have latest values
      await loadConfiguration();
      
      // Check if native BackgroundService is running
      try {
        const serviceChannel = MethodChannel('com.example.twtracking/service');
        final isNativeServiceRunning = await serviceChannel.invokeMethod('isBackgroundServiceRunning');
        
        print('🔍 Native BackgroundService status check: $isNativeServiceRunning');
        
        if (!isNativeServiceRunning) {
          print('⚠️ Native BackgroundService not running, attempting to start it');
          await serviceChannel.invokeMethod('startBackgroundService');
          
          // Wait a moment and check again
          await Future.delayed(const Duration(seconds: 2));
          final isRunningAfterStart = await serviceChannel.invokeMethod('isBackgroundServiceRunning');
          print('🔍 Native BackgroundService status after start attempt: $isRunningAfterStart');
        } else {
          print('✅ Native BackgroundService is running');
          
          // Get native service status for debugging
          try {
            final nativeStatus = await serviceChannel.invokeMethod('getNativeServiceStatus');
            print('📊 Native service status: $nativeStatus');
          } catch (e) {
            print('❌ Error getting native service status: $e');
          }
        }
      } catch (e) {
        print('❌ Error checking native service status: $e');
      }
      
      // Check database for data collection
      try {
        final dbHelper = DatabaseHelper();
        final stats = await dbHelper.getDatabaseStats();
        print('📊 Database stats: ${stats['totalRecords']} total, ${stats['unsyncedRecords']} unsynced, ${stats['syncedRecords']} synced');
        
        if (stats['totalRecords'] == 0) {
          print('⚠️ No data in database - native service might not be collecting data');
        } else if (stats['unsyncedRecords'] > 0) {
          print('⚠️ ${stats['unsyncedRecords']} unsynced records - sync service might not be working');
        }
      } catch (e) {
        print('❌ Error checking database stats: $e');
      }
      
      // Update UI with current status (no data collection)
      print('✅ Flutter service coordinator running - Native service handles data collection');
      
      // Test data collection every 5 minutes
      _testCounter++;
      if (_testCounter >= 10) { // Every 5 minutes (30s * 10 = 5 minutes)
        _testCounter = 0;
        try {
          print('🧪 Testing data collection...');
          const serviceChannel = MethodChannel('com.example.twtracking/service');
          await serviceChannel.invokeMethod('testDataCollection');
          print('✅ Data collection test triggered');
        } catch (e) {
          print('❌ Error testing data collection: $e');
        }
      }
      
      // Force configuration reload every 10 minutes
      _configReloadCounter++;
      if (_configReloadCounter >= 20) { // Every 10 minutes (30s * 20 = 10 minutes)
        _configReloadCounter = 0;
        try {
          print('🔄 Forcing configuration reload...');
          const serviceChannel = MethodChannel('com.example.twtracking/service');
          await serviceChannel.invokeMethod('forceReloadConfiguration');
          print('✅ Configuration reload triggered');
        } catch (e) {
          print('❌ Error forcing configuration reload: $e');
        }
      }
      
    } catch (e) {
      print('❌ Error in Flutter service coordinator: $e');
    }
  });
}

// REMOVED: _queueLocationData method - No longer needed
// REMOVED: _queueLocationDataWithReason method - No longer needed

// REMOVED: testDistanceCalculation method - No longer needed

// Load configuration from SharedPreferences (kept for service coordination)
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

    print('Configuration loaded for service coordination:');
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
