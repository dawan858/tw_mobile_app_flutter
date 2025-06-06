import 'package:flutter/material.dart';
import 'package:package_info_plus/package_info_plus.dart';

class ConfigurationScreen extends StatefulWidget {
  const ConfigurationScreen({Key? key}) : super(key: key);

  @override
  State<ConfigurationScreen> createState() => _ConfigurationScreenState();
}

class _ConfigurationScreenState extends State<ConfigurationScreen> {
  String _appVersion = '';

  @override
  void initState() {
    super.initState();
    _loadAppVersion();
  }

  Future<void> _loadAppVersion() async {
    final info = await PackageInfo.fromPlatform();
    setState(() {
      _appVersion = 'v${info.version}';
    });
  }

  // Preloaded values (replace with your actual config source)
  final Map<String, TextEditingController> controllers = {
    'gpsTimer': TextEditingController(text: '5'),
    'configTimer': TextEditingController(text: '60'),
    'uploadTimer': TextEditingController(text: '10'),
    'retryCounter': TextEditingController(text: '10'),
    'angleThreshold': TextEditingController(text: '45'),
    'overSpeedingThreshold': TextEditingController(text: '60'),
    'travelStartTimer': TextEditingController(text: '20'),
    'travelStopTimer': TextEditingController(text: '20'),
    'movingTimer': TextEditingController(text: '60'),
    'stopTimer': TextEditingController(text: '130'),
    'distanceThreshold': TextEditingController(text: '1000'),
    'heartbeatTimer': TextEditingController(text: '30'),
    'liveStatusUpdateTimer': TextEditingController(text: '30'),
    'baseUrl': TextEditingController(text: 'https://connectlive.commtw.com:446/twconnectlive/TrackingServices.asmx'),
  };

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
    {'key': 'baseUrl', 'label': 'BASE URL', 'icon': Icons.link},
  ];

  @override
  Widget build(BuildContext context) {
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
            constraints: BoxConstraints(maxWidth: 700),
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
                          backgroundColor: Color(0xFF3e4095),
                          shape: RoundedRectangleBorder(
                            borderRadius: BorderRadius.circular(buttonHeight / 2),
                          ),
                        ),
                        onPressed: () {
                          // TODO: Save logic
                          final config = { for (var f in fields) f['key']: controllers[f['key']]!.text };
                          // Save config as needed
                        },
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
                                color: Color(0xFF3e4095),
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
} 