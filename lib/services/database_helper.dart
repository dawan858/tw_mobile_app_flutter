import 'package:sqflite/sqflite.dart';
import 'package:path/path.dart';
import 'dart:convert';

class DatabaseHelper {
  static final DatabaseHelper _instance = DatabaseHelper._internal();
  static Database? _database;

  factory DatabaseHelper() => _instance;

  DatabaseHelper._internal();

  Future<Database> get database async {
    if (_database != null) return _database!;
    _database = await _initDatabase();
    return _database!;
  }

  Future<Database> _initDatabase() async {
    String path = join(await getDatabasesPath(), 'location_tracking.db');
    return await openDatabase(
      path,
      version: 1,
      onCreate: _onCreate,
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
        heading REAL,
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
        created_at INTEGER
      )
    ''');
  }

  Future<int> insertLocationData(Map<String, dynamic> data) async {
    final db = await database;
    data['created_at'] = DateTime.now().millisecondsSinceEpoch;
    return await db.insert('location_data', data);
  }

  Future<List<Map<String, dynamic>>> getUnsyncedData() async {
    final db = await database;
    return await db.query(
      'location_data',
      where: 'sync_status = ?',
      whereArgs: [0],
      orderBy: 'created_at ASC',
      limit: 1000,
    );
  }

  Future<void> markAsSynced(List<int> ids) async {
    final db = await database;
    await db.update(
      'location_data',
      {'sync_status': 1},
      where: 'id IN (${List.filled(ids.length, '?').join(',')})',
      whereArgs: ids,
    );
  }

  Future<int> getUnsyncedCount() async {
    final db = await database;
    return Sqflite.firstIntValue(
      await db.rawQuery('SELECT COUNT(*) FROM location_data WHERE sync_status = 0')
    ) ?? 0;
  }

  Future<void> deleteSyncedData() async {
    final db = await database;
    await db.delete(
      'location_data',
      where: 'sync_status = ?',
      whereArgs: [1],
    );
  }
} 