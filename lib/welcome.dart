import 'package:flutter/material.dart';
import 'package:qr_flutter/qr_flutter.dart';
import 'verification_screen.dart';
import 'package:package_info_plus/package_info_plus.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';

class WelcomeScreen extends StatefulWidget {
  final String imei;
  final String version;
  final VoidCallback onInfoTap;
  final Map<String, String> trackingData;
  const WelcomeScreen({Key? key, required this.imei, required this.version, required this.onInfoTap, required this.trackingData}) : super(key: key);

  @override
  State<WelcomeScreen> createState() => _WelcomeScreenState();
}

class _WelcomeScreenState extends State<WelcomeScreen> {
  int _versionTapCount = 0;
  DateTime? _lastTapTime;

  void _handleVersionTap() {
    final now = DateTime.now();
    if (_lastTapTime == null || now.difference(_lastTapTime!) > Duration(seconds: 2)) {
      _versionTapCount = 1;
    } else {
      _versionTapCount++;
      if (_versionTapCount == 5) {
        _versionTapCount = 0;
        Navigator.of(context).push(
          MaterialPageRoute(
            builder: (_) => VerificationScreen(trackingData: widget.trackingData),
          ),
        );
        return;
      }
    }
    _lastTapTime = now;
  }

  @override
  Widget build(BuildContext context) {
    final isTablet = MediaQuery.of(context).size.shortestSide >= 600;
    final qrSize = isTablet ? 300.0 : 200.0;
    final padding = isTablet ? 32.0 : 16.0;
    final fontSize = isTablet ? 28.0 : 18.0;
    final logoSize = isTablet ? 120.0 : 80.0;
    final cardRadius = isTablet ? 32.0 : 20.0;

    return Scaffold(
      backgroundColor: Colors.grey.shade200,
      body: SafeArea(
        child: Container(
          width: double.infinity,
          height: double.infinity,
          // decoration: BoxDecoration(
          //   color: Colors.white,
          //   borderRadius: BorderRadius.circular(cardRadius),
          //   boxShadow: [
          //     BoxShadow(
          //       color: Colors.black12,
          //       blurRadius: 16,
          //       offset: Offset(0, 8),
          //     ),
          //   ],
          // ),
          child: Padding(
            padding: EdgeInsets.only(left: padding,  top: padding, bottom: padding),
            child: Column(
              children: [
                // Main content row (Honda + QR)
                Expanded(
                  child: Row(
                    children: [
                      // Left column: logo, text
                      Expanded(
                        flex: 4,
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          mainAxisAlignment: MainAxisAlignment.start,
                          children: [
                            Expanded(
                              child: Image.asset(
                                'assets/honda.png',
                                width: 300,
                                height: 250,
                               // fit: BoxFit.fitHeight,
                              
                              ),
                            
                            ), 
                            Padding(
                              padding: const EdgeInsets.only(left: 40.0),
                              child:Text(
                                "WELCOME,",
                                style: TextStyle(fontWeight: FontWeight.bold, fontSize: fontSize, color: const Color(0xFF3e4095)),
                              ),
                            ),
                            
                            
                            Padding(
                              padding: const EdgeInsets.only(left: 40.0),
                              child: Text(
                                widget.imei,
                                style: TextStyle(color: const Color(0xFF3e4095), fontSize: fontSize * 1.1, fontWeight: FontWeight.w500),
                              ),
                            ),
                            
                            Padding(
                              padding: const EdgeInsets.only(left: 40.0),
                              child: Text(
                                "Scan the QRCode during Signup Process to enjoy our tracking services.",
                                style: TextStyle(color: Colors.teal, fontSize: fontSize * 0.95),
                              ),
                            ),
                          ],
                        ),
                      ),
                      // Right column: QR code
                      Expanded(
                        flex: 2,
                        child: Center(
                          child: QrImageView(
                            data: widget.imei,
                            version: QrVersions.auto,
                            size: qrSize,
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
                // Bottom row: info icon, TW logo, version number
                Row(
                  crossAxisAlignment: CrossAxisAlignment.end,
                  children: [
                    // Info icon (left)
                    Expanded(
                      child: Align(
                        alignment: Alignment.bottomLeft,
                        child: Padding(
                          padding: EdgeInsets.only(left: 40, bottom: 8),
                          child: GestureDetector(
                            onTap: widget.onInfoTap,
                            child: Icon(Icons.info, color: const Color(0xFF3e4095), size: 45),
                          ),
                        ),
                      ),
                    ),
                    // TW logo (center)
                    Expanded(
                      child: Align(
                        alignment: Alignment.bottomCenter,
                        child: Padding(
                          padding: const EdgeInsets.only(bottom: 8),
                          child: 
                              Image.asset(
                                'assets/tw.png',
                                width: logoSize * 0.7,
                                height: logoSize * 0.7,
                                fit: BoxFit.contain,
                              ),
                              
                            
                        ),
                      ),
                    ),
                    // Version number (right)
                    Expanded(
                      child: Align(
                        alignment: Alignment.bottomRight,
                        child: Padding(
                          padding: const EdgeInsets.only(right: 60, bottom: 25),
                          child: Column(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              GestureDetector(
                                onTap: _handleVersionTap,
                                child: Text(
                                  widget.version,
                                  style: TextStyle(color: const Color(0xFF3e4095), fontSize: fontSize * 0.8),
                                ),
                              ),
                            ],
                          ),
                        ),
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
} 