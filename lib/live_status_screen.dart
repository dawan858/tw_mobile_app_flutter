import 'package:flutter/material.dart';
import 'services/live_data_service.dart';

class LiveStatusScreen extends StatefulWidget {
  final Map<String, String>? initialTrackingData;
  const LiveStatusScreen({Key? key, this.initialTrackingData}) : super(key: key);

  @override
  State<LiveStatusScreen> createState() => _LiveStatusScreenState();
}

class _LiveStatusScreenState extends State<LiveStatusScreen> {
  final LiveDataService _liveDataService = LiveDataService();
  bool _isInitialized = false;

  @override
  void initState() {
    super.initState();
    _initializeLiveData();
  }

  Future<void> _initializeLiveData() async {
    try {
      await _liveDataService.initialize();
      if (mounted) {
        setState(() {
          _isInitialized = true;
        });
      }
    } catch (e) {
      print('Error initializing live data: $e');
    }
  }

  @override
  void dispose() {
    _liveDataService.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final isTablet = MediaQuery.of(context).size.shortestSide >= 600;
    final headingStyle = TextStyle(
      fontWeight: FontWeight.bold,
      fontSize: isTablet ? 28 : 22,
      color: Colors.black,
    );
    final labelStyle = TextStyle(fontWeight: FontWeight.w500, fontSize: isTablet ? 18 : 15, color: Colors.black87);
    final valueStyle = TextStyle(fontSize: isTablet ? 18 : 15, color: Colors.black87);
    final rowPadding = EdgeInsets.symmetric(vertical: isTablet ? 14 : 10, horizontal: isTablet ? 28 : 16);
    final altRowColor = Colors.white;
    final normalRowColor = const Color(0xFF3e4095);

    return Scaffold(
      backgroundColor: Colors.white,
      appBar: AppBar(
        backgroundColor: Colors.white,
        elevation: 0,
        leading: IconButton(
          icon: const Icon(Icons.arrow_back, color: Colors.black),
          onPressed: () => Navigator.of(context).pop(),
        ),
        title: null,
        centerTitle: false,
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh, color: Colors.black),
            onPressed: () async {
              // Show loading indicator
              ScaffoldMessenger.of(context).showSnackBar(
                const SnackBar(
                  content: Text('Refreshing satellite data...'),
                  duration: Duration(seconds: 2),
                ),
              );
              
              // Refresh all data
              await _liveDataService.refreshData();
              // Also specifically refresh satellite data
              await _liveDataService.refreshSatelliteData();
              
              // Show completion message
              if (mounted) {
                ScaffoldMessenger.of(context).showSnackBar(
                  const SnackBar(
                    content: Text('Data refreshed successfully'),
                    duration: Duration(seconds: 1),
                  ),
                );
              }
            },
          ),
        ],
      ),
      body: !_isInitialized
          ? const Center(
              child: CircularProgressIndicator(),
            )
          : StreamBuilder<Map<String, String>>(
              stream: _liveDataService.dataStream,
              initialData: widget.initialTrackingData ?? _liveDataService.getCurrentData(),
              builder: (context, snapshot) {
                final trackingData = snapshot.data ?? {};
                
                // Data in order as in screenshot
                final List<Map<String, String>> rows = [
                  {'label': 'Total Satellites', 'value': trackingData['totalSatellites'] ?? ''},
                  {'label': 'Connected Satellites', 'value': trackingData['connectedSatellites'] ?? ''},
                  {'label': 'STATUS', 'value': trackingData['status'] ?? ''},
                  {'label': 'LATITUDE', 'value': trackingData['latitude'] ?? ''},
                  {'label': 'LONGITUDE', 'value': trackingData['longitude'] ?? ''},
                  {'label': 'ALTITUDE', 'value': trackingData['altitude'] ?? ''},
                  {'label': 'ANGLE', 'value': trackingData['angle'] ?? ''},
                  {'label': 'SPEED', 'value': trackingData['speed'] ?? ''},
                  {'label': 'ACCURACY', 'value': trackingData['accuracy'] ?? ''},
                  {'label': 'LAST POLL TIME', 'value': trackingData['lastPollTime'] ?? ''},
                  {'label': 'LOCAL TIME', 'value': trackingData['localTime'] ?? ''},
                  {'label': 'GMT', 'value': trackingData['gmt'] ?? ''},
                  {'label': 'PENDING DATA', 'value': trackingData['pendingData'] ?? ''},
                  {'label': 'SERVER', 'value': trackingData['server'] ?? ''},
                  {'label': 'REFRESH TIME', 'value': trackingData['refreshTime'] ?? ''},
                ];

                Widget buildRow(String label, String value, bool alt) {
                  final textColor = alt ? Colors.black87 : Colors.white;
                  return Container(
                    color: alt ? altRowColor : normalRowColor,
                    padding: rowPadding,
                    child: Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Text(label, style: labelStyle.copyWith(color: textColor)),
                        Text(value, style: valueStyle.copyWith(color: textColor)),
                      ],
                    ),
                  );
                }

                return SingleChildScrollView(
                  child: Padding(
                    padding: EdgeInsets.symmetric(horizontal: isTablet ? 32 : 12, vertical: isTablet ? 24 : 12),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        const SizedBox(height: 8),
                        Row(
                          mainAxisAlignment: MainAxisAlignment.center,
                          children: [
                            Text('LIVE STATUS', style: headingStyle, textAlign: TextAlign.center),
                            const SizedBox(width: 8),
                            Container(
                              width: 8,
                              height: 8,
                              decoration: const BoxDecoration(
                                color: Colors.green,
                                shape: BoxShape.circle,
                              ),
                            ),
                          ],
                        ),
                        const SizedBox(height: 18),
                        Card(
                          elevation: 3,
                          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
                          margin: EdgeInsets.zero,
                          child: Column(
                            children: [
                              for (int i = 0; i < rows.length; i++)
                                buildRow(rows[i]['label']!, rows[i]['value']!, i % 2 == 1),
                            ],
                          ),
                        ),
                      ],
                    ),
                  ),
                );
              },
            ),
    );
  }
} 