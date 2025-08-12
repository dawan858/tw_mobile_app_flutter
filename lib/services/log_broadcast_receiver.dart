import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:fluttertoast/fluttertoast.dart';
import 'package:logger/logger.dart';
import 'log_upload_service.dart';
import 'dart:io';

import 'logs.dart';

class LogBroadcastReceiver {
  static const MethodChannel _channel = MethodChannel('log_broadcast_channel');
  static final LogUploadService _logUploadService = LogUploadService();
  static var logger = Logger();

  static void initialize() {
    _channel.setMethodCallHandler((call) async {

      logger.e('📤 METHOD CHENNEL INVOCATION: $call');
      switch (call.method) {
        case 'onIgnitionLogInserted':
          try {
            final message = call.arguments['message'] as String? ?? '';
            final details = call.arguments['details'] as String? ?? '';
            final logType = call.arguments['logType'] as String? ?? 'info';
            final timestamp = call.arguments['timestamp'] as String? ?? '';
            
            print('📤 Received ignition log broadcast: $message');
            await _logUploadService.uploadIgnitionLog(
              message: message,
              details: details,
              logType: logType,
              timestamp: timestamp,
            );
          } catch (e) {
            appendLog('❌ Error handling ignition log broadcast: $e');
            print('❌ Error handling ignition log broadcast: $e');
            logger.e('📤 METHOD CHENNEL INVOCATION: $e');

          }
          break;
          
        case 'onExceptionLogInserted':
          try {
            final main = call.arguments['main'] as String? ?? '';
            final details = call.arguments['details'] as String? ?? '';
            final timestamp = call.arguments['timestamp'] as String? ?? '';
            
            print('📤 Received exception log broadcast: $main');
            
            await _logUploadService.uploadExceptionLog(
              main: main,
              details: details,
              timestamp: timestamp,
            );
          } catch (e) {
            print('❌ Error handling exception log broadcast: $e');
          }
          break;
          
        default:
          print('Unknown method call: ${call.method}');
      }
    });
    
    print('✅ LogBroadcastReceiver initialized');
  }
  
  static void dispose() {
    print('🛑 LogBroadcastReceiver disposed');
  }
} 