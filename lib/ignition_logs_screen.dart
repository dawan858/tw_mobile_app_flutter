import 'package:flutter/material.dart';
import 'package:package_info_plus/package_info_plus.dart';
import 'services/database_helper.dart';

class IgnitionLogsScreen extends StatefulWidget {
  const IgnitionLogsScreen({Key? key}) : super(key: key);

  @override
  State<IgnitionLogsScreen> createState() => _IgnitionLogsScreenState();
}

class _IgnitionLogsScreenState extends State<IgnitionLogsScreen> {
  final DatabaseHelper _dbHelper = DatabaseHelper();
  List<Map<String, dynamic>> _ignitionLogs = [];
  bool _isLoading = true;
  String _appVersion = '';

  @override
  void initState() {
    super.initState();
    _loadAppVersion();
    _loadIgnitionLogs();
  }

  Future<void> _loadAppVersion() async {
    final info = await PackageInfo.fromPlatform();
    setState(() {
      _appVersion = 'v${info.version}';
    });
  }

  Future<void> _loadIgnitionLogs() async {
    try {
      setState(() {
        _isLoading = true;
      });

      // Get ignition logs from database
      final logs = await _dbHelper.getIgnitionLogs();
      
      setState(() {
        _ignitionLogs = logs;
        _isLoading = false;
      });
    } catch (e) {
      print('Error loading ignition logs: $e');
      setState(() {
        _isLoading = false;
      });
    }
  }

  Future<void> _clearIgnitionLogs() async {
    try {
      await _dbHelper.clearIgnitionLogs();
      await _loadIgnitionLogs();
      
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Ignition logs cleared successfully'),
            backgroundColor: Colors.green,
          ),
        );
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('Error clearing logs: $e'),
            backgroundColor: Colors.red,
          ),
        );
      }
    }
  }

  Future<void> _refreshLogs() async {
    await _loadIgnitionLogs();
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('Logs refreshed'),
          backgroundColor: Colors.blue,
        ),
      );
    }
  }

  String _formatTimestamp(String timestamp) {
    try {
      final dateTime = DateTime.parse(timestamp);
      return '${dateTime.day.toString().padLeft(2, '0')}/${dateTime.month.toString().padLeft(2, '0')}/${dateTime.year} ${dateTime.hour.toString().padLeft(2, '0')}:${dateTime.minute.toString().padLeft(2, '0')}:${dateTime.second.toString().padLeft(2, '0')}';
    } catch (e) {
      return timestamp;
    }
  }

  Color _getLogColor(String logType) {
    switch (logType.toLowerCase()) {
      case 'power_state_on':
      case 'acc_on':
      case 'ignition_on':
        return Colors.green;
      case 'power_state_off':
      case 'acc_off':
      case 'ignition_off':
        return Colors.red;
      case 'monitoring':
      case 'continuous':
        return Colors.blue;
      default:
        return Colors.grey;
    }
  }

  Icon _getLogIcon(String logType) {
    switch (logType.toLowerCase()) {
      case 'power_state_on':
      case 'acc_on':
      case 'ignition_on':
        return Icon(Icons.power, color: Colors.green, size: 16);
      case 'power_state_off':
      case 'acc_off':
      case 'ignition_off':
        return Icon(Icons.power_off, color: Colors.red, size: 16);
      case 'monitoring':
      case 'continuous':
        return Icon(Icons.monitor_heart, color: Colors.blue, size: 16);
      default:
        return Icon(Icons.info, color: Colors.grey, size: 16);
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

    return Scaffold(
      appBar: AppBar(
        leading: BackButton(color: Colors.black),
        backgroundColor: Colors.transparent,
        elevation: 0,
        title: Text(
          'IGNITION LOGS',
          style: TextStyle(
            fontSize: titleFontSize,
            fontWeight: FontWeight.bold,
            color: Colors.black,
          ),
        ),
        actions: [
          IconButton(
            icon: Icon(Icons.refresh, color: Colors.black),
            onPressed: _refreshLogs,
          ),
          IconButton(
            icon: Icon(Icons.clear_all, color: Colors.red),
            onPressed: () {
              showDialog(
                context: context,
                builder: (BuildContext context) {
                  return AlertDialog(
                    title: Text('Clear Logs'),
                    content: Text('Are you sure you want to clear all ignition logs?'),
                    actions: [
                      TextButton(
                        onPressed: () => Navigator.of(context).pop(),
                        child: Text('Cancel'),
                      ),
                      TextButton(
                        onPressed: () {
                          Navigator.of(context).pop();
                          _clearIgnitionLogs();
                        },
                        child: Text('Clear', style: TextStyle(color: Colors.red)),
                      ),
                    ],
                  );
                },
              );
            },
          ),
        ],
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
                    child: _isLoading
                        ? Center(
                            child: Column(
                              mainAxisAlignment: MainAxisAlignment.center,
                              children: [
                                CircularProgressIndicator(),
                                SizedBox(height: 16),
                                Text('Loading ignition logs...'),
                              ],
                            ),
                          )
                        : _ignitionLogs.isEmpty
                            ? Center(
                                child: Column(
                                  mainAxisAlignment: MainAxisAlignment.center,
                                  children: [
                                    Icon(
                                      Icons.power_off,
                                      size: 64,
                                      color: Colors.grey.shade400,
                                    ),
                                    SizedBox(height: 16),
                                    Text(
                                      'No ignition logs found',
                                      style: TextStyle(
                                        fontSize: buttonFontSize,
                                        color: Colors.grey.shade600,
                                      ),
                                    ),
                                    SizedBox(height: 8),
                                    Text(
                                      'Ignition state changes will appear here',
                                      style: TextStyle(
                                        fontSize: buttonFontSize * 0.9,
                                        color: Colors.grey.shade500,
                                      ),
                                    ),
                                  ],
                                ),
                              )
                            : RefreshIndicator(
                                onRefresh: _refreshLogs,
                                child: ListView.builder(
                                  itemCount: _ignitionLogs.length,
                                  itemBuilder: (context, index) {
                                    final log = _ignitionLogs[index];
                                    final timestamp = log['timestamp'] ?? '';
                                    final message = log['message'] ?? '';
                                    final logType = log['log_type'] ?? 'info';
                                    final details = log['details'] ?? '';

                                    return Container(
                                      margin: EdgeInsets.only(bottom: 12),
                                      decoration: BoxDecoration(
                                        color: Colors.white,
                                        borderRadius: BorderRadius.circular(borderRadius),
                                        border: Border.all(
                                          color: _getLogColor(logType).withOpacity(0.3),
                                        ),
                                        boxShadow: [
                                          BoxShadow(
                                            color: Colors.black.withOpacity(0.05),
                                            blurRadius: 4,
                                            offset: Offset(0, 2),
                                          ),
                                        ],
                                      ),
                                      child: Padding(
                                        padding: EdgeInsets.all(16),
                                        child: Column(
                                          crossAxisAlignment: CrossAxisAlignment.start,
                                          children: [
                                            Row(
                                              children: [
                                                _getLogIcon(logType),
                                                SizedBox(width: 8),
                                                Expanded(
                                                  child: Text(
                                                    message,
                                                    style: TextStyle(
                                                      fontSize: buttonFontSize * 0.9,
                                                      fontWeight: FontWeight.w600,
                                                      color: _getLogColor(logType),
                                                    ),
                                                  ),
                                                ),
                                              ],
                                            ),
                                            if (details.isNotEmpty) ...[
                                              SizedBox(height: 8),
                                              Text(
                                                details,
                                                style: TextStyle(
                                                  fontSize: buttonFontSize * 0.8,
                                                  color: Colors.grey.shade600,
                                                ),
                                              ),
                                            ],
                                            SizedBox(height: 8),
                                            Row(
                                              children: [
                                                Icon(
                                                  Icons.access_time,
                                                  size: 14,
                                                  color: Colors.grey.shade500,
                                                ),
                                                SizedBox(width: 4),
                                                Text(
                                                  _formatTimestamp(timestamp),
                                                  style: TextStyle(
                                                    fontSize: buttonFontSize * 0.75,
                                                    color: Colors.grey.shade500,
                                                  ),
                                                ),
                                                Spacer(),
                                                Container(
                                                  padding: EdgeInsets.symmetric(
                                                    horizontal: 8,
                                                    vertical: 2,
                                                  ),
                                                  decoration: BoxDecoration(
                                                    color: _getLogColor(logType).withOpacity(0.1),
                                                    borderRadius: BorderRadius.circular(12),
                                                  ),
                                                  child: Text(
                                                    logType.toUpperCase(),
                                                    style: TextStyle(
                                                      fontSize: buttonFontSize * 0.7,
                                                      color: _getLogColor(logType),
                                                      fontWeight: FontWeight.w500,
                                                    ),
                                                  ),
                                                ),
                                              ],
                                            ),
                                          ],
                                        ),
                                      ),
                                    );
                                  },
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