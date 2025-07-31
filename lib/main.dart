import 'package:flutter/material.dart';
import 'package:geolocator/geolocator.dart';
import 'package:intl/intl.dart';
import 'package:device_info_plus/device_info_plus.dart';
import 'dart:async';
import 'package:permission_handler/permission_handler.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:flutter_background_service/flutter_background_service.dart';
import 'package:tracking_world/services/device_admin_manager.dart';
import 'package:firebase_core/firebase_core.dart';
import 'services/api_service.dart';
import 'services/background_service.dart';
import 'services/permission_flow_manager.dart';
import 'services/ignition_monitor_service.dart';
import 'package:qr_flutter/qr_flutter.dart';
import 'welcome.dart';
import 'live_status_screen.dart';
import 'package:package_info_plus/package_info_plus.dart';
import 'services/config_service.dart';
import 'services/log_broadcast_receiver.dart';
import 'services/log_upload_service.dart';
import 'services/firebase_service.dart';
import 'firebase_options.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  
  // Initialize Firebase
  try {
    await Firebase.initializeApp(
      options: DefaultFirebaseOptions.currentPlatform,
    );
    print('✅ Firebase initialized in main');
  } catch (e) {
    print('❌ Error initializing Firebase in main: $e');
  }
  
  final prefs = await SharedPreferences.getInstance();
  final configService = ConfigService();

  // Initialize background service
  await initializeService();
  
  // Start the service immediately
  final service = FlutterBackgroundService();
  await service.startService();

  // Initialize ignition monitoring service
  final ignitionMonitorService = IgnitionMonitorService();
  print('🚀 Ignition monitoring service initialized');

  // Check if IMEI is already available from MainActivity
  String? imei = prefs.getString('imei');
  
  // If IMEI is not available from SharedPreferences, try to get it from method channel
  if (imei == null || imei.isEmpty || imei == 'unknown') {
    int retryCount = 0;
    while (imei == null || imei == 'unknown') {
      try {
        imei = await const MethodChannel('com.trackingWorld.tracking/device_info').invokeMethod('getImei');
        if (imei != null && imei.isNotEmpty && imei != 'unknown') {
          await prefs.setString('imei', imei);
          break;
        }
      } catch (e) {
        // Error getting IMEI from method channel
      }
      retryCount++;
      if (retryCount > 5) {
        break;
      }
      await Future.delayed(const Duration(seconds: 2)); // Wait before retrying
    }
  }

  if (imei != null && imei.isNotEmpty && imei != 'unknown') {
    // Fetch and store default config
    final isConfigInitialized = prefs.getBool('flutter.isConfigInitialized') ?? false;
    if (!isConfigInitialized) {
      await configService.fetchDefaultConfigFromServer();
      await configService.sendDefaultConfigToServer(imei);
      await prefs.setBool('flutter.isConfigInitialized', true);
    }
    
    // Fetch configuration from server
    await configService.fetchConfigFromServer(imei);
    
    // NEW: Fix configuration types before validation
    print('🔧 Fixing configuration types...');
    await configService.fixConfigurationTypes();
    
    // NEW: Validate configuration after loading
    print('🔍 Validating configuration after startup...');
    await configService.validateConfig();
    
    // NEW: Initialize and upload latest logs to endpoint
    try {
      final logUploadService = LogUploadService.instance;
      await logUploadService.uploadLatestLogs();
      print('📤 Latest logs queued for upload to endpoint');
      print('🔄 LogUploadService initialized and running');
    } catch (e) {
      print('⚠️ Error uploading latest logs: $e');
    }
    
    // NEW: Sync existing logs to Firebase
    try {
      final firebaseService = FirebaseService();
      await firebaseService.syncExistingLogs();
      print('🔥 Existing logs synced to Firebase');
    } catch (e) {
      print('⚠️ Error syncing logs to Firebase: $e');
    }
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

  bool _isDeviceAdminActive = false;

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
  int _igStatus = 0; // Default to ACC OFF
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
  int _lastIgStatus = 0; // Track previous igStatus for ignition change detection
  final DeviceInfoPlugin deviceInfo = DeviceInfoPlugin();
  final ApiService _apiService = ApiService();
  
  static const serviceChannel = MethodChannel('com.example.twtracking/service');
  static const MethodChannel satelliteChannel = MethodChannel('com.trackingWorld.tracking/satellite');
  static const MethodChannel avnSleepChannel = MethodChannel('com.example.twtracking/avn_sleep');
  
  // Permission flow manager
  final PermissionFlowManager _permissionManager = PermissionFlowManager();
  bool _permissionsGranted = false;

  @override
  void initState() {
    super.initState();
    _loadAppVersion();
    _setupPermissionFlow();
    _loadConfiguration();
    
    // Load current igStatus from SharedPreferences (set by native side)
    _loadCurrentIgStatus();
    
    // Initialize device admin manager
    DeviceAdminManager.initialize();
    DeviceAdminManager.onDeviceAdminStatusChanged = (bool isGranted) {
      if (mounted) {
        setState(() {
          _isDeviceAdminActive = isGranted;
        });
        if (isGranted) {
          print('✅ Device admin permission granted - continuing app initialization');
          _initializeApp();
        }
      }
    };
    
    // Initialize log broadcast receiver
    LogBroadcastReceiver.initialize();
    
    // Listen for permission events from native side
    const MethodChannel('com.trackingWorld.tracking/device_info').setMethodCallHandler((call) async {
      switch (call.method) {
        case 'onPermissionGranted':
          // Permission was granted, try getting IMEI again
          await _initializeApp();
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

    // Start the initialization sequence
    _initializeApp();
  }
  
  @override
  void dispose() {
    // Don't reset igStatus - preserve the actual state from native side
    _positionStreamSubscription?.cancel();
    // Don't stop the service on dispose, let it run in background
    super.dispose();
  }

  // Setup permission flow with callbacks
  void _setupPermissionFlow() {
    _permissionManager.onAllPermissionsGranted = () async {
      setState(() {
        _permissionsGranted = true;
      });
      print('✅ All permissions granted - app is ready');
      
      // Now that permissions are granted, initialize the app
      await _initializeApp();
    };

    _permissionManager.onPermissionDenied = (String permissionName) {
      print('❌ Permission denied: $permissionName');
      _showPermissionDeniedDialog(permissionName);
    };

    _permissionManager.onFlowCompleted = () async {
      print('✅ Permission flow completed');
      
      // Try to initialize app after permission flow completes
      await _initializeApp();
    };

    // Start permission flow after a short delay
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _startPermissionFlow();
    });
  }

  // Start the permission flow
  Future<void> _startPermissionFlow() async {
    // Check if critical permissions are already granted
    final criticalGranted = await _permissionManager.checkCriticalPermissions();
    if (criticalGranted) {
      setState(() {
        _permissionsGranted = true;
      });
      print('✅ Critical permissions already granted');
      
      // Initialize app since permissions are already granted
      await _initializeApp();
      return;
    }

    // Start the full permission flow
    print('🔄 Starting permission flow...');
    await _permissionManager.startPermissionFlow(context);
  }

  // Show dialog when critical permission is denied
  void _showPermissionDeniedDialog(String permissionName) {
    showDialog(
      context: context,
      barrierDismissible: false,
      builder: (BuildContext context) {
        return AlertDialog(
          title: const Text('Permission Required'),
          content: Text(
            '$permissionName permission is required for this app to function properly. '
            'Please grant the permission to continue.'
          ),
          actions: <Widget>[
            TextButton(
              child: const Text('Open Settings'),
              onPressed: () async {
                Navigator.of(context).pop();
                await openAppSettings();
              },
            ),
            TextButton(
              child: const Text('Retry'),
              onPressed: () {
                Navigator.of(context).pop();
                _startPermissionFlow();
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

  // Get device information
  Future<bool> _getDeviceInfo() async {
    try {
      print('🔄 Attempting to get device info...');
      
      // Try to get IMEI
      try {
        final String? imei = await const MethodChannel('com.trackingWorld.tracking/device_info').invokeMethod('getImei');
        if (imei != null && imei.isNotEmpty && imei != 'unknown') {
          setState(() {
            _imei = imei;
            _name = imei; // Using IMEI as name for consistency
          });
          print('✅ IMEI obtained successfully: $imei');
        } else {
          print('❌ Failed to get IMEI: IMEI is null, empty, or unknown');
          return false;
        }
      } on PlatformException catch (e) {
        print('❌ Failed to get IMEI: ${e.message}');
        return false;
      }

      // Get device model
      try {
        final androidInfo = await deviceInfo.androidInfo;
        setState(() {
          _phoneNo = androidInfo.model;
        });
        print('✅ Device model obtained: ${androidInfo.model}');
      } catch (e) {
        print('⚠️ Failed to get device model: $e');
        // Don't fail the entire process for device model
      }
      
      return true;
    } catch (e) {
      print('❌ Error getting device info: $e');
      return false;
    }
  }

  // Start tracking location
  void _startTracking() {
    print('Starting tracking...');
    setState(() {
      _isTracking = true;
    });
    
    // Service is now started by native side after IMEI is obtained
    // No need to call _startTrackingService() here
    
    // Start periodic igStatus refresh timer
    Timer.periodic(const Duration(seconds: 5), (timer) {
      if (mounted) {
        _refreshIgStatus();
      } else {
        timer.cancel();
      }
    });
    
    // Only use Flutter Geolocator for UI updates when app is in foreground
    const LocationSettings locationSettings = LocationSettings(
      accuracy: LocationAccuracy.bestForNavigation,
    );
    
    _positionStreamSubscription = Geolocator.getPositionStream(
      locationSettings: locationSettings
    ).listen((Position position) async {
      print('Received position update: ${position.latitude}, ${position.longitude}');
      
      // Check for ignition state change
      final prefs = await SharedPreferences.getInstance();
      final currentIgStatus = prefs.getInt('current_ig_status') ?? 0;
      
      if (currentIgStatus != _lastIgStatus) {
        print('🔄 Ignition state changed in foreground: $_lastIgStatus -> $currentIgStatus');
        
        // Send location data with ignition change reason
        final ignitionReason = currentIgStatus == 1 ? "Ignition On" : "Ignition Off";
        await _sendLocationDataWithReason(ignitionReason);
        print('✅ Foreground location sent with ignition change reason: $ignitionReason');
        
        _lastIgStatus = currentIgStatus;
      }
      
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
      print('Error getting location: $error');
    });
  }

  void _updateReason(Position position) {
    final prefs = SharedPreferences.getInstance();
    prefs.then((prefs) {
      // Handle all possible types for backward compatibility
      double angleThreshold = _getDoubleValue(prefs, 'flutter.angleThreshold', 45.0);
      double overSpeedingThreshold = _getDoubleValue(prefs, 'flutter.overSpeedingThreshold', 60.0);
      double distanceThreshold = _getDoubleValue(prefs, 'flutter.distanceThreshold', 1000.0);
      
      print('🔍 Reason calculation - Config: Distance=${distanceThreshold}m, Angle=${angleThreshold}°, Speed=${overSpeedingThreshold}km/h');
      
      if (position.speed * 3.6 > overSpeedingThreshold) {
        _reason = "Over Speeding";
        print('✅ Over Speeding reason triggered: ${(position.speed * 3.6).toStringAsFixed(1)} km/h > ${overSpeedingThreshold} km/h');
      } else if (_currentPosition != null) {
        // Check for distance-based reason first
        final distance = Geolocator.distanceBetween(
          _currentPosition!.latitude, 
          _currentPosition!.longitude, 
          position.latitude, 
          position.longitude
        );
        
        print('📏 Distance calculation: ${distance.toStringAsFixed(2)}m (threshold: ${distanceThreshold}m)');
        print('📍 Last position: ${_currentPosition!.latitude}, ${_currentPosition!.longitude}');
        print('📍 Current position: ${position.latitude}, ${position.longitude}');
        
        if (distance >= distanceThreshold) {
          _reason = "Distance";
          print('✅ Distance reason triggered: ${distance.toStringAsFixed(2)}m >= ${distanceThreshold}m');
        } else {
          print('ℹ️ Distance below threshold: ${distance.toStringAsFixed(2)}m < ${distanceThreshold}m');
          
          final bearingChange = (_bearing - _currentPosition!.heading).abs();
          final normalizedBearingChange = bearingChange > 180 ? 360 - bearingChange : bearingChange;
          
          print('🧭 Bearing change: ${normalizedBearingChange.toStringAsFixed(2)}° (threshold: ${angleThreshold}°)');
          
          if (position.speed * 3.6 >= 5 && normalizedBearingChange > angleThreshold) {
            _reason = "Turn";
            print('✅ Turn reason triggered: ${normalizedBearingChange.toStringAsFixed(2)}° > ${angleThreshold}°');
          } else if (position.speed * 3.6 > 1) {
            _reason = "Move";
            print('✅ Move reason triggered: speed > 1 km/h');
          } else {
            _reason = "Idle";
            print('✅ Idle reason triggered: low speed');
          }
        }
      } else {
        print('📍 First position - no previous position for distance calculation');
      }
    });
  }

  // Stop tracking location
  void _stopTracking() {
    print('Stopping tracking...');
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
    await _sendLocationDataWithReason(_reason);
  }

  // Send location data to server with custom reason
  Future<void> _sendLocationDataWithReason(String reason) async {
    // Get current igStatus from SharedPreferences (set by native side)
    final prefs = await SharedPreferences.getInstance();
    final currentIgStatus = prefs.getInt('current_ig_status') ?? 0;
    
    // Create a map of all tracked data
    final Map<String, dynamic> locationData = {
      'accuracy': _accuracy,
      'altitude': _altitude,
      'bearing': _bearing,
      'deviceRDT': _deviceRDT,
      'emailAddress': _emailAddress,
      'gmtSettings': _gmtSettings,
      'igStatus': currentIgStatus, // Use the actual igStatus from SharedPreferences
      'imei': _imei,
      'latitude': _latitude,
      'localPrimaryId': _localPrimaryId,
      'longitude': _longitude,
      'name': _name,
      'phoneNo': _phoneNo,
      'provider': _provider,
      'reason': reason,
      'speed': _speed,
      'time': _time,
      'versionNo': _appVersion,
      'timestamp': DateTime.now().toIso8601String(),
    };
    
    try {
      final success = await _apiService.sendLocationData(locationData);
      if (success) {
        print('Data sent successfully to backend with reason: $reason');
      } else {
        print('Failed to send data to backend');
      }
    } catch (e) {
      print('Error sending data to backend: $e');
    }
    
    // For debugging purposes
    print('Location data: $locationData');
    print('Current igStatus sent to server: $currentIgStatus');
  }

  // Check service status
  Future<void> _checkServiceStatus() async {
    try {
      final bool isRunning = await serviceChannel.invokeMethod('isServiceRunning');
      setState(() {
        _isTracking = isRunning;
      });
    } catch (e) {
      print('Error checking service status: $e');
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
        print('Failed to start service');
      }
    } catch (e) {
      print('Error starting service: $e');
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
        print('Failed to stop service');
      }
    } catch (e) {
      print('Error stopping service: $e');
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
      print('Error saving IMEI: $e');
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
      print('Error getting satellite data: $e');
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
  
  // Utility method to clear and reset configuration (for debugging)
  Future<void> _clearConfiguration() async {
    try {
      print('🧹 Clearing configuration...');
      final prefs = await SharedPreferences.getInstance();
      
      // Clear all flutter.* configuration keys
      await prefs.remove('flutter.angleThreshold');
      await prefs.remove('flutter.overSpeedingThreshold');
      await prefs.remove('flutter.distanceThreshold');
      await prefs.remove('flutter.gpsTimer');
      await prefs.remove('flutter.uploadTimer');
      await prefs.remove('flutter.movingTimer');
      await prefs.remove('flutter.stopTimer');
      
      print('✅ Configuration cleared successfully');
      
      // Reload configuration with defaults
      print('🔄 Reloading configuration...');
      await _loadConfiguration();
      print('✅ Configuration reloaded');
    } catch (e) {
      print('❌ Error clearing configuration: $e');
    }
  }

  void _updateTrackingParameters(Map<String, dynamic> config) {
    // Update tracking parameters based on configuration
    final prefs = SharedPreferences.getInstance();
    prefs.then((prefs) async {
      try {
        print('🔄 Updating tracking parameters with config: $config');
        
        // Store values with proper type conversion
        final gpsTimer = int.parse(config['gpsTimer']?.toString() ?? '5');
        final uploadTimer = int.parse(config['uploadTimer']?.toString() ?? '10');
        final angleThreshold = double.parse(config['angleThreshold']?.toString() ?? '45.0');
        final overSpeedingThreshold = double.parse(config['overSpeedingThreshold']?.toString() ?? '60.0');
        final distanceThreshold = double.parse(config['distanceThreshold']?.toString() ?? '1000.0');
        final movingTimer = int.parse(config['movingTimer']?.toString() ?? '60');
        final stopTimer = int.parse(config['stopTimer']?.toString() ?? '130');
        
        // Store with correct types
        await prefs.setInt('flutter.gpsTimer', gpsTimer);
        await prefs.setInt('flutter.uploadTimer', uploadTimer);
        await prefs.setDouble('flutter.angleThreshold', angleThreshold);
        await prefs.setDouble('flutter.overSpeedingThreshold', overSpeedingThreshold);
        await prefs.setDouble('flutter.distanceThreshold', distanceThreshold);
        await prefs.setInt('flutter.movingTimer', movingTimer);
        await prefs.setInt('flutter.stopTimer', stopTimer);
        
        print('✅ Tracking parameters updated successfully:');
        print('   - gpsTimer: $gpsTimer (int)');
        print('   - uploadTimer: $uploadTimer (int)');
        print('   - angleThreshold: $angleThreshold (double)');
        print('   - overSpeedingThreshold: $overSpeedingThreshold (double)');
        print('   - distanceThreshold: $distanceThreshold (double)');
        print('   - movingTimer: $movingTimer (int)');
        print('   - stopTimer: $stopTimer (int)');
        
      } catch (e) {
        print('❌ Error updating tracking parameters: $e');
      }
    });
  }
  
  double _getDoubleValue(SharedPreferences prefs, String key, double defaultValue) {
    try {
      // Try to get as double first
      final doubleValue = prefs.getDouble(key);
      if (doubleValue != null) {
        return doubleValue;
      }
      
      // Try to get as int and convert
      final intValue = prefs.getInt(key);
      if (intValue != null) {
        return intValue.toDouble();
      }
      
      // Try to get as string and parse
      final stringValue = prefs.getString(key);
      if (stringValue != null) {
        return double.tryParse(stringValue) ?? defaultValue;
      }
      
      return defaultValue;
    } catch (e) {
      print('Error getting double value for $key: $e');
      return defaultValue;
    }
  }

  // Proper app initialization sequence
  Future<void> _initializeApp() async {
    print('🔄 Starting app initialization...');
    
    // Step 1: Get device info (IMEI) - this requires phone permission
    final deviceInfoSuccess = await _getDeviceInfo();
    
    // Step 2: Check if we have IMEI
    if (!deviceInfoSuccess || _imei.isEmpty || _imei == 'unknown') {
      print('⚠️ IMEI not available yet - waiting for permissions');
      return; // Wait for permissions to be granted
    }
    
    print('✅ IMEI obtained: $_imei');
    
    // Step 3: Save IMEI
    await _saveImei();
    
    // Step 4: Check device admin permission
    await _checkDeviceAdminPermission();
    
    // Step 5: Clear and reload configuration to ensure proper types
    await _clearConfiguration();
    
    // Step 6: Get satellite data
    await _getSatelliteData();
    
    // Step 7: Signal to native side that app is ready
    await _signalAppReady();
    
    // Step 8: Check service status and start tracking
    await _checkServiceStatus();
    _startTracking();
    
    print('✅ App initialization completed');
  }

  // Check and request device admin permission
  Future<void> _checkDeviceAdminPermission() async {
    try {
      print('🔄 Checking device admin permission...');
      
      // Check if device admin is already active
      final isActive = await DeviceAdminManager.isDeviceAdminActive();
      if (mounted) {
        setState(() {
          _isDeviceAdminActive = isActive;
        });
      }
      
      if (isActive) {
        print('✅ Device admin already active');
        return;
      }
      
      // Request device admin permission with dialog
      print('🔄 Requesting device admin permission...');
      final granted = await DeviceAdminManager.requestDeviceAdminWithDialog(context);
      
      if (mounted) {
        setState(() {
          _isDeviceAdminActive = granted;
        });
      }
      
      if (granted) {
        print('✅ Device admin permission granted');
      } else {
        print('⚠️ Device admin permission not granted - app may not work optimally in background');
      }
      
    } catch (e) {
      print('❌ Error checking device admin permission: $e');
    }
  }

  // Signal to native side that app is ready (IMEI obtained)
  Future<void> _signalAppReady() async {
    try {
      await serviceChannel.invokeMethod('appReady');
      print('✅ Signaled app ready to native side');
    } catch (e) {
      print('❌ Error signaling app ready: $e');
    }
  }

  // Check if app is properly initialized
  bool get isAppInitialized {
    return _permissionsGranted && _imei.isNotEmpty && _imei != 'unknown' && _isDeviceAdminActive;
  }

  // Get initialization status message
  String get initializationStatus {
    if (!_permissionsGranted) return 'Waiting for permissions...';
    if (_imei.isEmpty || _imei == 'unknown') return 'Getting device ID...';
    if (!_isDeviceAdminActive) return 'Device admin required';
    return 'Ready';
  }

  // Test power state detection
  static Future<Map<String, dynamic>?> testPowerState(int testState) async {
    try {
      print('🧪 Testing power state from main app: $testState');
      
      // Try service channel first
      final result = await serviceChannel.invokeMethod('testSpecificPowerState', testState);
      print('✅ Power state test result: $result');
      return result;
    } catch (e) {
      print('❌ Error testing power state: $e');
      return null;
    }
  }

  // Manual power state check
  static Future<void> manualPowerStateCheck() async {
    try {
      print('🔍 MANUAL POWER STATE CHECK');
      
      // Get current igStatus
      final currentIgStatus = await serviceChannel.invokeMethod('getCurrentIgStatus');
      print('Current igStatus: $currentIgStatus');
      
      // Trigger power state check
      final checkResult = await serviceChannel.invokeMethod('triggerPowerStateCheck');
      print('Power state check result: $checkResult');
      
      // Get updated igStatus
      final updatedIgStatus = await serviceChannel.invokeMethod('getCurrentIgStatus');
      print('Updated igStatus: $updatedIgStatus');
      
    } catch (e) {
      print('❌ Error in manual power state check: $e');
    }
  }

  // Get stable igStatus from SharedPreferences
  static Future<int> getStableIgStatus() async {
    try {
      print('🔍 Getting stable igStatus from SharedPreferences');
      
      final prefs = await SharedPreferences.getInstance();
      final igStatus = prefs.getInt('current_ig_status') ?? 0;
      final timestamp = prefs.getInt('ig_status_timestamp') ?? 0;
      
      print('✅ Stable igStatus: $igStatus');
      print('   - Timestamp: $timestamp');
      print('   - Last updated: ${DateTime.fromMillisecondsSinceEpoch(timestamp)}');
      
      return igStatus;
    } catch (e) {
      print('❌ Error getting stable igStatus: $e');
      return 0;
    }
  }

  // Load current igStatus from SharedPreferences
  Future<void> _loadCurrentIgStatus() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final currentIgStatus = prefs.getInt('current_ig_status') ?? 0;
      
      if (mounted) {
        setState(() {
          _igStatus = currentIgStatus;
        });
      }
      
      print('📥 Loaded current igStatus from SharedPreferences: $currentIgStatus');
    } catch (e) {
      print('❌ Error loading current igStatus: $e');
    }
  }

  // Refresh igStatus from SharedPreferences
  Future<void> _refreshIgStatus() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final currentIgStatus = prefs.getInt('current_ig_status') ?? 0;
      
      if (_igStatus != currentIgStatus) {
        if (mounted) {
          setState(() {
            _igStatus = currentIgStatus;
          });
        }
        print('🔄 igStatus updated from SharedPreferences: $_igStatus → $currentIgStatus');
      }
    } catch (e) {
      print('❌ Error refreshing igStatus: $e');
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Stack(
        children: [
          WelcomeScreen(
            imei: _imei,
            version: _appVersion,
            onInfoTap: () async {
              await _getSatelliteData();
              setState(() {}); // Ensure UI is updated with latest satellite data
              Navigator.push(
                context,
                MaterialPageRoute(
                  builder: (context) => const LiveStatusScreen(),
                ),
              );
            },
          ),
          
          // Permission status indicator
          if (!_permissionsGranted || !isAppInitialized)
            Positioned(
              top: MediaQuery.of(context).padding.top + 10,
              right: 10,
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                decoration: BoxDecoration(
                  color: isAppInitialized ? Colors.green.withOpacity(0.9) : Colors.orange.withOpacity(0.9),
                  borderRadius: BorderRadius.circular(20),
                ),
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Icon(
                      isAppInitialized ? Icons.check_circle : Icons.warning_amber_rounded,
                      color: Colors.white,
                      size: 16,
                    ),
                    const SizedBox(width: 4),
                    Text(
                      initializationStatus,
                      style: const TextStyle(
                        color: Colors.white,
                        fontSize: 12,
                        fontWeight: FontWeight.w500,
                      ),
                    ),
                  ],
                ),
              ),
            ),
        ],
      ),
          );
    }
}