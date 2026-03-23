import 'package:drift/drift.dart';

import '../../../../core/storage/app_database.dart';
import '../../domain/model/audit_entry_dto.dart';

/// LocalAuditDataSource — reads audit entries from the local Drift (SQLite) DB.
///
/// Data is populated by pull sync ([RestSyncService._upsertAuditEntries]).
/// This datasource enables offline audit history viewing.
///
/// Uses [customSelect] instead of typed [select] because _upsertAuditEntries
/// writes occurred_at as text (backend timestamp format). Raw reads handle
/// both text and integer date values safely.
class LocalAuditDataSource {
  final AppDatabase _db;

  const LocalAuditDataSource(this._db);

  /// Returns a paginated slice of local audit entries, sorted by occurredAt DESC.
  Future<AuditPageResult> getAuditHistoryPage({
    required int page,
    required int size,
    String? entityType,
    String? entityId,
  }) async {
    final whereClauses = <String>[];
    final vars = <Variable>[];

    if (entityType != null) {
      whereClauses.add('entity_type = ?');
      vars.add(Variable.withString(entityType));
    }
    if (entityId != null) {
      whereClauses.add('entity_id = ?');
      vars.add(Variable.withString(entityId));
    }

    final whereStr =
        whereClauses.isEmpty ? '' : 'WHERE ${whereClauses.join(' AND ')}';

    final rows = await _db.customSelect(
      'SELECT id, user_id, entity_type, entity_id, action, '
      'value_before, value_after, occurred_at '
      'FROM audit_entries $whereStr '
      'ORDER BY occurred_at DESC '
      'LIMIT ${size + 1} OFFSET ${page * size}',
      variables: vars,
      readsFrom: {_db.auditEntries},
    ).get();

    final hasMore = rows.length > size;
    final entries = (hasMore ? rows.sublist(0, size) : rows)
        .map(_toDto)
        .toList();

    return AuditPageResult(entries: entries, hasMore: hasMore);
  }

  AuditEntryDto _toDto(QueryRow row) {
    return AuditEntryDto(
      id: row.read<String>('id'),
      entityType: row.read<String>('entity_type'),
      entityId: row.read<String>('entity_id'),
      action: row.read<String>('action'),
      valueBefore: row.readNullable<String>('value_before'),
      valueAfter: row.readNullable<String>('value_after'),
      userId: row.read<String>('user_id'),
      occurredAt: _parseOccurredAt(row.read<String>('occurred_at')),
    );
  }

  /// Parses occurred_at — stored as text by _upsertAuditEntries.
  /// Handles both JDBC format ("2023-06-15 12:30:00.0") and ISO-8601.
  DateTime _parseOccurredAt(String raw) {
    // Try ISO-8601 first (e.g. "2023-06-15T12:30:00.000Z")
    final parsed = DateTime.tryParse(raw);
    if (parsed != null) return parsed;
    // JDBC format: "2023-06-15 12:30:00.0" → replace space with T
    final iso = raw.replaceFirst(' ', 'T');
    return DateTime.tryParse(iso) ?? DateTime.now();
  }
}
