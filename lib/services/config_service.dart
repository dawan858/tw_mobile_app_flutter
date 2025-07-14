import 'dart:convert';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';

class ConfigService {
  static final ConfigService _instance = ConfigService._internal();
  static const String baseUrl = 'http://121.91.56.50:3000/api';
  
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

  Future<Map<String, dynamic>> fetchImeiConfig(String imei) async {
    try {
      final response = await http.get(
        Uri.parse('$baseUrl/devices/$imei/config'),
        headers: {'Content-Type': 'application/json'},
      );

      if (response.statusCode == 200) {
        final responseData = jsonDecode(response.body);
        if (responseData['success'] == true && responseData['data'] != null) {
          final data = responseData['data'];
          // Save to SharedPreferences
          await _saveConfig(data);
          return data;
        } else {
          print('Invalid response format: ${response.body}');
          return await getLocalConfig();
        }
      } else {
        print('Failed to fetch IMEI configuration. Status code: ${response.statusCode}');
        print('Response body: ${response.body}');
        return await getLocalConfig();
      }
    } catch (e) {
      print('Error fetching IMEI configuration: $e');
      return await getLocalConfig();
    }
  }

  Future<Map<String, dynamic>> getLocalConfig() async {
    final prefs = await SharedPreferences.getInstance();
    final config = <String, dynamic>{};
    
    for (var key in defaultConfig.keys) {
      final value = prefs.getString('flutter.$key');
      if (value != null) {
        // Convert string values to appropriate types based on defaultConfig
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

  Future<Map<String, dynamic>> getConfig() async {
    final prefs = await SharedPreferences.getInstance();
    final imei = prefs.getString('imei') ?? 'unknown';
    
    // Try to fetch from server first
    return await fetchImeiConfig(imei);
  }

  Future<void> updateConfig(Map<String, dynamic> newConfig) async {
    await _saveConfig(newConfig);
  }

  Future<Map<String, dynamic>> fetchDefaultConfigFromServer() async {
    try {
      final response = await http.get(
        Uri.parse('$baseUrl/default-config'),
        headers: {'Content-Type': 'application/json'},
      );

      if (response.statusCode == 200) {
        final data = jsonDecode(response.body);
        await _saveConfig(data);
        return data;
      } else {
        print('Failed to fetch default config. Status code: [${response.statusCode}m');
        print('Response body: ${response.body}');
        return defaultConfig;
      }
    } catch (e) {
      print('Error fetching default config: $e');
      return defaultConfig;
    }
  }

  Future<void> sendDefaultConfigToServer(String imei) async {
    try {
      // Convert all values to strings as required by the API
      final Map<String, String> stringConfig = defaultConfig.map(
        (key, value) => MapEntry(key, value.toString())
      );
      
      // Add baseUrl if it's required
      stringConfig['baseUrl'] = baseUrl;
      
      print('Sending config to server: $stringConfig');
      
      final response = await http.post(
        Uri.parse('$baseUrl/devices/$imei/config'),
        headers: {'Content-Type': 'application/json'},
        body: jsonEncode(stringConfig),
      );

      if (response.statusCode == 200) {
        print('Default config sent to server for IMEI: $imei');
        // Save the sent config to SharedPreferences
        await _saveConfig(stringConfig);
      } else {
        print('Failed to send default config. Status code: ${response.statusCode}');
        print('Response body: ${response.body}');
        print('Sent data: ${jsonEncode(stringConfig)}');
      }
    } catch (e) {
      print('Error sending default config: $e');
    }
  }
} 