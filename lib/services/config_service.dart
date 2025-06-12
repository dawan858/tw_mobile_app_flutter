import 'dart:convert';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';

class ConfigService {
  static final ConfigService _instance = ConfigService._internal();
  static const String baseUrl = 'http://ec2-52-66-236-101.ap-south-1.compute.amazonaws.com:3000/api';
  
  // Default values
  static const Map<String, dynamic> defaultConfig = {
    'gpsTimer': 5,
    'configTimer': 60,
    'uploadTimer': 10,
    'retryCounter': 10,
    'angleThreshold': 45,
    'overSpeedingThreshold': 60,
    'travelStartTimer': 20,
    'travelStopTimer': 20,
    'movingTimer': 60,
    'stopTimer': 130,
    'distanceThreshold': 1000,
    'heartbeatTimer': 30,
    'liveStatusUpdateTimer': 30,
  };

  factory ConfigService() => _instance;

  ConfigService._internal();

  Future<Map<String, dynamic>> fetchConfigFromServer(String imei) async {
    try {
      final response = await http.get(
        Uri.parse('$baseUrl/devices/$imei/config'),
        headers: {'Content-Type': 'application/json'},
      );

      if (response.statusCode == 200) {
        final data = jsonDecode(response.body);
        // Save to SharedPreferences
        await _saveConfig(data);
        return data;
      } else {
        print('Failed to fetch configuration. Status code: ${response.statusCode}');
        print('Response body: ${response.body}');
        // Return default config if fetch fails
        return defaultConfig;
      }
    } catch (e) {
      print('Error fetching configuration: $e');
      // Return default config if fetch fails
      return defaultConfig;
    }
  }

  Future<void> _saveConfig(Map<String, dynamic> config) async {
    final prefs = await SharedPreferences.getInstance();
    for (var entry in config.entries) {
      await prefs.setString('flutter.${entry.key}', entry.value.toString());
    }
  }

  Future<Map<String, dynamic>> getConfig() async {
    final prefs = await SharedPreferences.getInstance();
    final config = <String, dynamic>{};
    
    for (var key in defaultConfig.keys) {
      final value = prefs.getString('flutter.$key');
      if (value != null) {
        // Convert string values to appropriate types
        if (defaultConfig[key] is int) {
          config[key] = int.parse(value);
        } else if (defaultConfig[key] is double) {
          config[key] = double.parse(value);
        } else {
          config[key] = value;
        }
      } else {
        config[key] = defaultConfig[key];
      }
    }
    
    return config;
  }

  Future<void> updateConfig(Map<String, dynamic> newConfig) async {
    await _saveConfig(newConfig);
  }
} 