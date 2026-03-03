import 'dart:io';

import 'package:drift/drift.dart';
import 'package:drift/native.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';

import 'sync_queue_table.dart';

part 'app_database.g.dart';

/// AppDatabase — Drift SQLite database.
///
/// Single source of truth for all local data.
/// Tables added here as features are implemented.
@DriftDatabase(tables: [SyncQueue])
class AppDatabase extends _$AppDatabase {
  AppDatabase() : super(_openConnection());

  @override
  int get schemaVersion => 1;
}

LazyDatabase _openConnection() {
  return LazyDatabase(() async {
    final dbFolder = await getApplicationDocumentsDirectory();
    final file = File(p.join(dbFolder.path, 'keevo.sqlite'));
    return NativeDatabase.createInBackground(file);
  });
}
