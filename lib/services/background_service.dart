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
  _initializeMethodChannels();

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

  // Add position to buffer for analysis
  void addPositionToBuffer(Position position) {
    recentPositions.add(position);
    if (recentPositions.length > positionBufferSize) {
      recentPositions.removeAt(0);
    }
  }

  // Calculate speed from distance and time
  double calculateSpeedFromDistance(Position current, Position? previous) {
    if (previous == null || current.timestamp == null || previous.timestamp == null) return 0.0;
    
    final distance = Geolocator.distanceBetween(
      previous.latitude, previous.longitude,
      current.latitude, current.longitude,
    ); // meters
    
    final timeDiff = current.timestamp!.difference(previous.timestamp!).inMilliseconds / 1000.0; // seconds
    
    if (timeDiff <= 0) return 0.0;
    
    return (distance / timeDiff) * 3.6; // Convert m/s to km/h
  }

  // Calculate position stability over time
  double calculatePositionStability() {
    if (recentPositions.length < 3) return 0.0;
    
    // Calculate average position
    double avgLat = recentPositions.map((p) => p.latitude).reduce((a, b) => a + b) / recentPositions.length;
    double avgLng = recentPositions.map((p) => p.longitude).reduce((a, b) => a + b) / recentPositions.length;
    
    // Calculate variance
    double variance = 0.0;
    for (var pos in recentPositions) {
      double dist = Geolocator.distanceBetween(avgLat, avgLng, pos.latitude, pos.longitude);
      variance += dist * dist;
    }
    variance /= recentPositions.length;
    
    double stability = 1.0 / (1.0 + variance / 100.0); // Normalize to 0-1
    return stability;
  }

  // Check if device is stationary based on recent positions
  bool isDeviceStationary() {
    if (recentPositions.length < 3) return false;
    
    // Check if all recent positions are within 15 meters
    double maxDistance = 0.0;
    for (int i = 0; i < recentPositions.length - 1; i++) {
      for (int j = i + 1; j < recentPositions.length; j++) {
        double dist = Geolocator.distanceBetween(
          recentPositions[i].latitude, recentPositions[i].longitude,
          recentPositions[j].latitude, recentPositions[j].longitude,
        );
        if (dist > maxDistance) maxDistance = dist;
      }
    }
    
    return maxDistance < 15.0;
  }

  // Enhanced speed calculation with comprehensive stationary detection
  double getEnhancedAccurateSpeed(Position position, Position? previousPosition) {
    addPositionToBuffer(position);
    
    // Method 1: GPS speed (may be unreliable at low speeds)
    double gpsSpeed = position.speed * 3.6; // Convert m/s to km/h
    if (gpsSpeed < 0) gpsSpeed = 0;
    
    // Method 2: Calculate speed from distance/time
    double calculatedSpeed = calculateSpeedFromDistance(position, previousPosition);
    
    // Method 3: Analyze position stability over time
    double stabilityScore = calculatePositionStability();
    bool isStationary = isDeviceStationary();
    
    print('Enhanced Speed Analysis:');
    print('  GPS: ${gpsSpeed.toStringAsFixed(1)} km/h');
    print('  Calculated: ${calculatedSpeed.toStringAsFixed(1)} km/h');
    print('  Accuracy: ${position.accuracy.toStringAsFixed(1)}m');
    print('  Stability: ${(stabilityScore * 100).toStringAsFixed(1)}%');
    print('  Stationary: $isStationary');
    
    // Enhanced stationary detection
    if (isStationary) {
      consecutiveStationaryCount++;
      if (consecutiveStationaryCount >= 3) {
        print('Device confirmed stationary ($consecutiveStationaryCount consecutive readings)');
        return 0.0;
      }
    } else {
      consecutiveStationaryCount = 0;
    }
    
    // Filter unrealistic speeds (GPS errors)
    if (gpsSpeed > 100.0 || calculatedSpeed > 100.0) {
      print('Filtering unrealistic speed -> 0 km/h (likely GPS error)');
      return 0.0;
    }
    
    // Accuracy-based filtering
    if (position.accuracy > 25.0) {
      // Poor accuracy - be very conservative
      if (gpsSpeed < 8.0 && calculatedSpeed < 8.0 && stabilityScore < 0.3) {
        return 0.0;
      }
    } else {
      // Good accuracy - normal filtering
      if (gpsSpeed < 5.0 && calculatedSpeed < 5.0 && stabilityScore < 0.5) {
        return 0.0;
      }
    }
    
    // Return the most reliable speed
    double finalSpeed = 0.0;
    
    // Use average if both speeds indicate movement
    if (gpsSpeed >= 5.0 && calculatedSpeed >= 5.0) {
      finalSpeed = (gpsSpeed + calculatedSpeed) / 2.0; // Average of both
    } else if (gpsSpeed >= 8.0) {
      finalSpeed = gpsSpeed;
    } else if (calculatedSpeed >= 8.0) {
      finalSpeed = calculatedSpeed;
    }
    
    print('  Final: ${finalSpeed.toStringAsFixed(1)} km/h');
    return finalSpeed;
  }

  // Enhanced reason calculation with better stationary detection
  String calculateEnhancedReason(Position currentLocation, Position? lastPosition, double accurateSpeed) {
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

    // Enhanced movement detection with stricter criteria
    final isSignificantMovement = accurateSpeed >= 8.0 || // Higher speed threshold
                                 (distance > 20 && accurateSpeed > 3.0); // Larger distance + speed combo

    // Update movement status with stricter criteria
    if (isSignificantMovement) {
      if (!_isMoving) {
        _isMoving = true;
        _lastMovementTime = DateTime.now().millisecondsSinceEpoch;
        print('Movement detected: Speed ${accurateSpeed.toStringAsFixed(1)} km/h, Distance ${distance.toStringAsFixed(1)}m');
      }
      _lastStopTime = DateTime.now().millisecondsSinceEpoch;
    } else {
      // Faster transition to idle state (15 seconds instead of 30)
      if (_isMoving && (DateTime.now().millisecondsSinceEpoch - _lastStopTime) > 15000) {
        _isMoving = false;
        print('Movement stopped - transitioning to idle');
      }
    }

    // Determine reason with conservative thresholds
    if (accurateSpeed > overSpeedingThreshold) {
      return 'Over Speeding';
    } else if (accurateSpeed < 3.0) { // Very low speeds are always idle
      return 'Idle';
    } else if (!_isMoving) {
      return 'Idle';
    } else if (accurateSpeed >= 10 && bearingChange > angleThreshold) {
      return 'Turn';
    } else if (_isMoving && accurateSpeed >= 8.0) { // Only "Move" if actually moving at reasonable speed
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
           (accurateSpeed >= 8.0 && distance > 15); // Significant movement only
  }

  // Debug database state
  Future<void> debugDatabaseState() async {
    try {
      final dbHelper = DatabaseHelper();
      final stats = await dbHelper.getDatabaseStats();
      
      print('=== DATABASE DEBUG INFO ===');
      print('Total records: ${stats['totalRecords']}');
      print('Unsynced records: ${stats['unsyncedRecords']}');
      print('Synced records: ${stats['syncedRecords']}');
      print('Database size: ${stats['databaseSizeMB']} MB');
      
    } catch (e) {
      print('Error debugging database: $e');
    }
  }

  // Debug database at startup
  await debugDatabaseState();

  // Start periodic task based on GPS timer with enhanced processing
  Timer.periodic(Duration(seconds: gpsTimer), (timer) async {
    if (service is AndroidServiceInstance) {
      try {
        // Get current position
        final position = await Geolocator.getCurrentPosition(
          desiredAccuracy: LocationAccuracy.bestForNavigation,
        );

        // Enhanced accuracy filtering
        if (position.accuracy > 30) {
          print('Skipping very inaccurate location: accuracy = ${position.accuracy}m');
          return;
        }

        // Get enhanced speed with comprehensive filtering
        final accurateSpeed = getEnhancedAccurateSpeed(position, lastPosition);
        
        // Calculate reason with enhanced detection
        final reason = calculateEnhancedReason(position, lastPosition, accurateSpeed);

        // Check if we should process this location update
        if (shouldProcessLocationUpdate(position, lastPosition, lastUpdateTime, accurateSpeed)) {
          print('=== PROCESSING LOCATION UPDATE ===');
          print('Location: ${position.latitude.toStringAsFixed(6)}, '
                '${position.longitude.toStringAsFixed(6)}');
          print('Speed: ${accurateSpeed.toStringAsFixed(1)} km/h, Reason: $reason');

          // Queue location data with enhanced speed
          await _queueLocationData(position, reason, accurateSpeed);
          lastPosition = position;
          lastUpdateTime = DateTime.now();

          // Update notification with enhanced info
          service.setForegroundNotificationInfo(
            title: "GPS Tracking Active",
            content: "Speed: ${accurateSpeed.toStringAsFixed(1)} km/h\n"
                    "Accuracy: ${position.accuracy.toStringAsFixed(1)}m\n"
                    "Location: ${position.latitude.toStringAsFixed(6)}, ${position.longitude.toStringAsFixed(6)}\n"
                    "Reason: $reason",
          );
        } else {
          print('Skipping location update - no significant change detected');
          
          // Still update notification to show we're active
          service.setForegroundNotificationInfo(
            title: "GPS Tracking Active",
            content: "Monitoring... Speed: ${accurateSpeed.toStringAsFixed(1)} km/h\n"
                    "Accuracy: ${position.accuracy.toStringAsFixed(1)}m",
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

// NEW METHOD: Initialize method channels for background isolate
@pragma('vm:entry-point')
void _initializeMethodChannels() {
  try {
    print('🔄 Initializing method channels for background isolate...');
    
    // Service channel
    const MethodChannel serviceChannel = MethodChannel('com.example.twtracking/service');
    serviceChannel.setMethodCallHandler((call) async {
      print('BACKGROUND_SERVICE_CHANNEL method called: ${call.method}');
      
      switch (call.method) {
        case 'testMethodChannel':
          print('🧪 BACKGROUND TEST METHOD CHANNEL CALLED - SERVICE CHANNEL IS WORKING');
          return "Background service channel is working!";
          
        case 'getCurrentIgStatus':
          try {
            print('🔄 Getting current igStatus from background service channel');
            // Get igStatus from SharedPreferences since we're in background
            final prefs = await SharedPreferences.getInstance();
            final igStatus = prefs.getInt('current_ig_status') ?? 0;
            print('✅ Current igStatus from background: $igStatus');
            return igStatus;
          } catch (e) {
            print('❌ Error getting igStatus from background: $e');
            return 0;
          }
          
        case 'getCarPowerManagerStatus':
          try {
            print('🔍 Getting CarPowerManager status from background service channel');
            // Return a simplified status since we're in background
            final status = {
              'isInitialized': true,
              'isConnected': false,
              'carPowerManagerExists': false,
              'currentAccState': false,
              'currentIgStatus': 0,
              'hasAccCallback': false,
              'hasSleepCallback': false,
              'hasPowerStateListener': false,
            };
            
            print('✅ Background CarPowerManager status: $status');
            return {
              'status': status,
              'isProperlyInitialized': false,
            };
          } catch (e) {
            print('❌ Error getting CarPowerManager status from background: $e');
            return null;
          }
          
        case 'triggerPowerStateCheck':
          try {
            print('🔄 Triggering power state check from background service channel');
            // Read the current igStatus from SharedPreferences (set by native side)
            final prefs = await SharedPreferences.getInstance();
            final currentIgStatus = prefs.getInt('current_ig_status') ?? 0;
            final timestamp = prefs.getInt('ig_status_timestamp') ?? 0;
            
            print('✅ Background power state check - current igStatus: $currentIgStatus');
            print('   - Timestamp: $timestamp');
            print('   - Last updated: ${DateTime.fromMillisecondsSinceEpoch(timestamp)}');
            
            // Don't change the igStatus, just return the current state
            return {
              'currentIgStatus': currentIgStatus,
              'timestamp': timestamp,
              'lastUpdated': DateTime.fromMillisecondsSinceEpoch(timestamp).toString(),
            };
          } catch (e) {
            print('❌ Error triggering power state check from background: $e');
            return false;
          }
          
        case 'simulateAccStateChange':
          try {
            final isAccOn = call.arguments['isAccOn'] as bool? ?? false;
            print('🧪 Simulating ACC state change from background: $isAccOn');
            
            final prefs = await SharedPreferences.getInstance();
            final newIgStatus = isAccOn ? 1 : 0;
            
            await prefs.setInt('current_ig_status', newIgStatus);
            await prefs.setInt('ig_status_timestamp', DateTime.now().millisecondsSinceEpoch);
            
            print('✅ Background ACC state simulation completed: igStatus = $newIgStatus');
            return true;
          } catch (e) {
            print('❌ Error simulating ACC state change from background: $e');
            return false;
          }
          
        case 'testAccStateDetection':
          try {
            print('🧪 Testing ACC state detection from background service channel');
            
            // Get current status from SharedPreferences
            final prefs = await SharedPreferences.getInstance();
            final currentIgStatus = prefs.getInt('current_ig_status') ?? 0;
            final timestamp = prefs.getInt('ig_status_timestamp') ?? 0;
            
            print('✅ Background ACC state detection test:');
            print('   - Current igStatus: $currentIgStatus');
            print('   - Timestamp: $timestamp');
            print('   - Last updated: ${DateTime.fromMillisecondsSinceEpoch(timestamp)}');
            
            return {
              'currentIgStatus': currentIgStatus,
              'timestamp': timestamp,
              'lastUpdated': DateTime.fromMillisecondsSinceEpoch(timestamp).toString(),
            };
          } catch (e) {
            print('❌ Error testing ACC state detection from background: $e');
            return null;
          }
          
        case 'testSpecificPowerState':
          try {
            final testState = call.arguments as int? ?? 0;
            print('🧪 Testing specific power state from background: $testState');
            
            // Read the current igStatus from SharedPreferences (set by native side)
            final prefs = await SharedPreferences.getInstance();
            final currentIgStatus = prefs.getInt('current_ig_status') ?? 0;
            final timestamp = prefs.getInt('ig_status_timestamp') ?? 0;
            
            print('✅ Background power state test - current state:');
            print('   - Test state: $testState');
            print('   - Current igStatus: $currentIgStatus');
            print('   - Timestamp: $timestamp');
            print('   - Last updated: ${DateTime.fromMillisecondsSinceEpoch(timestamp)}');
            
            return {
              'testState': testState,
              'currentIgStatus': currentIgStatus,
              'timestamp': timestamp,
              'lastUpdated': DateTime.fromMillisecondsSinceEpoch(timestamp).toString(),
            };
          } catch (e) {
            print('❌ Error testing specific power state from background: $e');
            return false;
          }
          
        default:
          print('⚠️ Background service method not implemented: ${call.method}');
          return null;
      }
    });
    
    // AVN sleep channel
    const MethodChannel avnSleepChannel = MethodChannel('com.example.twtracking/avn_sleep');
    avnSleepChannel.setMethodCallHandler((call) async {
      print('BACKGROUND_AVN_SLEEP_CHANNEL method called: ${call.method}');
      
      switch (call.method) {
        case 'getCurrentIgStatus':
          try {
            print('🔄 Getting current igStatus from background AVN sleep channel');
            final prefs = await SharedPreferences.getInstance();
            final igStatus = prefs.getInt('current_ig_status') ?? 0;
            print('✅ Current igStatus from background AVN: $igStatus');
            return igStatus;
          } catch (e) {
            print('❌ Error getting igStatus from background AVN: $e');
            return 0;
          }
          
        case 'triggerPowerStateCheck':
          try {
            print('🔄 Triggering power state check from background AVN sleep channel');
            // Read the current igStatus from SharedPreferences (set by native side)
            final prefs = await SharedPreferences.getInstance();
            final currentIgStatus = prefs.getInt('current_ig_status') ?? 0;
            final timestamp = prefs.getInt('ig_status_timestamp') ?? 0;
            
            print('✅ Background AVN power state check - current igStatus: $currentIgStatus');
            print('   - Timestamp: $timestamp');
            print('   - Last updated: ${DateTime.fromMillisecondsSinceEpoch(timestamp)}');
            
            // Don't change the igStatus, just return the current state
            return {
              'currentIgStatus': currentIgStatus,
              'timestamp': timestamp,
              'lastUpdated': DateTime.fromMillisecondsSinceEpoch(timestamp).toString(),
            };
          } catch (e) {
            print('❌ Error triggering power state check from background AVN: $e');
            return false;
          }
          
        default:
          print('⚠️ Background AVN sleep method not implemented: ${call.method}');
          return null;
      }
    });
    
    print('✅ Method channels initialized for background isolate');
    
  } catch (e) {
    print('❌ Error initializing method channels for background isolate: $e');
  }
}
