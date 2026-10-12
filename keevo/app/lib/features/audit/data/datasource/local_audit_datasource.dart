import 'package:drift/drift.dart';

import '../../../../core/storage/app_database.dart';
import '../../../../core/storage/sql_like.dart';
import '../../domain/model/audit_entry_dto.dart';
import '../../domain/model/audit_filter.dart';

/// Prefix of the offline offset cursor (`local:<offset>`).
const kLocalAuditCursorPrefix = 'local:';

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
    final entries =
        (hasMore ? rows.sublist(0, size) : rows).map(_toDto).toList();

    return AuditPageResult(entries: entries, hasMore: hasMore);
  }

  /// v1s-18-7 — enriched projection: store name from `stores`, actor name
  /// from `employees` (by user), and the employee name as label fallback for
  /// Employee targets (same rule as the backend read-time join).
  /// Displayed object label: stored label, else the target employee's name.
  /// Shared by the projection and the `q` filter so search matches display.
  static const _labelExpr = '''
COALESCE(a.entity_label,
         (SELECT NULLIF(TRIM(te.first_name || ' ' || te.last_name), '')
            FROM employees te
           WHERE a.entity_type = 'Employee' AND te.id = a.entity_id
           LIMIT 1))''';

  static const _enrichedSelect = '''
SELECT a.id, a.user_id, a.entity_type, a.entity_id, a.action,
       a.value_before, a.value_after, a.occurred_at,
       a.store_id, a.device_id, a.source, a.metadata,
       $_labelExpr AS entity_label,
       (SELECT st.name FROM stores st WHERE st.id = a.store_id LIMIT 1)
         AS store_name,
       (SELECT NULLIF(TRIM(e.first_name || ' ' || e.last_name), '')
          FROM employees e WHERE e.user_id = a.user_id LIMIT 1) AS actor_name
FROM audit_entries a''';

  /// v1s-18-7 — same filters as the REST list, offset paging encoded as
  /// `local:<offset>` in [cursor].
  Future<AuditSlice> getAuditSlice(
    AuditFilter filter, {
    String? cursor,
    int size = 20,
  }) async {
    final offset = _offsetOf(cursor);
    final where = <String>[];
    final vars = <Variable>[];
    if (filter.from != null) {
      where.add('a.occurred_at >= ?');
      vars.add(Variable<String>(_sqlDate(filter.from!)));
    }
    if (filter.to != null) {
      where.add('a.occurred_at <= ?');
      vars.add(Variable<String>(_sqlDate(filter.to!)));
    }
    if (filter.storeId != null) {
      where.add('a.store_id = ?');
      vars.add(Variable<String>(filter.storeId!));
    }
    if (filter.userId != null) {
      where.add('a.user_id = ?');
      vars.add(Variable<String>(filter.userId!));
    }
    if (filter.action != null) {
      where.add('a.action = ?');
      vars.add(Variable<String>(filter.action!));
    }
    if (filter.entityType != null) {
      where.add('a.entity_type = ?');
      vars.add(Variable<String>(filter.entityType!));
    }
    final q = filter.normalizedQuery;
    if (q != null) {
      // SQLite LIKE is case-insensitive for ASCII.
      where.add("$_labelExpr LIKE ? ESCAPE '$likeEscapeChar'");
      vars.add(Variable<String>(likeContains(q)));
    }
    final whereStr = where.isEmpty ? '' : ' WHERE ${where.join(' AND ')}';

    final rows = await _db.customSelect(
      '$_enrichedSelect$whereStr '
      'ORDER BY a.occurred_at DESC, a.id DESC '
      'LIMIT ${size + 1} OFFSET $offset',
      variables: vars,
      readsFrom: {_db.auditEntries, _db.stores, _db.employees},
    ).get();

    final hasMore = rows.length > size;
    final entries =
        (hasMore ? rows.sublist(0, size) : rows).map(_toEnrichedDto).toList();
    return AuditSlice(
      entries: entries,
      nextCursor: hasMore ? '$kLocalAuditCursorPrefix${offset + size}' : null,
      isOffline: true,
    );
  }

  /// v1s-18-7 — one local row with resolved names, `null` when unknown.
  Future<AuditEntryDto?> getById(String id) async {
    final rows = await _db.customSelect(
      '$_enrichedSelect WHERE a.id = ? LIMIT 1',
      variables: [Variable<String>(id)],
      readsFrom: {_db.auditEntries, _db.stores, _db.employees},
    ).get();
    return rows.isEmpty ? null : _toEnrichedDto(rows.first);
  }

  static int _offsetOf(String? cursor) {
    if (cursor == null || !cursor.startsWith(kLocalAuditCursorPrefix)) {
      return 0;
    }
    final n =
        int.tryParse(cursor.substring(kLocalAuditCursorPrefix.length)) ?? 0;
    return n < 0 ? 0 : n;
  }

  /// Bound in the same text format the sync writes (`_toLocalIso`).
  String _sqlDate(DateTime value) =>
      _db.typeMapping.mapToSqlVariable(value.toLocal()) as String;

  AuditEntryDto _toEnrichedDto(QueryRow row) {
    return _toDto(row).copyWith(
      storeId: row.readNullable<String>('store_id'),
      storeName: row.readNullable<String>('store_name'),
      entityLabel: row.readNullable<String>('entity_label'),
      deviceId: row.readNullable<String>('device_id'),
      source: row.readNullable<String>('source'),
      metadata: row.readNullable<String>('metadata'),
      actorName: row.readNullable<String>('actor_name'),
    );
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
