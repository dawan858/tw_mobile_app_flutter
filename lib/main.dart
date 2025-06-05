import 'package:flutter/material.dart';
import 'package:geolocator/geolocator.dart';
import 'package:intl/intl.dart';
import 'package:device_info_plus/device_info_plus.dart';
import 'dart:async';
import 'package:permission_handler/permission_handler.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:flutter_background_service/flutter_background_service.dart';
import 'services/api_service.dart';
import 'services/background_service.dart';
import 'package:qr_flutter/qr_flutter.dart';
import 'welcome.dart';
import 'live_status_screen.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  
  // Initialize background service
  await initializeService();
  
  // Start the service immediately
  final service = FlutterBackgroundService();
  await service.startService();
  
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,  
      title: 'GPS Tracker',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(
          seedColor: Colors.blue,
          brightness: Brightness.light,
        ),
        useMaterial3: true,
      ),
      home: const GPSTracker(),
    );
  }
}

class GPSTracker extends StatefulWidget {
  const GPSTracker({super.key});

  @override
  State<GPSTracker> createState() => _GPSTrackerState();
}

class _GPSTrackerState extends State<GPSTracker> {
  // Location data
  Position? _currentPosition;
  double _accuracy = 3.9;
  double _altitude = 198;
  double _bearing = 201;
  double _speed = 95;
  double _latitude = 31.3025483;
  double _longitude = 74.0778433;
  
  // Device data
  String _deviceRDT = "30/01/2025 02:04:27.703";
  String _emailAddress = "email";
  String _gmtSettings = "GMT+05:00 2025";
  int _igStatus = 1;
  String _imei = "865632050026800";
  int _localPrimaryId = 10253;
  String _name = "865632050026800";
  String _phoneNo = "TST123";
  String _provider = "fused";
  String _reason = "Turn";
  int _time = 1738227867703;
  String _versionNo = "v 250111";
  
  StreamSubscription<Position>? _positionStreamSubscription;
  bool _isTracking = false;
  final DeviceInfoPlugin deviceInfo = DeviceInfoPlugin();
  final ApiService _apiService = ApiService();
  
  static const platform = MethodChannel('com.trackingWorld.tracking/device_info');
  static const serviceChannel = MethodChannel('com.trackingWorld.tracking/service');
  
  @override
  void initState() {
    super.initState();
    _checkAllPermissions();
    _getDeviceInfo();
    _saveImei();
    
    // Listen for permission events from native side
    platform.setMethodCallHandler((call) async {
      switch (call.method) {
        case 'onPermissionGranted':
          // Permission was granted, try getting IMEI again
          await _getDeviceInfo();
          break;
        case 'onPermissionDenied':
          if (mounted) {
            ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
              content: Text('Phone state permission is required to get IMEI'),
            ));
          }
          break;
      }
    });

    // Check if service is already running
    _checkServiceStatus();
  }
  
  @override
  void dispose() {
    _positionStreamSubscription?.cancel();
    // Don't stop the service on dispose, let it run in background
    super.dispose();
  }

  // Get device information
  Future<void> _getDeviceInfo() async {
    try {
      try {
        final String? imei = await platform.invokeMethod('getImei');
        if (imei != null && imei.isNotEmpty) {
          setState(() {
            _imei = imei;
            _name = imei; // Using IMEI as name for consistency
          });
        } else {
          debugPrint('Failed to get IMEI: IMEI is null or empty');
        }
      } on PlatformException catch (e) {
        debugPrint('Failed to get IMEI: ${e.message}');
      }

      final androidInfo = await deviceInfo.androidInfo;
      setState(() {
        _phoneNo = androidInfo.model;
        _versionNo = "v ${androidInfo.version.release}";
      });
    } catch (e) {
      debugPrint('Error getting device info: $e');
    }
  }

  // Check all required permissions
  Future<void> _checkAllPermissions() async {
    // Request phone state permission first
    final phoneStatus = await Permission.phone.request();
    if (phoneStatus.isDenied) {
      _showPermissionDialog('Phone State');
      return;
    }

    // Check location services
    bool serviceEnabled = await Geolocator.isLocationServiceEnabled();
    if (!serviceEnabled) {
      _showPermissionDialog('Location Services');
      return;
    }

    // Check location permission
    LocationPermission permission = await Geolocator.checkPermission();
    if (permission == LocationPermission.denied) {
      permission = await Geolocator.requestPermission();
      if (permission == LocationPermission.denied) {
        _showPermissionDialog('Location');
        return;
      }
    }
    
    if (permission == LocationPermission.deniedForever) {
      _showPermissionDialog('Location (Permanent)');
      return;
    }

    // Request notification permission for Android 13+
    if (await Permission.notification.isDenied) {
      await Permission.notification.request();
    }

    // Request background location permission
    if (await Permission.locationWhenInUse.isGranted) {
      final backgroundStatus = await Permission.locationAlways.request();
      if (backgroundStatus.isDenied) {
        _showPermissionDialog('Background Location');
        return;
      }
    }

    // If all permissions are granted, proceed with initialization
    await _getDeviceInfo();
    _startTrackingService();
  }

  void _showPermissionDialog(String permissionType) {
    showDialog(
      context: context,
      barrierDismissible: false,
      builder: (BuildContext context) {
        return AlertDialog(
          title: const Text('Permission Required'),
          content: Text('$permissionType permission is required to use this app. Please grant the permission in settings.'),
          actions: <Widget>[
            TextButton(
              child: const Text('Open Settings'),
              onPressed: () {
                openAppSettings();
                Navigator.of(context).pop();
              },
            ),
            TextButton(
              child: const Text('Exit App'),
              onPressed: () {
                SystemNavigator.pop();
              },
            ),
          ],
        );
      },
    );
  }

  // Start tracking location
  void _startTracking() {
    debugPrint('Starting tracking...');
    setState(() {
      _isTracking = true;
    });
    
    // Start the native service for background tracking
    _startTrackingService();
    
    // Only use Flutter Geolocator for UI updates when app is in foreground
    const LocationSettings locationSettings = LocationSettings(
      accuracy: LocationAccuracy.bestForNavigation,
    );
    
    _positionStreamSubscription = Geolocator.getPositionStream(
      locationSettings: locationSettings
    ).listen((Position position) {
      debugPrint('Received position update: ${position.latitude}, ${position.longitude}');
      if (mounted) {
        setState(() {
          _currentPosition = position;
          _accuracy = position.accuracy;
          _altitude = position.altitude;
          _bearing = position.heading;
          _speed = position.speed * 3.6;
          _latitude = position.latitude;
          _longitude = position.longitude;
          
          // Update timestamps and IDs
          _deviceRDT = DateFormat("dd/MM/yyyy HH:mm:ss.SSS").format(DateTime.now());
          _gmtSettings = "GMT+${DateTime.now().timeZoneOffset.inHours}:00 ${DateTime.now().year}";
          _time = DateTime.now().millisecondsSinceEpoch;
          _localPrimaryId = (_localPrimaryId + 1) % 100000;
        });
      }
    }, onError: (error) {
      debugPrint('Error getting location: $error');
    });
  }

  // Stop tracking location
  void _stopTracking() {
    debugPrint('Stopping tracking...');
    _positionStreamSubscription?.cancel();
    _stopTrackingService();
    if (mounted) {
      setState(() {
        _isTracking = false;
      });
    }
  }
  
  // Send location data to server
  Future<void> _sendLocationData() async {
    // Create a map of all tracked data
    final Map<String, dynamic> locationData = {
      'accuracy': _accuracy,
      'altitude': _altitude,
      'bearing': _bearing,
      'deviceRDT': _deviceRDT,
      'emailAddress': _emailAddress,
      'gmtSettings': _gmtSettings,
      'igStatus': _igStatus,
      'imei': _imei,
      'latitude': _latitude,
      'localPrimaryId': _localPrimaryId,
      'longitude': _longitude,
      'name': _name,
      'phoneNo': _phoneNo,
      'provider': _provider,
      'reason': _reason,
      'speed': _speed,
      'time': _time,
      'versionNo': _versionNo,
      'timestamp': DateTime.now().toIso8601String(),
    };
    
    try {
      final success = await _apiService.sendLocationData(locationData);
      if (success) {
        debugPrint('Data sent successfully to backend');
      } else {
        debugPrint('Failed to send data to backend');
      }
    } catch (e) {
      debugPrint('Error sending data to backend: $e');
    }
    
    // For debugging purposes
    debugPrint('Location data: $locationData');
  }

  // Check service status
  Future<void> _checkServiceStatus() async {
    try {
      final bool isRunning = await serviceChannel.invokeMethod('isServiceRunning');
      setState(() {
        _isTracking = isRunning;
      });
    } catch (e) {
      debugPrint('Error checking service status: $e');
    }
  }

  // Start tracking service
  Future<void> _startTrackingService() async {
    try {
      final bool started = await serviceChannel.invokeMethod('startService');
      if (started) {
        setState(() {
          _isTracking = true;
        });
      } else {
        debugPrint('Failed to start service');
      }
    } catch (e) {
      debugPrint('Error starting service: $e');
    }
  }

  // Stop tracking service
  Future<void> _stopTrackingService() async {
    try {
      final bool stopped = await serviceChannel.invokeMethod('stopService');
      if (stopped) {
        setState(() {
          _isTracking = false;
        });
      } else {
        debugPrint('Failed to stop service');
      }
    } catch (e) {
      debugPrint('Error stopping service: $e');
    }
  }

  Future<void> _saveImei() async {
    try {
      final String? imei = await platform.invokeMethod('getImei');
      if (imei != null && imei.isNotEmpty) {
        final prefs = await SharedPreferences.getInstance();
        await prefs.setString('imei', imei);
      }
    } catch (e) {
      debugPrint('Error saving IMEI: $e');
    }
  }

  @override
  Widget build(BuildContext context) {
    return WelcomeScreen(
      imei: _imei,
      version: _versionNo,
      onInfoTap: () {
        Navigator.push(
          context,
          MaterialPageRoute(
            builder: (context) => LiveStatusScreen(
              trackingData: {
                'totalSatellites': '45', // Replace with real value if available
                'connectedSatellites': '10', // Replace with real value if available
                'status': _isTracking ? 'Location Found' : 'Not Tracking',
                'latitude': _latitude.toStringAsFixed(6),
                'longitude': _longitude.toStringAsFixed(6),
                'altitude': _altitude.toStringAsFixed(3),
                'angle': _bearing.toStringAsFixed(3),
                'speed': _speed.toStringAsFixed(3),
                'accuracy': _accuracy.toStringAsFixed(3),
                'lastPollTime': _deviceRDT,
                'localTime': DateFormat('yyyy-MM-dd HH:mm:ss').format(DateTime.now()),
                'gmt': _gmtSettings,
                'pendingData': '0', // Replace with real value if available
                'server': 'Connected', // Replace with real value if available
                'refreshTime': DateFormat('yyyy-MM-dd HH:mm:ss').format(DateTime.now()),
              },
            ),
          ),
        );
      },
    );
  }
}