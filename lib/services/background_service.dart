import 'dart:async';
import 'dart:ui';
import 'package:flutter_background_service/flutter_background_service.dart';
import 'package:flutter_background_service_android/flutter_background_service_android.dart';
import 'package:geolocator/geolocator.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:http/http.dart' as http;
import 'dart:convert';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';

@pragma('vm:entry-point')
Future<void> initializeService() async {
  final service = FlutterBackgroundService();
  final FlutterLocalNotificationsPlugin flutterLocalNotificationsPlugin = FlutterLocalNotificationsPlugin();

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
    //distanceFilter: 10, // Only update if moved 10 meters
    //timeLimit: Duration(seconds: 5), // Minimum time between updates
  );

  // Get last known position
  Position? lastPosition;
  DateTime? lastUpdateTime;
  const minUpdateInterval = Duration(seconds: 5);
  const minDistance = 10.0; // meters

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
            
            // Update last position and time
            lastPosition = position;
            lastUpdateTime = now;

            // Send to server
            await _sendLocationToServer(position);
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

Future<void> _sendLocationToServer(Position position) async {
  try {
    final prefs = await SharedPreferences.getInstance();
    final imei = prefs.getString('imei') ?? 'unknown';
    
    final response = await http.post(
      Uri.parse('http://ec2-3-83-201-132.compute-1.amazonaws.com:3000/api/location'),
      headers: {'Content-Type': 'application/json'},
      body: jsonEncode({
        'latitude': position.latitude,
        'longitude': position.longitude,
        'accuracy': position.accuracy,
        'altitude': position.altitude,
        'speed': position.speed,
        'heading': position.heading,
        'imei': imei,
        'timestamp': DateTime.now().toIso8601String(),
      }),
    );

    if (response.statusCode != 200) {
      print('Failed to send location data: ${response.statusCode}');
      print('Response body: ${response.body}');
    } else {
      print('Successfully sent location data');
    }
  } catch (e) {
    print('Error sending location data: $e');
  }
} 