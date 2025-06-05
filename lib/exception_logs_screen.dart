import 'package:flutter/material.dart';
import 'services/database_helper.dart';

class ExceptionLogsScreen extends StatefulWidget {
  const ExceptionLogsScreen({Key? key}) : super(key: key);

  @override
  State<ExceptionLogsScreen> createState() => _ExceptionLogsScreenState();
}

class _ExceptionLogsScreenState extends State<ExceptionLogsScreen> {
  late Future<List<Map<String, dynamic>>> _logsFuture;

  @override
  void initState() {
    super.initState();
    _fetchLogs();
  }

  void _fetchLogs() {
    setState(() {
      _logsFuture = DatabaseHelper().getExceptionLogs();
    });
  }

  Future<void> _clearAllLogs() async {
    await DatabaseHelper().clearExceptionLogs();
    _fetchLogs();
  }

  @override
  Widget build(BuildContext context) {
    final isTablet = MediaQuery.of(context).size.shortestSide >= 600;
    final double titleFontSize = isTablet ? 38.0 : 28.0;
    final double logFontSize = isTablet ? 20.0 : 15.0;
    final double verticalSpacing = isTablet ? 40.0 : 24.0;
    final double cardRadius = isTablet ? 18.0 : 12.0;
    final double iconSize = isTablet ? 36.0 : 28.0;

    return Scaffold(
      appBar: AppBar(
        backgroundColor: Colors.transparent,
        elevation: 0,
        iconTheme: const IconThemeData(color: Colors.black),
        title: Text(
          'EXCEPTION LOGS',
          style: TextStyle(
            fontSize: titleFontSize,
            fontWeight: FontWeight.bold,
            color: Colors.black,
          ),
        ),
        centerTitle: true,
        actions: [
          IconButton(
            icon: Icon(Icons.delete_forever, size: iconSize, color: Colors.red),
            tooltip: 'Clear All',
            onPressed: () async {
              await _clearAllLogs();
            },
          ),
          IconButton(
            icon: Icon(Icons.download, size: iconSize, color: Colors.black),
            onPressed: () {
              // TODO: Implement download functionality
            },
          ),
        ],
      ),
      body: SafeArea(
        child: FutureBuilder<List<Map<String, dynamic>>>(
          future: _logsFuture,
          builder: (context, snapshot) {
            if (snapshot.connectionState == ConnectionState.waiting) {
              return const Center(child: CircularProgressIndicator());
            }
            if (!snapshot.hasData || snapshot.data!.isEmpty) {
              return const Center(child: Text('No exception logs.'));
            }
            final logs = snapshot.data!;
            return ListView.separated(
              padding: EdgeInsets.symmetric(vertical: verticalSpacing, horizontal: 12),
              itemCount: logs.length,
              separatorBuilder: (_, __) => SizedBox(height: verticalSpacing / 2),
              itemBuilder: (context, index) {
                final log = logs[index];
                final mainMessage = log['main'] ?? '';
                final details = log['details'] ?? '';
                return Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Container(
                      width: double.infinity,
                      decoration: BoxDecoration(
                        color: Colors.blue[800],
                        borderRadius: BorderRadius.circular(cardRadius),
                      ),
                      padding: EdgeInsets.all(isTablet ? 18 : 12),
                      child: Text(
                        mainMessage,
                        style: TextStyle(
                          color: Colors.white,
                          fontSize: logFontSize,
                          fontWeight: FontWeight.w500,
                        ),
                      ),
                    ),
                    if (details.isNotEmpty)
                      Padding(
                        padding: EdgeInsets.only(top: 8, left: 2, right: 2),
                        child: Text(
                          details,
                          style: TextStyle(
                            color: Colors.black,
                            fontSize: logFontSize,
                          ),
                        ),
                      ),
                  ],
                );
              },
            );
          },
        ),
      ),
    );
  }
} 