import 'dart:async';
import 'package:flutter/services.dart';
import 'package:geolocator/geolocator.dart';
import 'package:intl/intl.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:device_info_plus/device_info_plus.dart';
import 'package:package_info_plus/package_info_plus.dart';

class LiveDataService {
  static final LiveDataService _instance = LiveDataService._internal();
  factory LiveDataService() => _instance;
  LiveDataService._internal();

  static const MethodChannel serviceChannel = MethodChannel('com.example.twtracking/service');
  static const MethodChannel satelliteChannel = MethodChannel('com.trackingWorld.tracking/satellite');
  static const MethodChannel deviceInfoChannel = MethodChannel('com.trackingWorld.tracking/device_info');

  Timer? _updateTimer;
  final StreamController<Map<String, String>> _dataController = StreamController<Map<String, String>>.broadcast();
  
  Stream<Map<String, String>> get dataStream => _dataController.stream;

  // Current tracking data
  Position? _currentPosition;
  int _totalSatellites = 0;
  int _connectedSatellites = 0;
  bool _isTracking = false;
  String _deviceRDT = '';
  String _gmtSettings = '';
  String _imei = '';
  String _appVersion = '';

  // Initialize the service
  Future<void> initialize() async {
    await _loadAppVersion();
    await _loadImei();
    await _getSatelliteData();
    await _getCurrentPosition();
    await _updateTrackingStatus();
    
    // Start periodic updates
    _startPeriodicUpdates();
  }

  // Start periodic data updates
  void _startPeriodicUpdates() {
    _updateTimer?.cancel();
    _updateTimer = Timer.periodic(const Duration(seconds: 2), (timer) async {
      await _updateAllData();
    });
  }

  // Update all tracking data
  Future<void> _updateAllData() async {
    try {
      await _getSatelliteData();
      await _getCurrentPosition();
      await _updateTrackingStatus();
      await _updateTimestamps();
      
      // Emit updated data
      _dataController.add(_getCurrentTrackingData());
    } catch (e) {
      print('Error updating live data: $e');
    }
  }

  // Get current tracking data as a map
  Map<String, String> _getCurrentTrackingData() {
    final now = DateTime.now();
    
    // Determine status based on BackgroundService and GPS availability
    String status;
    if (!_isTracking) {
      status = 'BackgroundService Stopped';
    } else if (_currentPosition == null) {
      status = 'Searching GPS Signal';
    } else if (_connectedSatellites < 3) {
      status = 'Poor GPS Signal';
    } else if (_currentPosition!.accuracy > 50) {
      status = 'Low GPS Accuracy';
    } else {
      status = 'Location Found';
    }
    
    return {
      'totalSatellites': _totalSatellites.toString(),
      'connectedSatellites': _connectedSatellites.toString(),
      'status': status,
      'latitude': _currentPosition?.latitude.toStringAsFixed(6) ?? '0.000000',
      'longitude': _currentPosition?.longitude.toStringAsFixed(6) ?? '0.000000',
      'altitude': _currentPosition?.altitude.toStringAsFixed(3) ?? '0.000',
      'angle': _currentPosition?.heading.toStringAsFixed(3) ?? '0.000',
      'speed': _currentPosition?.speed.toStringAsFixed(3) ?? '0.000',
      'accuracy': _currentPosition?.accuracy.toStringAsFixed(3) ?? '0.000',
      'lastPollTime': _deviceRDT,
      'localTime': DateFormat('yyyy-MM-dd HH:mm:ss').format(now),
      'gmt': _gmtSettings,
      'pendingData': '0', // TODO: Get from sync service
      'server': 'Connected', // TODO: Check network status
      'refreshTime': DateFormat('yyyy-MM-dd HH:mm:ss').format(now),
    };
  }

  // Get satellite data from native service
  Future<void> _getSatelliteData() async {
    try {
      print('🛰️ Requesting satellite data from native service...');
      final result = await satelliteChannel.invokeMethod('getSatelliteData');
      if (result != null) {
        _totalSatellites = result['totalSatellites'] ?? 0;
        _connectedSatellites = result['connectedSatellites'] ?? 0;
        
        print('🛰️ Satellite data received: $_connectedSatellites/$_totalSatellites');
        
        // If satellite data is 0, try to refresh it
        if (_totalSatellites == 0 && _connectedSatellites == 0) {
          print('⚠️ Satellite data is 0, attempting refresh...');
          await _refreshSatelliteData();
        }
      } else {
        print('⚠️ Satellite data result is null');
      }
    } catch (e) {
      print('❌ Error getting satellite data: $e');
    }
  }

  // Force refresh satellite data
  Future<void> _refreshSatelliteData() async {
    try {
      final result = await satelliteChannel.invokeMethod('refreshSatelliteData');
      if (result != null) {
        _totalSatellites = result['totalSatellites'] ?? 0;
        _connectedSatellites = result['connectedSatellites'] ?? 0;
        print('✅ Satellite data refreshed: $_connectedSatellites/$_totalSatellites');
      }
    } catch (e) {
      print('Error refreshing satellite data: $e');
    }
  }

  // Get current position
  Future<void> _getCurrentPosition() async {
    try {
      final position = await Geolocator.getCurrentPosition(
        desiredAccuracy: LocationAccuracy.bestForNavigation,
      );
      _currentPosition = position;
    } catch (e) {
      print('Error getting current position: $e');
    }
  }

  // Update tracking status from BackgroundService
  Future<void> _updateTrackingStatus() async {
    try {
      // Check if BackgroundService is running
      final isRunning = await serviceChannel.invokeMethod('isBackgroundServiceRunning');
      _isTracking = isRunning ?? false;
      
      if (_isTracking) {
        print('✅ BackgroundService is running - tracking active');
      } else {
        print('❌ BackgroundService is not running - tracking stopped');
      }
    } catch (e) {
      print('Error checking BackgroundService status: $e');
      // Fallback to old method if new method doesn't exist
      try {
        final isRunning = await serviceChannel.invokeMethod('isServiceRunning');
        _isTracking = isRunning ?? false;
        print('⚠️ Using fallback service check: $_isTracking');
      } catch (fallbackError) {
        print('Error checking service status (fallback): $fallbackError');
        _isTracking = false;
      }
    }
  }

  // Update timestamps
  Future<void> _updateTimestamps() async {
    final now = DateTime.now();
    _deviceRDT = DateFormat("dd/MM/yyyy HH:mm:ss.SSS").format(now);
    _gmtSettings = "GMT+${now.timeZoneOffset.inHours}:00 ${now.year}";
  }

  // Load app version
  Future<void> _loadAppVersion() async {
    try {
      final info = await PackageInfo.fromPlatform();
      _appVersion = 'v${info.version}';
    } catch (e) {
      print('Error loading app version: $e');
      _appVersion = 'v1.0.0';
    }
  }

  // Load IMEI
  Future<void> _loadImei() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      _imei = prefs.getString('imei') ?? '';
      
      if (_imei.isEmpty) {
        final imei = await deviceInfoChannel.invokeMethod('getImei');
        if (imei != null && imei.isNotEmpty && imei != 'unknown') {
          _imei = imei;
          await prefs.setString('imei', imei);
        }
      }
    } catch (e) {
      print('Error loading IMEI: $e');
    }
  }

  // Get current data snapshot
  Map<String, String> getCurrentData() {
    return _getCurrentTrackingData();
  }

  // Force refresh data
  Future<void> refreshData() async {
    await _updateAllData();
  }

  // Force refresh satellite data specifically
  Future<void> refreshSatelliteData() async {
    print('🛰️ Force refreshing satellite data...');
    await _refreshSatelliteData();
    
    // If still 0, try again after a delay
    if (_totalSatellites == 0 && _connectedSatellites == 0) {
      print('🛰️ Satellite data still 0, trying again after delay...');
      await Future.delayed(const Duration(seconds: 2));
      await _refreshSatelliteData();
    }
  }

  // Dispose resources
  void dispose() {
    _updateTimer?.cancel();
    _dataController.close();
  }
} 