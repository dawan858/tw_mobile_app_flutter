import 'dart:convert';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';

class ConfigService {
  static final ConfigService _instance = ConfigService._internal();
  static const String baseUrl = 'http://twca.trackingworld.com.pk:3000/api';
  
  // Default values
  static const Map<String, dynamic> defaultConfig = {
    'gpsTimer': 5,
    'configTimer': 60,
    'uploadTimer': 10,
    'retryCounter': 10,
    'angleThreshold': 45.0,
    'overSpeedingThreshold': 60.0,
    'travelStartTimer': 20,
    'travelStopTimer': 20,
    'movingTimer': 60,
    'stopTimer': 130,
    'distanceThreshold': 1000.0,
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
      final key = 'flutter.${entry.key}';
      final value = entry.value;
      
      try {
        // Store values with appropriate types based on defaultConfig
        if (defaultConfig[entry.key] is int) {
          final intValue = int.tryParse(value.toString()) ?? defaultConfig[entry.key] as int;
          await prefs.setInt(key, intValue);
          print('✅ Saved config $key as int: $intValue');
        } else if (defaultConfig[entry.key] is double) {
          final doubleValue = double.tryParse(value.toString()) ?? defaultConfig[entry.key] as double;
          await prefs.setDouble(key, doubleValue);
          print('✅ Saved config $key as double: $doubleValue');
        } else {
          await prefs.setString(key, value.toString());
          print('✅ Saved config $key as string: $value');
        }
      } catch (e) {
        print('❌ Error saving config $key: $e');
        // Use default value as fallback
        if (defaultConfig[entry.key] is int) {
          await prefs.setInt(key, defaultConfig[entry.key] as int);
        } else if (defaultConfig[entry.key] is double) {
          await prefs.setDouble(key, defaultConfig[entry.key] as double);
        } else {
          await prefs.setString(key, defaultConfig[entry.key].toString());
        }
      }
    }
    
    // Also update the main app's tracking parameters
    await _updateTrackingParameters(config);
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
      try {
        // Try to get the value as the correct type first
        dynamic value;
        if (defaultConfig[key] is int) {
          // Try to get as int first
          value = prefs.getInt('flutter.$key');
          if (value == null) {
            // Try as double and convert to int
            final doubleValue = prefs.getDouble('flutter.$key');
            if (doubleValue != null) {
              value = doubleValue.toInt();
            } else {
              // Try as string and parse
              final stringValue = prefs.getString('flutter.$key');
              if (stringValue != null) {
                final intValue = int.tryParse(stringValue);
                if (intValue != null) {
                  value = intValue;
                }
              }
            }
          }
        } else if (defaultConfig[key] is double) {
          // Try to get as double first
          value = prefs.getDouble('flutter.$key');
          if (value == null) {
            // Try as int and convert to double
            final intValue = prefs.getInt('flutter.$key');
            if (intValue != null) {
              value = intValue.toDouble();
            } else {
              // Try as string and parse
              final stringValue = prefs.getString('flutter.$key');
              if (stringValue != null) {
                final doubleValue = double.tryParse(stringValue);
                if (doubleValue != null) {
                  value = doubleValue;
                }
              }
            }
          }
        } else {
          value = prefs.getString('flutter.$key');
        }
        
        config[key] = value ?? defaultConfig[key];
      } catch (e) {
        print('Error reading $key from SharedPreferences: $e');
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

  // NEW: Validate and debug configuration
  Future<Map<String, dynamic>> validateConfig() async {
    final prefs = await SharedPreferences.getInstance();
    final config = <String, dynamic>{};
    final issues = <String>[];
    
    print('🔍 === CONFIGURATION VALIDATION ===');
    
    for (var key in defaultConfig.keys) {
      try {
        // Try to get the value as the correct type first
        dynamic value;
        if (defaultConfig[key] is int) {
          // Try to get as int first
          value = prefs.getInt('flutter.$key');
          if (value == null) {
            // Try as double and convert to int
            final doubleValue = prefs.getDouble('flutter.$key');
            if (doubleValue != null) {
              value = doubleValue.toInt();
              print('🔄 Converted $key from double ($doubleValue) to int ($value)');
            } else {
              // Try as string and parse
              final stringValue = prefs.getString('flutter.$key');
              if (stringValue != null) {
                final intValue = int.tryParse(stringValue);
                if (intValue != null) {
                  value = intValue;
                }
              }
            }
          }
        } else if (defaultConfig[key] is double) {
          // Try to get as double first
          value = prefs.getDouble('flutter.$key');
          if (value == null) {
            // Try as int and convert to double
            final intValue = prefs.getInt('flutter.$key');
            if (intValue != null) {
              value = intValue.toDouble();
              print('🔄 Converted $key from int ($intValue) to double ($value)');
            } else {
              // Try as string and parse
              final stringValue = prefs.getString('flutter.$key');
              if (stringValue != null) {
                final doubleValue = double.tryParse(stringValue);
                if (doubleValue != null) {
                  value = doubleValue;
                }
              }
            }
          }
        } else {
          value = prefs.getString('flutter.$key');
        }
        
        if (value != null) {
          config[key] = value;
          print('✅ $key: $value (${value.runtimeType})');
        } else {
          config[key] = defaultConfig[key];
          issues.add('$key: Missing value, using default ${defaultConfig[key]}');
          print('⚠️ $key: Missing value, using default ${defaultConfig[key]}');
        }
      } catch (e) {
        print('❌ Error reading $key: $e');
        config[key] = defaultConfig[key];
        issues.add('$key: Error reading value, using default ${defaultConfig[key]}');
      }
    }
    
    if (issues.isNotEmpty) {
      print('❌ Configuration issues found:');
      for (var issue in issues) {
        print('   - $issue');
      }
    } else {
      print('✅ All configuration values are valid');
    }
    
    print('📊 Current configuration:');
    config.forEach((key, value) {
      print('   $key: $value (${value.runtimeType})');
    });
    
    return config;
  }

  Future<bool> updateConfig(Map<String, dynamic> newConfig) async {
    await _saveConfig(newConfig);
    
    // Also update the main app's tracking parameters
    await _updateTrackingParameters(newConfig);
    
    // Send updated configuration to server
    final serverSuccess = await _sendConfigToServer(newConfig);
    
    return serverSuccess;
  }

  Future<String?> getLastServerUpdateTime() async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getString('flutter.config_last_server_update');
  }
  
  Future<void> _updateTrackingParameters(Map<String, dynamic> config) async {
    final prefs = await SharedPreferences.getInstance();
    
    // Update tracking parameters with proper type conversion
    if (config.containsKey('gpsTimer')) {
      await prefs.setInt('flutter.gpsTimer', int.parse(config['gpsTimer'].toString()));
    }
    if (config.containsKey('uploadTimer')) {
      await prefs.setInt('flutter.uploadTimer', int.parse(config['uploadTimer'].toString()));
    }
    if (config.containsKey('angleThreshold')) {
      await prefs.setDouble('flutter.angleThreshold', double.parse(config['angleThreshold'].toString()));
    }
    if (config.containsKey('overSpeedingThreshold')) {
      await prefs.setDouble('flutter.overSpeedingThreshold', double.parse(config['overSpeedingThreshold'].toString()));
    }
    if (config.containsKey('distanceThreshold')) {
      await prefs.setDouble('flutter.distanceThreshold', double.parse(config['distanceThreshold'].toString()));
    }
    if (config.containsKey('movingTimer')) {
      await prefs.setInt('flutter.movingTimer', int.parse(config['movingTimer'].toString()));
    }
    if (config.containsKey('stopTimer')) {
      await prefs.setInt('flutter.stopTimer', int.parse(config['stopTimer'].toString()));
    }
  }

  Future<bool> _sendConfigToServer(Map<String, dynamic> config) async {
    try {
      // Get IMEI from SharedPreferences
      final prefs = await SharedPreferences.getInstance();
      final imei = prefs.getString('imei') ?? prefs.getString('flutter.imei');
      
      if (imei == null || imei.isEmpty || imei == 'unknown') {
        print('❌ Cannot send config to server: IMEI not available');
        throw Exception('IMEI not available for server communication');
      }

      // Convert all values to strings as required by the API
      final Map<String, String> stringConfig = config.map(
        (key, value) => MapEntry(key, value.toString())
      );
      
      // Add timestamp for server tracking
      stringConfig['updatedAt'] = DateTime.now().toIso8601String();
      
      print('🔄 Sending updated config to server for IMEI: $imei');
      print('📤 Config data: $stringConfig');
      
      final response = await http.post(
        Uri.parse('$baseUrl/devices/$imei/config'),
        headers: {
          'Content-Type': 'application/json',
          'User-Agent': 'TrackingWorld-Mobile-App',
        },
        body: jsonEncode(stringConfig),
      ).timeout(const Duration(seconds: 30));

      if (response.statusCode == 200 || response.statusCode == 201) {
        print('✅ Configuration successfully sent to server for IMEI: $imei');
        print('📥 Server response: ${response.body}');
        
        // Store server response timestamp
        await prefs.setString('flutter.config_last_server_update', DateTime.now().toIso8601String());
      } else {
        print('❌ Failed to send configuration to server. Status code: ${response.statusCode}');
        print('📥 Server response: ${response.body}');
        print('📤 Sent data: ${jsonEncode(stringConfig)}');
        throw Exception('Server returned status code: ${response.statusCode}');
      }
          } catch (e) {
        print('❌ Error sending configuration to server: $e');
        // Don't rethrow the error to avoid breaking the local save
        // The configuration is still saved locally even if server communication fails
        return false;
      }
      return true;
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
        print('Failed to fetch default config. Status code:  [${response.statusCode}m');
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

  // NEW: Fix existing configuration data with incorrect types
  Future<void> fixConfigurationTypes() async {
    final prefs = await SharedPreferences.getInstance();
    print('🔧 === FIXING CONFIGURATION TYPES ===');
    
    for (var key in defaultConfig.keys) {
      try {
        final prefKey = 'flutter.$key';
        
        if (defaultConfig[key] is int) {
          // Check if stored as double and convert to int
          final doubleValue = prefs.getDouble(prefKey);
          if (doubleValue != null) {
            final intValue = doubleValue.toInt();
            await prefs.setInt(prefKey, intValue);
            await prefs.remove(prefKey); // Remove the double value
            print('🔧 Fixed $key: converted double ($doubleValue) to int ($intValue)');
          }
        } else if (defaultConfig[key] is double) {
          // Check if stored as int and convert to double
          final intValue = prefs.getInt(prefKey);
          if (intValue != null) {
            final doubleValue = intValue.toDouble();
            await prefs.setDouble(prefKey, doubleValue);
            await prefs.remove(prefKey); // Remove the int value
            print('🔧 Fixed $key: converted int ($intValue) to double ($doubleValue)');
          }
        }
      } catch (e) {
        print('❌ Error fixing $key: $e');
      }
    }
    
    print('✅ Configuration type fixing completed');
  }

  // NEW: Clear all configuration and reset to defaults
  Future<void> clearAndResetConfiguration() async {
    final prefs = await SharedPreferences.getInstance();
    print('🧹 === CLEARING AND RESETTING CONFIGURATION ===');
    
    // Clear all flutter.* configuration keys
    for (var key in defaultConfig.keys) {
      final prefKey = 'flutter.$key';
      await prefs.remove(prefKey);
      print('🧹 Cleared $prefKey');
    }
    
    // Save default configuration
    await _saveConfig(defaultConfig);
    print('✅ Configuration cleared and reset to defaults');
  }
} 