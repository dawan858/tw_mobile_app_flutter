import 'package:flutter/material.dart';
import 'dart:async';
import 'package:tracking_world/configuration_screen.dart';
import 'live_status_screen.dart';
import 'pending_data_screen.dart';
import 'exception_logs_screen.dart';
import 'ignition_logs_screen.dart';
import 'package:package_info_plus/package_info_plus.dart';
import 'services/sync_service.dart';
import 'services/log_upload_service.dart';

import 'services/database_helper.dart';

class SettingsScreen extends StatefulWidget {
  const SettingsScreen({Key? key}) : super(key: key);

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  String _appVersion = '';
  final SyncService _syncService = SyncService();
  bool _isBackendDown = false;
  bool _isSyncing = false;
  Timer? _statusTimer;

  @override
  void initState() {
    super.initState();
    _loadAppVersion();
    _loadBackendStatus();
    _startStatusMonitoring();
  }

  // NEW: Log Upload Debug Methods
  Future<void> _testLogUploadConnection() async {
    try {
      final logUploadService = LogUploadService.instance;
      final isConnected = await logUploadService.testConnection();
      
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(isConnected ? '✅ Connection successful!' : '❌ Connection failed'),
          backgroundColor: isConnected ? Colors.green : Colors.red,
          duration: Duration(seconds: 3),
        ),
      );
    } catch (e) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('❌ Test failed: $e'),
          backgroundColor: Colors.red,
        ),
      );
    }
  }

  Future<void> _showLogUploadStatus() async {
    try {
      final logUploadService = LogUploadService.instance;
      final status = logUploadService.getStatus();
      
      showDialog(
        context: context,
        builder: (context) => AlertDialog(
          title: Text('📊 Log Upload Status'),
          content: SingleChildScrollView(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Text('🔗 Endpoint: ${status['endpoint']}'),
                Text('📱 Device IMEI: ${status['deviceImei']}'),
                Text('⏳ Is Uploading: ${status['isUploading']}'),
                Text('📦 Queue Size: ${status['queueSize']}'),
                Text('⏰ Upload Interval: ${status['uploadInterval']}s'),
                Text('🔄 Last Exception Log: ${status['lastExceptionLog'] ?? 'None'}'),
                Text('🚗 Last Ignition Log: ${status['lastIgnitionLog'] ?? 'None'}'),
              ],
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(context).pop(),
              child: Text('Close'),
            ),
          ],
        ),
      );
    } catch (e) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('❌ Error getting status: $e'),
          backgroundColor: Colors.red,
        ),
      );
    }
  }

  Future<void> _forceLogUpload() async {
    try {
      final logUploadService = LogUploadService.instance;
      await logUploadService.forceUpload();
      
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('🔄 Force upload triggered'),
          backgroundColor: Colors.blue,
          duration: Duration(seconds: 2),
        ),
      );
    } catch (e) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('❌ Force upload failed: $e'),
          backgroundColor: Colors.red,
        ),
      );
    }
  }

  @override
  void dispose() {
    _statusTimer?.cancel();
    super.dispose();
  }

  Future<void> _loadAppVersion() async {
    final info = await PackageInfo.fromPlatform();
    setState(() {
      _appVersion = 'v${info.version}';
    });
  }

  Future<void> _loadBackendStatus() async {
    try {
      print('🔄 Loading backend status...');
      
      // Check server status to ensure accurate detection
      final serverHealthy = await _syncService.checkServerStatus();
      
      final stats = await _syncService.getSyncStats();
      final newIsBackendDown = stats['isServerDown'] ?? false;
      final newIsSyncing = stats['isSyncing'] ?? false;
      final isMonitoring = stats['isMonitoringServer'] ?? false;
      
      print('Server health check result: $serverHealthy');
      print('Stats - isServerDown: $newIsBackendDown, isSyncing: $newIsSyncing, isMonitoring: $isMonitoring');
      
      setState(() {
        _isBackendDown = newIsBackendDown;
        _isSyncing = newIsSyncing;
      });
      
      print('✅ Backend status updated - isDown: $_isBackendDown');
    } catch (e) {
      print('❌ Error loading backend status: $e');
    }
  }

  void _startStatusMonitoring() {
    // Update status every 30 seconds
    _statusTimer = Timer.periodic(const Duration(seconds: 30), (timer) {
      _loadBackendStatus();
    });
  }

  Future<void> _manualSyncBackend() async {
    if (_isSyncing) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('Sync already in progress...'),
          backgroundColor: Colors.orange,
        ),
      );
      return;
    }

    setState(() {
      _isSyncing = true;
    });

    try {
      // Show loading dialog
      showDialog(
        context: context,
        barrierDismissible: false,
        builder: (BuildContext context) {
          return AlertDialog(
            title: const Text('Syncing Backend'),
            content: const Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                CircularProgressIndicator(),
                SizedBox(height: 16),
                Text('Attempting to sync with backend...'),
              ],
            ),
          );
        },
      );

      // Force sync with debug
      await _syncService.forceSyncWithDebug();
      
      // Reload status
      await _loadBackendStatus();

      // Close loading dialog
      Navigator.of(context).pop();

      // Show result
      if (!_isBackendDown) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('✅ Backend sync successful!'),
            backgroundColor: Colors.green,
          ),
        );
              } else {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(
              content: Text('⚠️ Backend still down. Retry will be attempted automatically.'),
              backgroundColor: Colors.orange,
            ),
          );
        }
    } catch (e) {
      // Close loading dialog
      Navigator.of(context).pop();
      
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('❌ Sync failed: $e'),
          backgroundColor: Colors.red,
        ),
      );
    } finally {
      setState(() {
        _isSyncing = false;
      });
    }
  }

  Future<void> _resetPhaseSystem() async {
    try {
      await _syncService.resetPhaseSystem();
      await _loadBackendStatus();
      
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('✅ Phase system reset to normal mode'),
          backgroundColor: Colors.green,
        ),
      );
    } catch (e) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('❌ Reset failed: $e'),
          backgroundColor: Colors.red,
        ),
      );
    }
  }

  Future<void> _forceServerStatusCheck() async {
    try {
      print('🔄 Force server status check requested...');
      
      await _syncService.forceServerStatusCheck();
      await _loadBackendStatus();
      
      final message = _isBackendDown 
          ? '⚠️ Server is currently down'
          : '✅ Server is healthy';
      
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(message),
          backgroundColor: _isBackendDown ? Colors.orange : Colors.green,
        ),
      );
    } catch (e) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('❌ Status check failed: $e'),
          backgroundColor: Colors.red,
        ),
      );
    }
  }



  @override
  Widget build(BuildContext context) {
    final isTablet = MediaQuery.of(context).size.shortestSide >= 600;
    final double padding = isTablet ? 24.0 : 16.0;
    final double titleFontSize = isTablet ? 22.0 : 18.0;
    final double buttonFontSize = isTablet ? 18.0 : 16.0;
    final double buttonHeight = isTablet ? 48.0 : 44.0;
    final double borderRadius = isTablet ? 16.0 : 12.0;
    final double verticalSpacing = isTablet ? 24.0 : 16.0;
    final double logoSize = isTablet ? 60.0 : 40.0;

    // Placeholder exception logs for demonstration
    final List<Map<String, String>> exceptionLogs = [
      {
        'main': 'ClassName: com.example.twtracking.ServerUpdateForegroundService, MethodName: setUploadTimer, Message: no such table: TWPXLive_Configuration (code 1 SQLITE_ERROR): , while compiling: SELECT UploadTimer, MovingTimer, StopTimer, Distance, TravelStartTimer, TravelStopTimer FROM TWPXLive_Configuration LIMIT 1, Date: 2025-05-14 15:32:02',
        'details': '',
      },
      {
        'main': 'ClassName: android.os.Handler, MethodName: handleCallback, Message: Internet Not Available.',
        'details': 'Date: 2025-05-14 15:32:03',
      },
      {
        'main': 'ClassName: com.example.twtracking.ConfigurationUpdateForegroundService, MethodName: setConfigurationUpdateTimer, Message: no such table: TWPXLive_Configuration (code 1 SQLITE_ERROR): , while compiling: SELECT ConfigurationTimer FROM TWPXLive_Configuration LIMIT 1, Date: 2025-05-14 15:32:03',
        'details': '',
      },
    ];

    final List<Map<String, dynamic>> buttons = [
      {'label': 'STOP TRACKING', 'onTap': () {}},
      {'label': 'LIVE STATUS', 'onTap': (BuildContext ctx) {
        Navigator.of(ctx).push(
          MaterialPageRoute(
            builder: (_) => const LiveStatusScreen(),
          ),
        );
      }},
      {'label': 'CONFIGURATION', 'onTap': () {
        Navigator.of(context).push(
          MaterialPageRoute(
            builder: (_) => const ConfigurationScreen(),
          ),
        );
      }},
      {'label': 'PENDING DATA', 'onTap': (BuildContext ctx) {
        Navigator.of(ctx).push(
          MaterialPageRoute(
            builder: (_) => const PendingDataScreen(),
          ),
        );
      }},
      {'label': 'EXCEPTION LOGS', 'onTap': (BuildContext ctx) {
        Navigator.of(ctx).push(
          MaterialPageRoute(
            builder: (_) => const ExceptionLogsScreen(),
          ),
        );
      }},
      {'label': 'IGNITION LOGS', 'onTap': (BuildContext ctx) {
        Navigator.of(ctx).push(
          MaterialPageRoute(
            builder: (_) => const IgnitionLogsScreen(),
          ),
        );
      }},


      {'label': 'LOGOUT', 'onTap': () {}},
    ];

    return Scaffold(
      appBar: AppBar(
        leading: BackButton(color: Colors.black),
        backgroundColor: Colors.transparent,
        elevation: 0,
      ),
      body: SafeArea(
        child: Center(
          child: ConstrainedBox(
            constraints: BoxConstraints(maxWidth: 700),
            child: Padding(
              padding: EdgeInsets.symmetric(horizontal: padding),
              child: Column(
                children: [
                  Expanded(
                    child: SingleChildScrollView(
                      child: Column(
                        children: [
                          SizedBox(height: verticalSpacing),
                          Text(
                            "SETTINGS",
                            style: TextStyle(
                              fontSize: titleFontSize,
                              fontWeight: FontWeight.bold,
                              color: Colors.black,
                            ),
                          ),
                          SizedBox(height: verticalSpacing),
                          
                          // NEW: Backend Status Card (only show when backend is down)
                          if (_isBackendDown) ...[
                            Container(
                              width: double.infinity,
                              padding: EdgeInsets.all(16),
                              decoration: BoxDecoration(
                                color: Colors.orange.shade50,
                                borderRadius: BorderRadius.circular(borderRadius),
                                border: Border.all(color: Colors.orange.shade200),
                              ),
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Row(
                                    children: [
                                      Icon(Icons.warning, color: Colors.orange.shade700, size: 20),
                                      SizedBox(width: 8),
                                      Text(
                                        'Backend Status',
                                        style: TextStyle(
                                          fontSize: buttonFontSize,
                                          fontWeight: FontWeight.bold,
                                          color: Colors.orange.shade700,
                                        ),
                                      ),
                                    ],
                                  ),
                                  SizedBox(height: 8),
                                  Text(
                                    'Server is currently down',
                                    style: TextStyle(
                                      fontSize: buttonFontSize * 0.9,
                                      color: Colors.orange.shade600,
                                    ),
                                  ),
                                  SizedBox(height: 4),
                                  Row(
                                    children: [
                                      Icon(
                                        Icons.sync,
                                        color: Colors.green.shade600,
                                        size: 16,
                                      ),
                                      SizedBox(width: 4),
                                      Text(
                                        'Continuous monitoring active - checking every 30 seconds',
                                        style: TextStyle(
                                          fontSize: buttonFontSize * 0.8,
                                          color: Colors.green.shade600,
                                        ),
                                      ),
                                    ],
                                  ),
                                  SizedBox(height: 4),
                                  Text(
                                    'All pending data will be synced automatically when server comes back online',
                                    style: TextStyle(
                                      fontSize: buttonFontSize * 0.8,
                                      color: Colors.grey.shade600,
                                      fontStyle: FontStyle.italic,
                                    ),
                                  ),
                                ],
                              ),
                            ),
                            SizedBox(height: verticalSpacing),
                          ],
                          
                          ...buttons.map((btn) => Padding(
                            padding: EdgeInsets.only(bottom: verticalSpacing),
                            child: SizedBox(
                              width: double.infinity,
                              height: buttonHeight,
                              child: ElevatedButton(
                                style: ElevatedButton.styleFrom(
                                  backgroundColor: Color(0xFF3e4095),
                                  shape: RoundedRectangleBorder(
                                    borderRadius: BorderRadius.circular(buttonHeight / 2),
                                  ),
                                ),
                                onPressed: btn['label'] == 'LIVE STATUS' || btn['label'] == 'PENDING DATA' || btn['label'] == 'EXCEPTION LOGS' || btn['label'] == 'IGNITION LOGS'
                                    ? () => btn['onTap'](context)
                                    : btn['onTap'],
                                child: Text(
                                  btn['label'],
                                  style: TextStyle(fontSize: buttonFontSize, color: Colors.white),
                                ),
                              ),
                            ),
                          )),
                          
                          // NEW: Sync Backend Button (only show when backend is down)
                          if (_isBackendDown) ...[
                            SizedBox(height: verticalSpacing),
                            SizedBox(
                              width: double.infinity,
                              height: buttonHeight,
                              child: ElevatedButton.icon(
                                style: ElevatedButton.styleFrom(
                                  backgroundColor: Colors.orange.shade600,
                                  shape: RoundedRectangleBorder(
                                    borderRadius: BorderRadius.circular(buttonHeight / 2),
                                  ),
                                ),
                                onPressed: _isSyncing ? null : _manualSyncBackend,
                                icon: _isSyncing 
                                    ? SizedBox(
                                        width: 20,
                                        height: 20,
                                        child: CircularProgressIndicator(
                                          strokeWidth: 2,
                                          valueColor: AlwaysStoppedAnimation<Color>(Colors.white),
                                        ),
                                      )
                                    : Icon(Icons.sync, color: Colors.white, size: 20),
                                label: Text(
                                  _isSyncing ? 'SYNCING...' : 'SYNC BACKEND',
                                  style: TextStyle(fontSize: buttonFontSize, color: Colors.white),
                                ),
                              ),
                            ),
                            
                            // NEW: Reset Phase Button (admin only)
                            SizedBox(height: verticalSpacing / 2),
                            SizedBox(
                              width: double.infinity,
                              height: buttonHeight * 0.8,
                              child: OutlinedButton.icon(
                                style: OutlinedButton.styleFrom(
                                  side: BorderSide(color: Colors.grey.shade400),
                                  shape: RoundedRectangleBorder(
                                    borderRadius: BorderRadius.circular(buttonHeight / 2),
                                  ),
                                ),
                                onPressed: _resetPhaseSystem,
                                icon: Icon(Icons.refresh, color: Colors.grey.shade600, size: 16),
                                label: Text(
                                  'RESET PHASE SYSTEM',
                                  style: TextStyle(fontSize: buttonFontSize * 0.9, color: Colors.grey.shade600),
                                ),
                              ),
                            ),
                            
                            // NEW: Force Server Status Check Button
                            SizedBox(height: verticalSpacing / 2),
                            SizedBox(
                              width: double.infinity,
                              height: buttonHeight * 0.8,
                              child: OutlinedButton.icon(
                                style: OutlinedButton.styleFrom(
                                  side: BorderSide(color: Colors.blue.shade400),
                                  shape: RoundedRectangleBorder(
                                    borderRadius: BorderRadius.circular(buttonHeight / 2),
                                  ),
                                ),
                                onPressed: _forceServerStatusCheck,
                                icon: Icon(Icons.check_circle, color: Colors.blue.shade600, size: 16),
                                label: Text(
                                  'CHECK SERVER STATUS',
                                  style: TextStyle(fontSize: buttonFontSize * 0.9, color: Colors.blue.shade600),
                                ),
                              ),
                            ),
                          ],
                          
                          // NEW: Debug Section for Log Upload (always visible)
                          SizedBox(height: verticalSpacing),
                          Container(
                            padding: EdgeInsets.all(16),
                            decoration: BoxDecoration(
                              color: Colors.grey.shade100,
                              borderRadius: BorderRadius.circular(12),
                              border: Border.all(color: Colors.grey.shade300),
                            ),
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(
                                  '🔧 LOG UPLOAD DEBUG',
                                  style: TextStyle(
                                    fontSize: buttonFontSize * 0.9,
                                    fontWeight: FontWeight.bold,
                                    color: Colors.grey.shade700,
                                  ),
                                ),
                                SizedBox(height: 8),
                                SizedBox(
                                  width: double.infinity,
                                  height: buttonHeight * 0.7,
                                  child: ElevatedButton.icon(
                                    style: ElevatedButton.styleFrom(
                                      backgroundColor: Colors.purple.shade600,
                                      shape: RoundedRectangleBorder(
                                        borderRadius: BorderRadius.circular(buttonHeight / 2),
                                      ),
                                    ),
                                    onPressed: _testLogUploadConnection,
                                    icon: Icon(Icons.wifi_tethering, color: Colors.white, size: 16),
                                    label: Text(
                                      'TEST CONNECTION',
                                      style: TextStyle(fontSize: buttonFontSize * 0.8, color: Colors.white),
                                    ),
                                  ),
                                ),
                                SizedBox(height: 8),
                                SizedBox(
                                  width: double.infinity,
                                  height: buttonHeight * 0.7,
                                  child: ElevatedButton.icon(
                                    style: ElevatedButton.styleFrom(
                                      backgroundColor: Colors.teal.shade600,
                                      shape: RoundedRectangleBorder(
                                        borderRadius: BorderRadius.circular(buttonHeight / 2),
                                      ),
                                    ),
                                    onPressed: _showLogUploadStatus,
                                    icon: Icon(Icons.info, color: Colors.white, size: 16),
                                    label: Text(
                                      'SHOW STATUS',
                                      style: TextStyle(fontSize: buttonFontSize * 0.8, color: Colors.white),
                                    ),
                                  ),
                                ),
                                SizedBox(height: 8),
                                SizedBox(
                                  width: double.infinity,
                                  height: buttonHeight * 0.7,
                                  child: ElevatedButton.icon(
                                    style: ElevatedButton.styleFrom(
                                      backgroundColor: Colors.indigo.shade600,
                                      shape: RoundedRectangleBorder(
                                        borderRadius: BorderRadius.circular(buttonHeight / 2),
                                      ),
                                    ),
                                    onPressed: _forceLogUpload,
                                    icon: Icon(Icons.upload, color: Colors.white, size: 16),
                                    label: Text(
                                      'FORCE UPLOAD',
                                      style: TextStyle(fontSize: buttonFontSize * 0.8, color: Colors.white),
                                    ),
                                  ),
                                ),
                              ],
                            ),
                          ),
                          

                        ],
                      ),
                    ),
                  ),
                  Row(
                    children: [
                      Expanded(
                        child: Align(
                          alignment: Alignment.bottomLeft,
                          child: Text(
                            _appVersion,
                            style: TextStyle(
                              color: Color(0xFF3e4095),
                              fontSize: buttonFontSize * 0.8,
                            ),
                          ),
                        ),
                      ),
                      Expanded(
                        child: Align(
                          alignment: Alignment.bottomRight,
                          child: Image.asset(
                            'assets/tw.png',
                            width: logoSize,
                            height: logoSize,
                            fit: BoxFit.contain,
                          ),
                        ),
                      ),
                    ],
                  ),
                  SizedBox(height: 16),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
} 