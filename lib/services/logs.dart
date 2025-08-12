import 'dart:io';
import 'package:path_provider/path_provider.dart';

Future<void> appendLog(String message) async {
  try {
    // Get the app's documents directory
    final dir = await getApplicationDocumentsDirectory();
    final file = File('${dir.path}/app_logs.txt');

    // Create the file if it doesn't exist
    if (!await file.exists()) {
      await file.create(recursive: true);
    }

    // Append the log message with a timestamp
    final timestamp = DateTime.now().toIso8601String();
    await file.writeAsString('[$timestamp] $message\n', mode: FileMode.append);
  } catch (e) {
    // If something goes wrong, print to console
    print('Error writing log: $e');
  }
}
