import 'package:sqflite/sqflite.dart';
import 'package:path/path.dart';
import 'dart:convert';
import 'dart:io';
import 'package:intl/intl.dart';
import 'log_upload_service.dart';
import 'firebase_service.dart';

class DatabaseHelper {
  static final DatabaseHelper _instance = DatabaseHelper._internal();
  static Database? _database;

  factory DatabaseHelper() => _instance;

  DatabaseHelper._internal();

  Future<Database> get database async {
    if (_database != null) return _database!;
    _database = await _initDatabase();
    
    // Ensure all tables exist after initialization
    await ensureTablesExist();
    
    return _database!;
  }

  Future<Database> _initDatabase() async {
    String path = join(await getDatabasesPath(), 'location_tracking.db');
    return await openDatabase(
      path,
      version: 5,
      onCreate: _onCreate,
      onUpgrade: _onUpgrade,
    );
  }

  Future<void> _onCreate(Database db, int version) async {
    await db.execute('''
      CREATE TABLE location_data(
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        latitude REAL NOT NULL,
        longitude REAL NOT NULL,
        accuracy REAL,
        altitude REAL,
        speed REAL,
        bearing REAL,
        imei TEXT,
        timestamp TEXT,
        deviceRDT TEXT,
        gmtSettings TEXT,
        igStatus INTEGER,
        localPrimaryId INTEGER,
        name TEXT,
        phoneNo TEXT,
        provider TEXT,
        reason TEXT,
        versionNo TEXT,
        sync_status INTEGER DEFAULT 0,
        createAt TEXT
      )
    ''');

    // Create indexes for faster queries
    await db.execute('''
      CREATE INDEX idx_sync_status ON location_data(sync_status)
    ''');
    
    await db.execute('''
      CREATE INDEX idx_createAt ON location_data(createAt)
    ''');

    await db.execute('''
      CREATE INDEX idx_timestamp ON location_data(timestamp)
    ''');

    // Create exception logs table
    await db.execute('''
      CREATE TABLE exception_logs(
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        main TEXT NOT NULL,
        details TEXT,
        created_at TEXT
      )
    ''');

    // Create sync statistics table
    await db.execute('''
      CREATE TABLE sync_stats(
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        total_records INTEGER,
        synced_records INTEGER,
        failed_records INTEGER,
        last_sync_time INTEGER,
        sync_duration INTEGER,
        created_at TEXT
      )
    ''');

    // Create ignition logs table
    await db.execute('''
      CREATE TABLE ignition_logs(
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        message TEXT NOT NULL,
        details TEXT,
        log_type TEXT DEFAULT 'info',
        timestamp TEXT
      )
    ''');

    print('Database tables created successfully');
  }

  Future<void> _onUpgrade(Database db, int oldVersion, int newVersion) async {
    print('Upgrading database from version $oldVersion to $newVersion');
    
    if (oldVersion < 2) {
      // Add indexes if they don't exist
      try {
        await db.execute('''
          CREATE INDEX IF NOT EXISTS idx_sync_status ON location_data(sync_status)
        ''');
        await db.execute('''
          CREATE INDEX IF NOT EXISTS idx_createAt ON location_data(createAt)
        ''');
        await db.execute('''
          CREATE INDEX IF NOT EXISTS idx_timestamp ON location_data(timestamp)
        ''');
        print('Created indexes for version 2');
      } catch (e) {
        print('Error creating indexes: $e');
      }
    }

    if (oldVersion < 3) {
      // Create exception logs table if it doesn't exist
      try {
              await db.execute('''
        CREATE TABLE IF NOT EXISTS exception_logs(
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          main TEXT NOT NULL,
          details TEXT,
          created_at TEXT
        )
      ''');
        print('Created exception_logs table for version 3');
      } catch (e) {
        print('Error creating exception_logs table: $e');
      }

      // Create sync stats table if it doesn't exist
      try {
              await db.execute('''
        CREATE TABLE IF NOT EXISTS sync_stats(
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          total_records INTEGER,
          synced_records INTEGER,
          failed_records INTEGER,
          last_sync_time INTEGER,
          sync_duration INTEGER,
          created_at TEXT
        )
      ''');
        print('Created sync_stats table for version 3');
      } catch (e) {
        print('Error creating sync_stats table: $e');
      }
    }

    if (oldVersion < 4) {
      // Migrate created_at to createAt (camelCase)
      try {
        // Check if created_at column exists
        final columns = await db.rawQuery("PRAGMA table_info(location_data)");
        final hasCreatedAt = columns.any((col) => col['name'] == 'created_at');
        
        if (hasCreatedAt) {
          // Add createAt column
          await db.execute('ALTER TABLE location_data ADD COLUMN createAt TEXT');
          
          // Copy data from created_at to createAt
          await db.execute('UPDATE location_data SET createAt = created_at');
          
          // Drop the old created_at column (SQLite doesn't support DROP COLUMN directly)
          // We'll create a new table with the correct schema
          await db.execute('''
            CREATE TABLE location_data_new(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              latitude REAL NOT NULL,
              longitude REAL NOT NULL,
              accuracy REAL,
              altitude REAL,
              speed REAL,
              bearing REAL,
              imei TEXT,
              timestamp TEXT,
              deviceRDT TEXT,
              gmtSettings TEXT,
              igStatus INTEGER,
              localPrimaryId INTEGER,
              name TEXT,
              phoneNo TEXT,
              provider TEXT,
              reason TEXT,
              versionNo TEXT,
              sync_status INTEGER DEFAULT 0,
              createAt TEXT
            )
          ''');
          
          // Copy all data to new table
          await db.execute('''
            INSERT INTO location_data_new 
            SELECT id, latitude, longitude, accuracy, altitude, speed, bearing, 
                   imei, timestamp, deviceRDT, gmtSettings, igStatus, localPrimaryId,
                   name, phoneNo, provider, reason, versionNo, sync_status, createAt
            FROM location_data
          ''');
          
          // Drop old table and rename new table
          await db.execute('DROP TABLE location_data');
          await db.execute('ALTER TABLE location_data_new RENAME TO location_data');
          
          print('Successfully migrated created_at to createAt for version 4');
        }
      } catch (e) {
        print('Error migrating created_at to createAt: $e');
      }
    }

    if (oldVersion < 5) {
      // Create ignition logs table if it doesn't exist
      try {
        await db.execute('''
          CREATE TABLE IF NOT EXISTS ignition_logs(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            message TEXT NOT NULL,
            details TEXT,
            log_type TEXT DEFAULT 'info',
            timestamp TEXT
          )
        ''');
        print('Created ignition_logs table for version 5');
      } catch (e) {
        print('Error creating ignition_logs table: $e');
      }
    }
  }

  Future<bool> _tableExists(String tableName) async {
    try {
      final db = await database;
      final result = await db.rawQuery(
        "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
        [tableName]
      );
      return result.isNotEmpty;
    } catch (e) {
      print('Error checking if table exists: $e');
      return false;
    }
  }

  Future<void> ensureTablesExist() async {
    try {
      final db = await database;
      
      // Create exception_logs table if it doesn't exist
      await db.execute('''
        CREATE TABLE IF NOT EXISTS exception_logs(
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          main TEXT NOT NULL,
          details TEXT,
          created_at TEXT
        )
      ''');
      
      // Create sync_stats table if it doesn't exist
      await db.execute('''
        CREATE TABLE IF NOT EXISTS sync_stats(
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          total_records INTEGER,
          synced_records INTEGER,
          failed_records INTEGER,
          last_sync_time INTEGER,
          sync_duration INTEGER,
          created_at TEXT
        )
      ''');
      
      // Create ignition_logs table if it doesn't exist
      await db.execute('''
        CREATE TABLE IF NOT EXISTS ignition_logs(
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          message TEXT NOT NULL,
          details TEXT,
          log_type TEXT DEFAULT 'info',
          timestamp TEXT
        )
      ''');
      
      // Create indexes if they don't exist
      await db.execute('''
        CREATE INDEX IF NOT EXISTS idx_sync_status ON location_data(sync_status)
      ''');
      await db.execute('''
        CREATE INDEX IF NOT EXISTS idx_createAt ON location_data(createAt)
      ''');
      await db.execute('''
        CREATE INDEX IF NOT EXISTS idx_timestamp ON location_data(timestamp)
      ''');
      
      print('All tables and indexes ensured to exist');
    } catch (e) {
      print('Error ensuring tables exist: $e');
    }
  }

  Future<int> insertLocationData(Map<String, dynamic> data) async {
    try {
      final db = await database;
      // Use createAt (camelCase) for database consistency
      data['createAt'] = DateFormat("dd/MM/yyyy HH:mm:ss.SSS").format(DateTime.now());
      
      // Insert the new record
      final id = await db.insert('location_data', data);
      
      // Maintain record limit after insertion
      await _maintainRecordLimit();
      
      return id;
    } catch (e) {
      print('Error inserting location data: $e');
      await insertExceptionLog(main: 'Insert Error', details: e.toString());
      rethrow;
    }
  }

  Future<void> _maintainRecordLimit() async {
    try {
      final db = await database;
      
      // Count total records
      final List<Map<String, dynamic>> countResult = await db.rawQuery(
        'SELECT COUNT(*) as count FROM location_data'
      );
      final totalRecords = countResult.first['count'] as int;
      
      if (totalRecords > 1000) {
        final recordsToDelete = totalRecords - 1000;
        
        // First, try to delete only synced records (keep unsynced data)
        final syncedDeleteCount = await db.delete(
          'location_data',
          where: 'sync_status = 1 AND id IN (SELECT id FROM location_data WHERE sync_status = 1 ORDER BY createAt ASC LIMIT ?)',
          whereArgs: [recordsToDelete],
        );
        
        // If we still need to delete more records, delete oldest regardless of sync status
        if (syncedDeleteCount < recordsToDelete) {
          final remainingToDelete = recordsToDelete - syncedDeleteCount;
          await db.delete(
            'location_data',
            where: 'id IN (SELECT id FROM location_data ORDER BY createAt ASC LIMIT ?)',
            whereArgs: [remainingToDelete],
          );
        }
        
        print('Maintained record limit: deleted $recordsToDelete old records (${syncedDeleteCount} synced, ${recordsToDelete - syncedDeleteCount} total)');
      }
    } catch (e) {
      print('Error maintaining record limit: $e');
      await insertExceptionLog(main: 'Maintenance Error', details: e.toString());
    }
  }

  Future<List<Map<String, dynamic>>> getUnsyncedData({int limit = 100}) async {
    try {
      final db = await database;
      return await db.query(
        'location_data',
        where: 'sync_status = ?',
        whereArgs: [0],
        orderBy: 'createAt ASC',
        limit: limit,
      );
    } catch (e) {
      print('Error getting unsynced data: $e');
      await insertExceptionLog(main: 'Query Error', details: e.toString());
      return [];
    }
  }

  Future<List<Map<String, dynamic>>> getAllLocationData({int? limit, int? offset}) async {
    try {
      final db = await database;
      return await db.query(
        'location_data',
        orderBy: 'createAt DESC',
        limit: limit,
        offset: offset,
      );
    } catch (e) {
      print('Error getting all location data: $e');
      await insertExceptionLog(main: 'Query All Error', details: e.toString());
      return [];
    }
  }

  Future<void> markAsSynced(List<int> ids) async {
    if (ids.isEmpty) return;
    
    try {
      final db = await database;
      await db.update(
        'location_data',
        {'sync_status': 1},
        where: 'id IN (${List.filled(ids.length, '?').join(',')})',
        whereArgs: ids,
      );
      print('Marked ${ids.length} records as synced');
    } catch (e) {
      print('Error marking as synced: $e');
      await insertExceptionLog(main: 'Update Error', details: e.toString());
    }
  }

  Future<int> getUnsyncedCount() async {
    try {
      final db = await database;
      return Sqflite.firstIntValue(
        await db.rawQuery('SELECT COUNT(*) FROM location_data WHERE sync_status = 0')
      ) ?? 0;
    } catch (e) {
      print('Error getting unsynced count: $e');
      await insertExceptionLog(main: 'Count Error', details: e.toString());
      return 0;
    }
  }

  Future<int> getTotalCount() async {
    try {
      final db = await database;
      return Sqflite.firstIntValue(
        await db.rawQuery('SELECT COUNT(*) FROM location_data')
      ) ?? 0;
    } catch (e) {
      print('Error getting total count: $e');
      return 0;
    }
  }

  Future<void> deleteSyncedData() async {
    try {
      final db = await database;
      final deletedCount = await db.delete(
        'location_data',
        where: 'sync_status = ?',
        whereArgs: [1],
      );
      print('Deleted $deletedCount synced records');
    } catch (e) {
      print('Error deleting synced data: $e');
      await insertExceptionLog(main: 'Delete Error', details: e.toString());
    }
  }

  Future<void> deleteOldSyncedData({int keepRecentCount = 100}) async {
    try {
      final db = await database;
      
      // Keep only the most recent synced records
      final deletedCount = await db.delete(
        'location_data',
        where: 'sync_status = 1 AND id NOT IN (SELECT id FROM location_data WHERE sync_status = 1 ORDER BY createAt DESC LIMIT ?)',
        whereArgs: [keepRecentCount],
      );
      
      print('Deleted $deletedCount old synced records, kept $keepRecentCount recent ones');
    } catch (e) {
      print('Error deleting old synced data: $e');
      await insertExceptionLog(main: 'Cleanup Error', details: e.toString());
    }
  }

  // Exception Logs Methods
  Future<void> insertExceptionLog({required String main, String details = ''}) async {
    try {
      if (await _tableExists('exception_logs')) {
        final db = await database;
        final timestamp = DateFormat("dd/MM/yyyy HH:mm:ss.SSS").format(DateTime.now());
        
        await db.insert('exception_logs', {
          'main': main,
          'details': details,
          'created_at': timestamp,
        });
        
        // Upload to endpoint immediately
        try {
          final logUploadService = LogUploadService();
          await logUploadService.uploadExceptionLog(
            main: main,
            details: details,
            timestamp: timestamp,
          );
        } catch (e) {
          print('⚠️ Failed to upload exception log to endpoint: $e');
        }
        
        // Send to Firebase
        try {
          final firebaseService = FirebaseService();
          await firebaseService.sendExceptionLog(
            main: main,
            details: details,
            timestamp: timestamp,
          );
        } catch (e) {
          print('⚠️ Failed to send exception log to Firebase: $e');
        }
      } else {
        print('Exception logs table does not exist, skipping log: $main');
      }
    } catch (e) {
      print('Error inserting exception log: $e');
    }
  }

  Future<List<Map<String, dynamic>>> getExceptionLogs({int limit = 100}) async {
    try {
      if (await _tableExists('exception_logs')) {
        final db = await database;
        return await db.query(
          'exception_logs',
          orderBy: 'created_at DESC',
          limit: limit,
        );
      } else {
        print('Exception logs table does not exist');
        return [];
      }
    } catch (e) {
      print('Error getting exception logs: $e');
      return [];
    }
  }

  Future<void> clearExceptionLogs() async {
    try {
      if (await _tableExists('exception_logs')) {
        final db = await database;
        await db.delete('exception_logs');
        print('Exception logs cleared');
      } else {
        print('Exception logs table does not exist');
      }
    } catch (e) {
      print('Error clearing exception logs: $e');
    }
  }

  // Ignition Logs Methods
  Future<void> insertIgnitionLog({
    required String message, 
    String details = '', 
    String logType = 'info'
  }) async {
    try {
      if (await _tableExists('ignition_logs')) {
        final db = await database;
        final timestamp = DateTime.now().toIso8601String();
        
        await db.insert('ignition_logs', {
          'message': message,
          'details': details,
          'log_type': logType,
          'timestamp': timestamp,
        });
        
        // Upload to endpoint immediately
        try {
          final logUploadService = LogUploadService();
          await logUploadService.uploadIgnitionLog(
            message: message,
            details: details,
            logType: logType,
            timestamp: timestamp,
          );
        } catch (e) {
          print('⚠️ Failed to upload ignition log to endpoint: $e');
        }
        
        // Send to Firebase
        try {
          final firebaseService = FirebaseService();
          await firebaseService.sendIgnitionLog(
            message: message,
            details: details,
            logType: logType,
            timestamp: timestamp,
          );
        } catch (e) {
          print('⚠️ Failed to send ignition log to Firebase: $e');
        }
      } else {
        print('Ignition logs table does not exist, skipping log: $message');
      }
    } catch (e) {
      print('Error inserting ignition log: $e');
    }
  }

  Future<List<Map<String, dynamic>>> getIgnitionLogs({int limit = 100}) async {
    try {
      if (await _tableExists('ignition_logs')) {
        final db = await database;
        return await db.query(
          'ignition_logs',
          orderBy: 'timestamp DESC',
          limit: limit,
        );
      } else {
        print('Ignition logs table does not exist');
        return [];
      }
    } catch (e) {
      print('Error getting ignition logs: $e');
      return [];
    }
  }

  Future<void> clearIgnitionLogs() async {
    try {
      if (await _tableExists('ignition_logs')) {
        final db = await database;
        await db.delete('ignition_logs');
        print('Ignition logs cleared');
      } else {
        print('Ignition logs table does not exist');
      }
    } catch (e) {
      print('Error clearing ignition logs: $e');
    }
  }

  // Utility Methods
  Future<bool> locationEntryExists(Map<String, dynamic> data) async {
    try {
      final db = await database;
      final result = await db.query(
        'location_data',
        where: 'latitude = ? AND longitude = ? AND timestamp = ? AND provider = ?',
        whereArgs: [
          data['latitude'],
          data['longitude'],
          data['timestamp'],
          data['provider'],
        ],
        limit: 1,
      );
      return result.isNotEmpty;
    } catch (e) {
      print('Error checking location entry existence: $e');
      await insertExceptionLog(main: 'Duplicate Check Error', details: e.toString());
      return false;
    }
  }

  Future<Map<String, dynamic>> getDatabaseStats() async {
    try {
      final db = await database;
      
      final totalCountResult = await db.rawQuery('SELECT COUNT(*) as count FROM location_data');
      final unsyncedCountResult = await db.rawQuery('SELECT COUNT(*) as count FROM location_data WHERE sync_status = 0');
      final syncedCountResult = await db.rawQuery('SELECT COUNT(*) as count FROM location_data WHERE sync_status = 1');
      
      final oldestResult = await db.rawQuery('SELECT MIN(createAt) as oldest FROM location_data');
      final newestResult = await db.rawQuery('SELECT MAX(createAt) as newest FROM location_data');
      
      // Check if exception_logs table exists before querying
      int exceptionLogsCount = 0;
      if (await _tableExists('exception_logs')) {
        final exceptionCountResult = await db.rawQuery('SELECT COUNT(*) as count FROM exception_logs');
        exceptionLogsCount = exceptionCountResult.first['count'] as int;
      }
      
      // Check if sync_stats table exists before querying
      int syncStatsCount = 0;
      if (await _tableExists('sync_stats')) {
        final syncStatsCountResult = await db.rawQuery('SELECT COUNT(*) as count FROM sync_stats');
        syncStatsCount = syncStatsCountResult.first['count'] as int;
      }
      
      // Get database size
      final dbPath = join(await getDatabasesPath(), 'location_tracking.db');
      int dbSize = 0;
      try {
        final file = File(dbPath);
        final stat = await file.stat();
        dbSize = stat.size;
      } catch (e) {
        print('Error getting database size: $e');
      }
      
      return {
        'totalRecords': totalCountResult.first['count'] as int,
        'unsyncedRecords': unsyncedCountResult.first['count'] as int,
        'syncedRecords': syncedCountResult.first['count'] as int,
        'oldestRecord': oldestResult.first['oldest'],
        'newestRecord': newestResult.first['newest'],
        'exceptionLogsCount': exceptionLogsCount,
        'syncStatsCount': syncStatsCount,
        'databaseSizeBytes': dbSize,
        'databaseSizeMB': (dbSize / (1024 * 1024)).toStringAsFixed(2),
      };
    } catch (e) {
      print('Error getting database stats: $e');
      return {
        'totalRecords': 0,
        'unsyncedRecords': 0,
        'syncedRecords': 0,
        'oldestRecord': null,
        'newestRecord': null,
        'exceptionLogsCount': 0,
        'syncStatsCount': 0,
        'databaseSizeBytes': 0,
        'databaseSizeMB': '0.00',
      };
    }
  }

  Future<void> cleanupDatabase() async {
    try {
      final db = await database;
      
      // Delete old exception logs (keep only recent 50)
      if (await _tableExists('exception_logs')) {
        await db.delete(
          'exception_logs',
          where: 'id NOT IN (SELECT id FROM exception_logs ORDER BY created_at DESC LIMIT 50)',
        );
      }
      
      // Vacuum database to reclaim space
      await db.execute('VACUUM');
      
      print('Database cleanup completed');
    } catch (e) {
      print('Error during database cleanup: $e');
    }
  }

  Future<void> forceMaintainRecordLimit() async {
    await _maintainRecordLimit();
  }

  Future<void> resetDatabase() async {
    try {
      print('Resetting database...');
      
      // Close existing connection
      if (_database != null) {
        await _database!.close();
        _database = null;
      }
      
      // Delete the database file
      final path = join(await getDatabasesPath(), 'location_tracking.db');
      await databaseFactory.deleteDatabase(path);
      
      print('Database deleted, will be recreated on next access');
      
      // Reinitialize database
      _database = await _initDatabase();
      
      print('Database reset completed successfully');
    } catch (e) {
      print('Error resetting database: $e');
    }
  }

  Future<void> close() async {
    if (_database != null) {
      await _database!.close();
      _database = null;
      print('Database closed');
    }
  }
}