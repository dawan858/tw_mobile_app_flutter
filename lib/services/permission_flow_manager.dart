import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:geolocator/geolocator.dart';
import 'device_admin_manager.dart';
import 'dart:async';

// Permission step class
class PermissionStep {
  final String name;
  final String description;
  final Future<bool> Function() checkPermission;
  final Future<bool> Function() requestPermission;
  final bool isRequired;

  PermissionStep({
    required this.name,
    required this.description,
    required this.checkPermission,
    required this.requestPermission,
    this.isRequired = true,
  });
}

class PermissionFlowManager {
  static final PermissionFlowManager _instance = PermissionFlowManager._internal();
  factory PermissionFlowManager() => _instance;
  PermissionFlowManager._internal();

  // Permission flow states
  bool _isFlowInProgress = false;
  int _currentStep = 0;
  final List<PermissionStep> _permissionSteps = [];
  
  // Callbacks
  Function()? onAllPermissionsGranted;
  Function(String)? onPermissionDenied;
  Function()? onFlowCompleted;

  // Initialize permission steps
  void initializeSteps() {
    _permissionSteps.clear();
    
    // Step 1: Phone State Permission (for IMEI)
    _permissionSteps.add(PermissionStep(
      name: 'Phone State',
      description: 'Required to get device IMEI for tracking identification',
      checkPermission: () async => await Permission.phone.isGranted,
      requestPermission: () async {
        final status = await Permission.phone.request();
        return status.isGranted;
      },
    ));

    // Step 2: Location Services
    _permissionSteps.add(PermissionStep(
      name: 'Location Services',
      description: 'Required for GPS tracking functionality',
      checkPermission: () async => await Geolocator.isLocationServiceEnabled(),
      requestPermission: () async {
        // Show dialog to enable location services
        return await Geolocator.isLocationServiceEnabled();
      },
    ));

    // Step 3: Location Permission
    _permissionSteps.add(PermissionStep(
      name: 'Location',
      description: 'Required for GPS tracking and location updates',
      checkPermission: () async {
        final permission = await Geolocator.checkPermission();
        return permission == LocationPermission.whileInUse || 
               permission == LocationPermission.always;
      },
      requestPermission: () async {
        final permission = await Geolocator.requestPermission();
        return permission == LocationPermission.whileInUse || 
               permission == LocationPermission.always;
      },
    ));

    // Step 4: Background Location Permission
    _permissionSteps.add(PermissionStep(
      name: 'Background Location',
      description: 'Required for continuous tracking when app is in background',
      checkPermission: () async => await Permission.locationAlways.isGranted,
      requestPermission: () async {
        final status = await Permission.locationAlways.request();
        return status.isGranted;
      },
    ));

    // Step 5: Notification Permission
    _permissionSteps.add(PermissionStep(
      name: 'Notifications',
      description: 'Required for service notifications and alerts',
      checkPermission: () async => await Permission.notification.isGranted,
      requestPermission: () async {
        final status = await Permission.notification.request();
        return status.isGranted;
      },
      isRequired: false, // Not critical for core functionality
    ));

    // Step 6: Storage Permission
    _permissionSteps.add(PermissionStep(
      name: 'Storage',
      description: 'Required for storing tracking data locally',
      checkPermission: () async => await Permission.storage.isGranted,
      requestPermission: () async {
        final status = await Permission.storage.request();
        return status.isGranted;
      },
      isRequired: false, // Not critical for core functionality
    ));

    // Step 7: Device Admin (LAST STEP)
    _permissionSteps.add(PermissionStep(
      name: 'Device Administrator',
      description: 'Required for security protection and preventing app removal',
      checkPermission: () async => await DeviceAdminManager.isDeviceAdminActive(),
      requestPermission: () async {
        await DeviceAdminManager.requestDeviceAdmin();
        // Wait longer for user to respond and system to update
        await Future.delayed(const Duration(seconds: 5));
        // Check multiple times with delays to ensure status is updated
        for (int i = 0; i < 3; i++) {
          final isActive = await DeviceAdminManager.isDeviceAdminActive();
          if (isActive) {
            return true;
          }
          await Future.delayed(const Duration(seconds: 2));
        }
        return await DeviceAdminManager.isDeviceAdminActive();
      },
    ));
  }

  // Start the permission flow
  Future<bool> startPermissionFlow(BuildContext context) async {
    if (_isFlowInProgress) {
      print('Permission flow already in progress');
      return false;
    }

    _isFlowInProgress = true;
    _currentStep = 0;
    
    print('=== STARTING PERMISSION FLOW ===');
    
    // Initialize steps
    initializeSteps();
    
    // Show progress dialog
    bool? dialogResult;
    showDialog(
      context: context,
      barrierDismissible: false,
      builder: (BuildContext context) {
        return StatefulBuilder(
          builder: (context, setState) {
            return AlertDialog(
              title: const Text('Setting Up Permissions'),
              content: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text('Processing: ${getCurrentStepName()}'),
                  const SizedBox(height: 16),
                  LinearProgressIndicator(
                    value: getProgress(),
                    backgroundColor: Colors.grey[300],
                    valueColor: const AlwaysStoppedAnimation<Color>(Colors.blue),
                  ),
                  const SizedBox(height: 8),
                  Text(
                    'Step ${_currentStep + 1} of ${_permissionSteps.length}',
                    style: const TextStyle(fontSize: 12, color: Colors.grey),
                  ),
                ],
              ),
            );
          },
        );
      },
    ).then((result) {
      dialogResult = result;
    });
    
    // Process each permission step
    for (int i = 0; i < _permissionSteps.length; i++) {
      _currentStep = i;
      final step = _permissionSteps[i];
      
      print('Processing step ${i + 1}/${_permissionSteps.length}: ${step.name}');
      
      // Check if permission is already granted
      final isGranted = await step.checkPermission();
      if (isGranted) {
        print('✅ ${step.name} permission already granted');
        continue;
      }
      
      // Request permission
      print('Requesting ${step.name} permission...');
      final granted = await _requestPermissionWithDialog(context, step);
      
      if (!granted && step.isRequired) {
        print('❌ Required permission ${step.name} was denied');
        _isFlowInProgress = false;
        Navigator.of(context).pop(); // Close progress dialog
        onPermissionDenied?.call(step.name);
        return false;
      } else if (!granted) {
        print('⚠️ Optional permission ${step.name} was denied - continuing');
      }
    }
    
    // Close progress dialog
    Navigator.of(context).pop();
    
    print('✅ All permissions processed successfully');
    _isFlowInProgress = false;
    onAllPermissionsGranted?.call();
    onFlowCompleted?.call();
    return true;
  }

  // Request permission with user-friendly dialog
  Future<bool> _requestPermissionWithDialog(BuildContext context, PermissionStep step) async {
    // Show explanation dialog first
    final shouldProceed = await _showPermissionExplanationDialog(context, step);
    if (!shouldProceed) {
      return false;
    }
    
    // Request the permission
    return await step.requestPermission();
  }

  // Show permission explanation dialog
  Future<bool> _showPermissionExplanationDialog(BuildContext context, PermissionStep step) async {
    return await showDialog<bool>(
      context: context,
      barrierDismissible: false,
      builder: (BuildContext context) {
        return AlertDialog(
          title: Text('${step.name} Permission Required'),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(step.description),
              const SizedBox(height: 16),
              Text(
                'Step ${_currentStep + 1} of ${_permissionSteps.length}',
                style: const TextStyle(
                  fontSize: 12,
                  color: Colors.grey,
                ),
              ),
            ],
          ),
          actions: <Widget>[
            if (!step.isRequired)
              TextButton(
                child: const Text('Skip'),
                onPressed: () => Navigator.of(context).pop(false),
              ),
            TextButton(
              child: Text(step.isRequired ? 'Grant Permission' : 'Continue'),
              onPressed: () => Navigator.of(context).pop(true),
            ),
          ],
        );
      },
    ) ?? false;
  }

  // Check if all critical permissions are granted
  Future<bool> checkCriticalPermissions() async {
    initializeSteps();
    
    for (final step in _permissionSteps) {
      if (step.isRequired) {
        final isGranted = await step.checkPermission();
        if (!isGranted) {
          print('❌ Critical permission ${step.name} not granted');
          return false;
        }
      }
    }
    
    print('✅ All critical permissions granted');
    return true;
  }

  // Get current flow progress
  double getProgress() {
    if (_permissionSteps.isEmpty) return 0.0;
    return (_currentStep + 1) / _permissionSteps.length;
  }

  // Get current step name
  String getCurrentStepName() {
    if (_currentStep < _permissionSteps.length) {
      return _permissionSteps[_currentStep].name;
    }
    return 'Completed';
  }

  // Check if flow is in progress
  bool get isFlowInProgress => _isFlowInProgress;

  // Reset flow state
  void reset() {
    _isFlowInProgress = false;
    _currentStep = 0;
    _permissionSteps.clear();
  }
} 