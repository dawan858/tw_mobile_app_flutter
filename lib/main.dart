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
import 'package:package_info_plus/package_info_plus.dart';
import 'services/config_service.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  
  final prefs = await SharedPreferences.getInstance();
  final configService = ConfigService();

  // Initialize background service
  await initializeService();
  
  // Start the service immediately
  final service = FlutterBackgroundService();
  await service.startService();

  // Get IMEI and wait for it to be available
  String? imei;
  int retryCount = 0;
  while (imei == null || imei == 'unknown') {
    try {
      imei = await const MethodChannel('com.trackingWorld.tracking/device_info').invokeMethod('getImei');
      if (imei != null && imei.isNotEmpty) {
        await prefs.setString('imei', imei);
        break;
      }
    } catch (e) {
      print('Error getting IMEI: $e');
    }
    retryCount++;
    if (retryCount > 10) break; // Prevent infinite loop
    await Future.delayed(const Duration(seconds: 1)); // Wait before retrying
  }

  if (imei != null && imei != 'unknown') {
    // Fetch and store default config
    final isConfigInitialized = prefs.getBool('flutter.isConfigInitialized') ?? false;
    if (!isConfigInitialized) {
      await configService.fetchDefaultConfigFromServer();
      await configService.sendDefaultConfigToServer(imei);
      await prefs.setBool('flutter.isConfigInitialized', true);
    }
    
    // Fetch configuration from server
    await configService.fetchConfigFromServer(imei);
  }
  
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
  String _appVersion = '';
  
  // Satellite data
  int _totalSatellites = 0;
  int _connectedSatellites = 0;
  
  StreamSubscription<Position>? _positionStreamSubscription;
  bool _isTracking = false;
  final DeviceInfoPlugin deviceInfo = DeviceInfoPlugin();
  final ApiService _apiService = ApiService();
  
  static const serviceChannel = MethodChannel('com.trackingWorld.tracking/service');
  static const MethodChannel satelliteChannel = MethodChannel('com.trackingWorld.tracking/satellite');
  
  @override
  void initState() {
    super.initState();
    _loadAppVersion();
    _checkAllPermissions();
    _getDeviceInfo();
    _saveImei();
    _loadConfiguration();
    
    // Set igStatus to 1 when app starts
    setState(() {
      _igStatus = 1;
    });
    
    // Listen for permission events from native side
    const MethodChannel('com.trackingWorld.tracking/device_info').setMethodCallHandler((call) async {
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

    // Get satellite data once after permissions and service checks
    _getSatelliteData();

    // Check if service is already running
    _checkServiceStatus();
    _startTracking();
  }
  
  @override
  void dispose() {
    // Set igStatus to 0 when app is closed
    _igStatus = 0;
    _positionStreamSubscription?.cancel();
    // Don't stop the service on dispose, let it run in background
    super.dispose();
  }

  // Get device information
  Future<void> _getDeviceInfo() async {
    try {
      try {
        final String? imei = await const MethodChannel('com.trackingWorld.tracking/device_info').invokeMethod('getImei');
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

    // Request storage permissions
    if (await Permission.storage.isDenied) {
      await Permission.storage.request();
    }

    // Request media permissions for Android 13+
    if (await Permission.photos.isDenied) {
      await Permission.photos.request();
    }

    if (await Permission.videos.isDenied) {
      await Permission.videos.request();
    }
  }

  // Show permission dialog
  void _showPermissionDialog(String permissionType) {
    showDialog(
      context: context,
      barrierDismissible: false,
      builder: (BuildContext context) {
        return AlertDialog(
          title: Text('$permissionType Permission Required'),
          content: Text('This app needs $permissionType permission to function properly. Please grant the permission in settings.'),
          actions: <Widget>[
            TextButton(
              child: const Text('Open Settings'),
              onPressed: () async {
                Navigator.of(context).pop();
                await openAppSettings();
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
          double rawSpeed = position.speed * 3.6;
          _speed = rawSpeed < 0 ? 0 : rawSpeed;
          _latitude = position.latitude;
          _longitude = position.longitude;
          
          // Update timestamps and IDs
          _deviceRDT = DateFormat("dd/MM/yyyy HH:mm:ss.SSS").format(DateTime.now());
          _gmtSettings = "GMT+${DateTime.now().timeZoneOffset.inHours}:00 ${DateTime.now().year}";
          _time = DateTime.now().millisecondsSinceEpoch;
          _localPrimaryId = (_localPrimaryId + 1) % 100000;
          
          // Update reason based on movement
          _updateReason(position);
        });
      }
    }, onError: (error) {
      debugPrint('Error getting location: $error');
    });
  }

  void _updateReason(Position position) {
    final prefs = SharedPreferences.getInstance();
    prefs.then((prefs) {
      final angleThreshold = prefs.getDouble('flutter.angleThreshold') ?? 45.0;
      final overSpeedingThreshold = prefs.getDouble('flutter.overSpeedingThreshold') ?? 60.0;
      
      if (position.speed * 3.6 > overSpeedingThreshold) {
        _reason = "Over Speeding";
      } else if (_currentPosition != null) {
        final bearingChange = (_bearing - _currentPosition!.heading).abs();
        final normalizedBearingChange = bearingChange > 180 ? 360 - bearingChange : bearingChange;
        
        if (position.speed * 3.6 >= 5 && normalizedBearingChange > angleThreshold) {
          _reason = "Turn";
        } else if (position.speed * 3.6 > 1) {
          _reason = "Move";
        } else {
          _reason = "Idle";
        }
      }
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
      'versionNo': _appVersion,
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
      final String? imei = await const MethodChannel('com.trackingWorld.tracking/device_info').invokeMethod('getImei');
      if (imei != null && imei.isNotEmpty) {
        final prefs = await SharedPreferences.getInstance();
        await prefs.setString('imei', imei);
      }
    } catch (e) {
      debugPrint('Error saving IMEI: $e');
    }
  }

  Future<void> _getSatelliteData() async {
    try {
      final result = await satelliteChannel.invokeMethod('getSatelliteData');
      if (result != null) {
        setState(() {
          _totalSatellites = result['totalSatellites'] ?? 0;
          _connectedSatellites = result['connectedSatellites'] ?? 0;
        });
      }
    } catch (e) {
      debugPrint('Error getting satellite data: $e');
    }
  }

  Future<void> _loadAppVersion() async {
    final info = await PackageInfo.fromPlatform();
    setState(() {
      _appVersion = 'v${info.version}';
    });
  }

  Future<void> _loadConfiguration() async {
    final configService = ConfigService();
    final config = await configService.getConfig();
    
    // Update tracking intervals based on configuration
    if (mounted) {
      setState(() {
        // Update any UI elements that depend on configuration
        _updateTrackingParameters(config);
      });
    }
  }

  void _updateTrackingParameters(Map<String, dynamic> config) {
    // Update tracking parameters based on configuration
    final prefs = SharedPreferences.getInstance();
    prefs.then((prefs) {
      prefs.setInt('flutter.gpsTimer', int.parse(config['gpsTimer'] ?? '5'));
      prefs.setInt('flutter.uploadTimer', int.parse(config['uploadTimer'] ?? '10'));
      prefs.setDouble('flutter.angleThreshold', double.parse(config['angleThreshold'] ?? '45.0'));
      prefs.setDouble('flutter.overSpeedingThreshold', double.parse(config['overSpeedingThreshold'] ?? '60.0'));
      prefs.setDouble('flutter.distanceThreshold', double.parse(config['distanceThreshold'] ?? '1000.0'));
      prefs.setInt('flutter.movingTimer', int.parse(config['movingTimer'] ?? '60'));
      prefs.setInt('flutter.stopTimer', int.parse(config['stopTimer'] ?? '130'));
    });
  }

  @override
  Widget build(BuildContext context) {
    return WelcomeScreen(
      imei: _imei,
      version: _appVersion,
      trackingData: {
        'totalSatellites': _totalSatellites.toString(),
        'connectedSatellites': _connectedSatellites.toString(),
        'status': _isTracking ? 'Location Found' : 'Not Tracking',
        'latitude': _currentPosition?.latitude.toStringAsFixed(6) ?? _latitude.toStringAsFixed(6),
        'longitude': _currentPosition?.longitude.toStringAsFixed(6) ?? _longitude.toStringAsFixed(6),
        'altitude': _currentPosition?.altitude.toStringAsFixed(3) ?? _altitude.toStringAsFixed(3),
        'angle': _currentPosition?.heading.toStringAsFixed(3) ?? _bearing.toStringAsFixed(3),
        'speed': _currentPosition?.speed.toStringAsFixed(3) ?? _speed.toStringAsFixed(3),
        'accuracy': _currentPosition?.accuracy.toStringAsFixed(3) ?? _accuracy.toStringAsFixed(3),
        'lastPollTime': _deviceRDT,
        'localTime': DateFormat('yyyy-MM-dd HH:mm:ss').format(DateTime.now()),
        'gmt': _gmtSettings,
        'pendingData': '0', // This would require sync service info
        'server': 'Connected', // This would require network status check
        'refreshTime': DateFormat('yyyy-MM-dd HH:mm:ss').format(DateTime.now()),
      },
      onInfoTap: () async {
        await _getSatelliteData();
        setState(() {}); // Ensure UI is updated with latest satellite data
        Navigator.push(
          context,
          MaterialPageRoute(
            builder: (context) => LiveStatusScreen(
              trackingData: {
                'totalSatellites': _totalSatellites.toString(),
                'connectedSatellites': _connectedSatellites.toString(),
                'status': _isTracking ? 'Location Found' : 'Not Tracking',
                'latitude': _currentPosition?.latitude.toStringAsFixed(6) ?? _latitude.toStringAsFixed(6),
                'longitude': _currentPosition?.longitude.toStringAsFixed(6) ?? _longitude.toStringAsFixed(6),
                'altitude': _currentPosition?.altitude.toStringAsFixed(3) ?? _altitude.toStringAsFixed(3),
                'angle': _currentPosition?.heading.toStringAsFixed(3) ?? _bearing.toStringAsFixed(3),
                'speed': _currentPosition?.speed.toStringAsFixed(3) ?? _speed.toStringAsFixed(3),
                'accuracy': _currentPosition?.accuracy.toStringAsFixed(3) ?? _accuracy.toStringAsFixed(3),
                'lastPollTime': _deviceRDT,
                'localTime': DateFormat('yyyy-MM-dd HH:mm:ss').format(DateTime.now()),
                'gmt': _gmtSettings,
                'pendingData': '0',
                'server': 'Connected',
                'refreshTime': DateFormat('yyyy-MM-dd HH:mm:ss').format(DateTime.now()),
              },
            ),
          ),
        );
      },
    );
  }
}