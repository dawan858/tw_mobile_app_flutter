import 'package:flutter/material.dart';
import 'services/database_helper.dart';
import 'package:package_info_plus/package_info_plus.dart';

class PendingDataScreen extends StatefulWidget {
  const PendingDataScreen({Key? key}) : super(key: key);

  @override
  State<PendingDataScreen> createState() => _PendingDataScreenState();
}

class _PendingDataScreenState extends State<PendingDataScreen> {
  late Future<List<Map<String, dynamic>>> _pendingDataFuture;
  String _appVersion = '';

  @override
  void initState() {
    super.initState();
    _pendingDataFuture = DatabaseHelper().getUnsyncedData();
    _loadAppVersion();
  }

  Future<void> _loadAppVersion() async {
    final info = await PackageInfo.fromPlatform();
    setState(() {
      _appVersion = 'v${info.version}';
    });
  }

  @override
  Widget build(BuildContext context) {
    final isTablet = MediaQuery.of(context).size.shortestSide >= 600;
    final double titleFontSize = isTablet ? 38.0 : 28.0;
    final double itemFontSize = isTablet ? 22.0 : 16.0;
    final double logoSize = isTablet ? 60.0 : 40.0;
    final double verticalSpacing = isTablet ? 40.0 : 24.0;

    return Scaffold(
      appBar: AppBar(
        backgroundColor: Colors.transparent,
        elevation: 0,
        iconTheme: const IconThemeData(color: Colors.black),
      ),
      body: SafeArea(
        child: Column(
          children: [
            SizedBox(height: verticalSpacing),
            Center(
              child: Text(
                'PENDING DATA',
                style: TextStyle(
                  fontSize: titleFontSize,
                  fontWeight: FontWeight.bold,
                  color: Colors.black,
                ),
              ),
            ),
            SizedBox(height: verticalSpacing),
            Expanded(
              child: FutureBuilder<List<Map<String, dynamic>>>(
                future: _pendingDataFuture,
                builder: (context, snapshot) {
                  if (snapshot.connectionState == ConnectionState.waiting) {
                    return const Center(child: CircularProgressIndicator());
                  }
                  if (!snapshot.hasData || snapshot.data!.isEmpty) {
                    return const SizedBox();
                  }
                  final data = snapshot.data!;
                  return ListView.separated(
                    itemCount: data.length,
                    separatorBuilder: (_, __) => Divider(),
                    itemBuilder: (context, index) {
                      final entry = data[index];
                      return ListTile(
                        title: Text(
                          'Lat: ${entry['latitude']}, Lng: ${entry['longitude']}',
                          style: TextStyle(fontSize: itemFontSize, fontWeight: FontWeight.bold),
                        ),
                        subtitle: Text(
                          'Time: ${entry['timestamp'] ?? ''}\nIMEI: ${entry['imei'] ?? ''}',
                          style: TextStyle(fontSize: itemFontSize * 0.9),
                        ),
                        trailing: Text(
                          'ID: ${entry['id']}',
                          style: TextStyle(fontSize: itemFontSize * 0.8, color: Colors.grey),
                        ),
                      );
                    },
                  );
                },
              ),
            ),
            Row(
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
                          fontSize: itemFontSize,
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
            ),
          ],
        ),
      ),
    );
  }
} 