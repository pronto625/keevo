import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:keevo/features/audit/domain/model/audit_filter.dart';
import 'package:keevo/features/audit/presentation/provider/audit_log_provider.dart';
import 'package:keevo/features/audit/presentation/provider/audit_provider.dart';

import '../page/audit_test_helpers.dart';

/// v1s-18-7 — a loadMore in flight during a refresh must not append to (or
/// overwrite) the refreshed list.
void main() {
  test('loadMore result is discarded after a refresh', () async {
    final repo = FakeAuditRepository();
    final pendingMore = Completer<AuditSlice>();
    var builds = 0;
    repo.onSlice = (_, cursor) {
      if (cursor != null) return pendingMore.future;
      builds++;
      return Future.value(builds == 1
          ? AuditSlice(entries: [auditEntry('old')], nextCursor: 'c1')
          : AuditSlice(entries: [auditEntry('fresh')], nextCursor: 'c2'));
    };
    final container = ProviderContainer(
        overrides: [auditRepositoryProvider.overrideWithValue(repo)]);
    addTearDown(container.dispose);
    container.listen(auditLogNotifierProvider, (_, __) {});

    await container.read(auditLogNotifierProvider.future);
    final notifier = container.read(auditLogNotifierProvider.notifier);
    final more = notifier.loadMore();
    expect(notifier.isLoadingMore, isTrue);

    container.invalidate(auditLogNotifierProvider);
    final refreshed = await container.read(auditLogNotifierProvider.future);
    expect(refreshed.entries.map((e) => e.id), ['fresh']);
    expect(container.read(auditLogNotifierProvider.notifier).isLoadingMore,
        isFalse);

    pendingMore.complete(AuditSlice(entries: [auditEntry('stale-more')]));
    await more;

    final state = container.read(auditLogNotifierProvider).requireValue;
    expect(state.entries.map((e) => e.id), ['fresh']);
    expect(state.nextCursor, 'c2');
  });

  test('loadMore appends without duplicates when nothing changed', () async {
    final repo = FakeAuditRepository();
    repo.onSlice = (_, cursor) async => cursor == null
        ? AuditSlice(entries: [auditEntry('a')], nextCursor: 'c1')
        : AuditSlice(entries: [auditEntry('a'), auditEntry('b')]);
    final container = ProviderContainer(
        overrides: [auditRepositoryProvider.overrideWithValue(repo)]);
    addTearDown(container.dispose);
    container.listen(auditLogNotifierProvider, (_, __) {});

    await container.read(auditLogNotifierProvider.future);
    await container.read(auditLogNotifierProvider.notifier).loadMore();

    final state = container.read(auditLogNotifierProvider).requireValue;
    expect(state.entries.map((e) => e.id), ['a', 'b']);
    expect(state.hasMore, isFalse);
  });
}
