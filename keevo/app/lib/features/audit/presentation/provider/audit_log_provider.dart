import 'package:drift/drift.dart' show OrderingTerm;
import 'package:riverpod_annotation/riverpod_annotation.dart';

import '../../../../core/di/providers.dart';
import '../../domain/model/audit_entry_dto.dart';
import '../../domain/model/audit_filter.dart';
import 'audit_provider.dart';

part 'audit_log_provider.g.dart';

const kAuditLogPageSize = 20;

/// Current filters of the « Historique des actions » screen (v1s-18-7).
@riverpod
class AuditLogFilterNotifier extends _$AuditLogFilterNotifier {
  @override
  AuditFilter build() => AuditFilter.empty;

  void set(AuditFilter filter) {
    if (filter != state) state = filter;
  }

  void clear() => state = AuditFilter.empty;
}

/// Loaded entries of the history list.
class AuditLogState {
  final List<AuditEntryDto> entries;
  final String? nextCursor;
  final bool isOffline;

  const AuditLogState({
    required this.entries,
    this.nextCursor,
    this.isOffline = false,
  });

  bool get hasMore => nextCursor != null;
}

/// History list: first slice on build (re-run whenever the filters change),
/// [loadMore] appends the next one with the cursor, never duplicating an id.
@riverpod
class AuditLogNotifier extends _$AuditLogNotifier {
  bool _loadingMore = false;

  /// Bumped on every (re)build: a loadMore started before a refresh must not
  /// append to the refreshed list.
  int _generation = 0;

  bool get isLoadingMore => _loadingMore;

  @override
  Future<AuditLogState> build() async {
    _generation++;
    _loadingMore = false;
    final filter = ref.watch(auditLogFilterNotifierProvider);
    final slice = await ref
        .read(auditRepositoryProvider)
        .getAuditSlice(filter, size: kAuditLogPageSize);
    return AuditLogState(
      entries: slice.entries,
      nextCursor: slice.nextCursor,
      isOffline: slice.isOffline,
    );
  }

  /// Appends the next slice. Errors propagate to the caller (SnackBar) and
  /// leave the loaded list untouched.
  Future<void> loadMore() async {
    final current = state.valueOrNull;
    if (current == null || !current.hasMore || _loadingMore) return;
    final generation = _generation;
    _loadingMore = true;
    final filter = ref.read(auditLogFilterNotifierProvider);
    try {
      final slice = await ref.read(auditRepositoryProvider).getAuditSlice(
            filter,
            cursor: current.nextCursor,
            size: kAuditLogPageSize,
          );
      // A refresh / filter change happened while loading: this slice
      // belongs to a list that no longer exists.
      if (generation != _generation ||
          !identical(state.valueOrNull, current) ||
          ref.read(auditLogFilterNotifierProvider) != filter) {
        return;
      }
      final seen = current.entries.map((e) => e.id).toSet();
      state = AsyncData(AuditLogState(
        entries: [
          ...current.entries,
          ...slice.entries.where((e) => seen.add(e.id)),
        ],
        nextCursor: slice.nextCursor,
        isOffline: current.isOffline || slice.isOffline,
      ));
    } finally {
      // A rebuild already reset the flag (and may own a newer loadMore).
      if (generation == _generation) _loadingMore = false;
    }
  }
}

/// Fresh detail of one entry (remote, local fallback).
@riverpod
Future<AuditEntryDto> auditEntryDetail(AuditEntryDetailRef ref, String id) =>
    ref.watch(auditRepositoryProvider).getAuditEntry(id);

/// A choice in a filter sheet.
class AuditFilterOption {
  final String id;
  final String label;

  const AuditFilterOption(this.id, this.label);
}

/// Stores known on this device (« Boutique » filter).
@riverpod
Future<List<AuditFilterOption>> auditStoreOptions(
    AuditStoreOptionsRef ref) async {
  final db = ref.watch(appDatabaseProvider);
  final stores = await (db.select(db.stores)
        ..orderBy([(t) => OrderingTerm.asc(t.name)]))
      .get();
  return [for (final s in stores) AuditFilterOption(s.id, s.name)];
}

/// Employees known on this device, keyed by user id (« Employé » filter).
@riverpod
Future<List<AuditFilterOption>> auditEmployeeOptions(
    AuditEmployeeOptionsRef ref) async {
  final db = ref.watch(appDatabaseProvider);
  final employees = await (db.select(db.employees)
        ..orderBy([(t) => OrderingTerm.asc(t.firstName)]))
      .get();
  final seen = <String>{};
  return [
    for (final e in employees)
      if (seen.add(e.userId))
        AuditFilterOption(e.userId, '${e.firstName} ${e.lastName}'.trim()),
  ];
}
