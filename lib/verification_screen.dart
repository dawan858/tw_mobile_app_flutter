import 'package:flutter/material.dart';
import 'settings_screen.dart';

class VerificationScreen extends StatefulWidget {
  final Map<String, String> trackingData;
  const VerificationScreen({Key? key, required this.trackingData}) : super(key: key);

  @override
  State<VerificationScreen> createState() => _VerificationScreenState();
}

class _VerificationScreenState extends State<VerificationScreen> {
  final TextEditingController _passwordController = TextEditingController();
  bool _obscureText = true;

  @override
  Widget build(BuildContext context) {
    final isTablet = MediaQuery.of(context).size.shortestSide >= 600;
    final double padding = isTablet ? 32.0 : 16.0;
    final double titleFontSize = isTablet ? 28.0 : 18.0;
    final double textFieldFontSize = isTablet ? 22.0 : 16.0;
    final double labelFontSize = isTablet ? 18.0 : 14.0;
    final double buttonFontSize = isTablet ? 22.0 : 16.0;
    final double buttonHeight = isTablet ? 56.0 : 44.0;
    final double borderRadius = isTablet ? 20.0 : 12.0;
    final double verticalSpacing = isTablet ? 40.0 : 20.0;

    return Scaffold(
      resizeToAvoidBottomInset: true,
      appBar: AppBar(
        leading: BackButton(color: Colors.black),
        backgroundColor: Colors.transparent,
        elevation: 0,
      ),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: EdgeInsets.symmetric(horizontal: padding),
          child: ConstrainedBox(
            constraints: BoxConstraints(
              minHeight: MediaQuery.of(context).size.height - MediaQuery.of(context).padding.top - kToolbarHeight,
            ),
            child: IntrinsicHeight(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.center,
                children: [
                  SizedBox(height: verticalSpacing),
                  Text(
                    "VERIFICATION",
                    style: TextStyle(
                      fontSize: titleFontSize,
                      fontWeight: FontWeight.bold,
                      color: Colors.black,
                    ),
                  ),
                  SizedBox(height: verticalSpacing),
                  TextField(
                    controller: _passwordController,
                    obscureText: _obscureText,
                    style: TextStyle(
                      fontSize: textFieldFontSize,
                      letterSpacing: 2.0,
                    ),
                    decoration: InputDecoration(
                      labelText: "Password",
                      labelStyle: TextStyle(
                        fontSize: labelFontSize,
                        color: Colors.grey[800],
                        fontWeight: FontWeight.w500,
                      ),
                      prefixIcon: Icon(Icons.lock_outline, color: Color(0xFF3e4095)),
                      suffixIcon: IconButton(
                        icon: Icon(
                          _obscureText ? Icons.visibility_off : Icons.visibility,
                          color: Colors.grey,
                        ),
                        onPressed: () {
                          setState(() {
                            _obscureText = !_obscureText;
                          });
                        },
                      ),
                      enabledBorder: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(borderRadius),
                        borderSide: BorderSide(color: Color(0xFF3e4095), width: 2),
                      ),
                      focusedBorder: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(borderRadius),
                        borderSide: BorderSide(color: Color(0xFF3e4095), width: 2),
                      ),
                      contentPadding: EdgeInsets.symmetric(vertical: isTablet ? 20.0 : 14.0, horizontal: 0),
                      filled: true,
                      fillColor: Colors.transparent,
                    ),
                  ),
                  SizedBox(height: verticalSpacing),
                  SizedBox(
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
                        Navigator.of(context).pushReplacement(
                          MaterialPageRoute(
                            builder: (_) => SettingsScreen(
                              trackingData: widget.trackingData,
                            ),
                          ),
                        );
                      },
                      child: Text(
                        "NEXT",
                        style: TextStyle(fontSize: buttonFontSize, color: Colors.white),
                      ),
                    ),
                  ),
                  SizedBox(height: 20),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
} 