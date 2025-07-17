import 'dart:convert';
import 'package:http/http.dart' as http;

class ApiService {
  static const String baseUrl = 'http://twca.trackingworld.com.pk:3000/api';

  Future<bool> sendLocationData(Map<String, dynamic> locationData) async {
    try {
      // Remove timestamp from the data
      final dataToSend = Map<String, dynamic>.from(locationData);
      dataToSend.remove('timestamp');

      final response = await http.post(
        Uri.parse('$baseUrl/location'),
        headers: {
          'Content-Type': 'application/json',
        },
        body: jsonEncode(dataToSend),
      );

      if (response.statusCode == 200 || response.statusCode == 201) {
        print('Location data sent successfully');
        return true;
      } else {
        print('Failed to send location data. Status code: ${response.statusCode}');
        print('Response body: ${response.body}');
        return false;
      }
    } catch (e) {
      print('Error sending location data: $e');
      return false;
    }
  }
} 