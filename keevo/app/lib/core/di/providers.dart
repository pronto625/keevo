import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../storage/app_database.dart';

/// Global AppDatabase provider — singleton via Riverpod.
final appDatabaseProvider = Provider<AppDatabase>((ref) {
  final db = AppDatabase();
  ref.onDispose(db.close);
  return db;
});
