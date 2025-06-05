import 'package:flutter/material.dart';
import 'package:tracking_world/configuration_screen.dart';
import 'live_status_screen.dart';
import 'pending_data_screen.dart';
import 'exception_logs_screen.dart';

class SettingsScreen extends StatelessWidget {
  final String version;
  final Map<String, String> trackingData;
  const SettingsScreen({Key? key, required this.version, required this.trackingData}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    final isTablet = MediaQuery.of(context).size.shortestSide >= 600;
    final double padding = isTablet ? 48.0 : 24.0;
    final double titleFontSize = isTablet ? 38.0 : 28.0;
    final double buttonFontSize = isTablet ? 28.0 : 20.0;
    final double buttonHeight = isTablet ? 70.0 : 56.0;
    final double borderRadius = isTablet ? 32.0 : 24.0;
    final double verticalSpacing = isTablet ? 32.0 : 20.0;
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
            builder: (_) => LiveStatusScreen(trackingData: trackingData),
          ),
        );
      }},
      {'label': 'CONFIGURATION', 'onTap': () {
        Navigator.of(context).push(
          MaterialPageRoute(
            builder: (_) => const ConfigurationScreen(version: 'v250111'),
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
      {'label': 'LOGOUT', 'onTap': () {}},
    ];

    return Scaffold(
      appBar: AppBar(
        leading: BackButton(color: Colors.black),
        backgroundColor: Colors.transparent,
        elevation: 0,
      ),
      body: SafeArea(
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
                            onPressed: btn['label'] == 'LIVE STATUS' || btn['label'] == 'PENDING DATA' || btn['label'] == 'EXCEPTION LOGS'
                                ? () => btn['onTap'](context)
                                : btn['onTap'],
                            child: Text(
                              btn['label'],
                              style: TextStyle(fontSize: buttonFontSize, color: Colors.white),
                            ),
                          ),
                        ),
                      )),
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
                        version,
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
    );
  }
} 