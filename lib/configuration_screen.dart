import 'package:flutter/material.dart';
import 'package:package_info_plus/package_info_plus.dart';
import 'services/config_service.dart';

class ConfigurationScreen extends StatefulWidget {
  const ConfigurationScreen({Key? key}) : super(key: key);

  @override
  State<ConfigurationScreen> createState() => _ConfigurationScreenState();
}

class _ConfigurationScreenState extends State<ConfigurationScreen> {
  String _appVersion = '';
  final ConfigService _configService = ConfigService();
  final Map<String, TextEditingController> controllers = {};
  bool _isLoading = true;

  final List<Map<String, dynamic>> fields = [
    {'key': 'gpsTimer', 'label': 'GPS TIMER', 'icon': Icons.my_location},
    {'key': 'configTimer', 'label': 'CONFIGURATION TIMER', 'icon': Icons.settings},
    {'key': 'uploadTimer', 'label': 'UPLOAD TIMER', 'icon': Icons.upload},
    {'key': 'retryCounter', 'label': 'RETRY COUNTER', 'icon': Icons.format_list_numbered},
    {'key': 'angleThreshold', 'label': 'ANGLE THRESHOLD', 'icon': Icons.north_east},
    {'key': 'overSpeedingThreshold', 'label': 'OVER-SPEEDING THRESHOLD', 'icon': Icons.speed},
    {'key': 'travelStartTimer', 'label': 'TRAVEL START TIMER', 'icon': Icons.subdirectory_arrow_right},
    {'key': 'travelStopTimer', 'label': 'TRAVEL STOP TIMER', 'icon': Icons.stop},
    {'key': 'movingTimer', 'label': 'MOVING TIMER', 'icon': Icons.show_chart},
    {'key': 'stopTimer', 'label': 'STOP TIMER', 'icon': Icons.block},
    {'key': 'distanceThreshold', 'label': 'DISTANCE THRESHOLD', 'icon': Icons.import_export},
    {'key': 'heartbeatTimer', 'label': 'HEARTBEAT TIMER', 'icon': Icons.sync},
    {'key': 'liveStatusUpdateTimer', 'label': 'LIVE STATUS UPDATE TIMER', 'icon': Icons.refresh},
  ];

  @override
  void initState() {
    super.initState();
    _loadAppVersion();
    _loadConfiguration();
  }

  Future<void> _loadAppVersion() async {
    final info = await PackageInfo.fromPlatform();
    setState(() {
      _appVersion = 'v${info.version}';
    });
  }

  Future<void> _loadConfiguration() async {
    setState(() => _isLoading = true);
    try {
      final config = await _configService.getConfig();
      print('Loaded config: $config');
      
      // Initialize controllers with current values
      config.forEach((key, value) {
        if (key != 'device' && key != 'createdAt' && key != 'updatedAt') {
          print('Setting controller for $key with value: $value');
          controllers[key] = TextEditingController(text: value.toString());
        }
      });
      
      setState(() => _isLoading = false);
    } catch (e) {
      print('Error loading configuration: $e');
      setState(() => _isLoading = false);
    }
  }

  Future<void> _saveConfiguration() async {
    try {
      // Show loading indicator
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Saving configuration...'),
            duration: Duration(seconds: 2),
          ),
        );
      }

      final newConfig = <String, dynamic>{};
      controllers.forEach((key, controller) {
        final value = controller.text;
        if (ConfigService.defaultConfig[key] is int) {
          newConfig[key] = int.parse(value);
        } else if (ConfigService.defaultConfig[key] is double) {
          newConfig[key] = double.parse(value);
        } else {
          newConfig[key] = value;
        }
      });

      final serverSuccess = await _configService.updateConfig(newConfig);
      
      if (mounted) {
        if (serverSuccess) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(
              content: Text('Configuration saved locally and sent to server'),
              backgroundColor: Colors.green,
              duration: Duration(seconds: 3),
            ),
          );
        } else {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(
              content: Text('Configuration saved locally but server update failed'),
              backgroundColor: Colors.orange,
              duration: Duration(seconds: 3),
            ),
          );
        }
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('Error saving configuration: $e'),
            backgroundColor: Colors.red,
            duration: Duration(seconds: 3),
          ),
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_isLoading) {
      return const Scaffold(
        body: Center(child: CircularProgressIndicator()),
      );
    }

    final isTablet = MediaQuery.of(context).size.shortestSide >= 600;
    final double titleFontSize = isTablet ? 22.0 : 18.0;
    final double labelFontSize = isTablet ? 18.0 : 14.0;
    final double fieldFontSize = isTablet ? 18.0 : 14.0;
    final double iconSize = isTablet ? 24.0 : 20.0;
    final double buttonFontSize = isTablet ? 18.0 : 16.0;
    final double buttonHeight = isTablet ? 48.0 : 44.0;
    final double borderRadius = isTablet ? 16.0 : 12.0;
    final double verticalSpacing = isTablet ? 24.0 : 16.0;
    final double logoSize = isTablet ? 60.0 : 40.0;

    return Scaffold(
      appBar: AppBar(
        backgroundColor: Colors.transparent,
        elevation: 0,
        iconTheme: const IconThemeData(color: Colors.black),
      ),
      body: SafeArea(
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 700),
            child: ListView.separated(
              padding: EdgeInsets.symmetric(horizontal: isTablet ? 24 : 16),
              itemCount: fields.length + 3, // title + fields + save + bottom row
              separatorBuilder: (_, __) => SizedBox(height: verticalSpacing / 2),
              itemBuilder: (context, index) {
                if (index == 0) {
                  return Column(
                    children: [
                      SizedBox(height: verticalSpacing),
                      Center(
                        child: Text(
                          'CONFIGURATION',
                          style: TextStyle(
                            fontSize: titleFontSize,
                            fontWeight: FontWeight.bold,
                            color: Colors.black,
                          ),
                        ),
                      ),
                      SizedBox(height: verticalSpacing),
                    ],
                  );
                } else if (index == fields.length + 1) {
                  return Padding(
                    padding: EdgeInsets.symmetric(vertical: verticalSpacing),
                    child: SizedBox(
                      width: double.infinity,
                      height: buttonHeight,
                      child: ElevatedButton(
                        style: ElevatedButton.styleFrom(
                          backgroundColor: const Color(0xFF3e4095),
                          shape: RoundedRectangleBorder(
                            borderRadius: BorderRadius.circular(buttonHeight / 2),
                          ),
                        ),
                        onPressed: _saveConfiguration,
                        child: Text(
                          'SAVE',
                          style: TextStyle(fontSize: buttonFontSize, color: Colors.white),
                        ),
                      ),
                    ),
                  );
                } else if (index == fields.length + 2) {
                  // Bottom row with version and logo
                  return Row(
                    children: [
                      Expanded(
                        child: Align(
                          alignment: Alignment.bottomLeft,
                          child: Padding(
                            padding: const EdgeInsets.only(left: 16, bottom: 8),
                            child: Text(
                              _appVersion,
                              style: TextStyle(
                                color: const Color(0xFF3e4095),
                                fontSize: buttonFontSize * 0.8,
                                fontWeight: FontWeight.w600,
                              ),
                            ),
                          ),
                        ),
                      ),
                      Expanded(
                        child: Align(
                          alignment: Alignment.bottomRight,
                          child: Padding(
                            padding: const EdgeInsets.only(right: 16, bottom: 8),
                            child: Image.asset(
                              'assets/tw.png',
                              width: logoSize,
                              height: logoSize,
                              fit: BoxFit.contain,
                            ),
                          ),
                        ),
                      ),
                    ],
                  );
                } else {
                  final field = fields[index - 1];
                  return Container(
                    decoration: BoxDecoration(
                      border: Border.all(color: Colors.grey, width: 1.2),
                      borderRadius: BorderRadius.circular(borderRadius),
                    ),
                    child: Row(
                      children: [
                        Padding(
                          padding: EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                          child: Icon(field['icon'], size: iconSize, color: Colors.grey[800]),
                        ),
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Padding(
                                padding: const EdgeInsets.only(top: 8.0, left: 4.0),
                                child: Text(
                                  field['label'],
                                  style: TextStyle(
                                    fontSize: labelFontSize,
                                    color: Colors.grey[800],
                                    fontWeight: FontWeight.w500,
                                  ),
                                ),
                              ),
                              Padding(
                                padding: const EdgeInsets.only(left: 4.0, bottom: 8.0),
                                child: TextField(
                                  controller: controllers[field['key']],
                                  style: TextStyle(fontSize: fieldFontSize),
                                  keyboardType: TextInputType.number,
                                  decoration: const InputDecoration(
                                    border: InputBorder.none,
                                    isDense: true,
                                    contentPadding: EdgeInsets.zero,
                                  ),
                                ),
                              ),
                            ],
                          ),
                        ),
                      ],
                    ),
                  );
                }
              },
            ),
          ),
        ),
      ),
    );
  }

  @override
  void dispose() {
    controllers.values.forEach((controller) => controller.dispose());
    super.dispose();
  }
} 