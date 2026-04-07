import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:keevo/core/di/providers.dart';
import 'package:keevo/features/audit/domain/model/audit_entry_dto.dart';
import 'package:keevo/features/audit/domain/repository/audit_repository.dart';
import 'package:keevo/features/audit/presentation/provider/audit_provider.dart';
import 'package:keevo/features/audit/presentation/widget/stock_history_widget.dart';

// ── Mock repository ────────────────────────────────────────────────────────

class _FakeAuditRepository implements AuditRepository {
  final List<AuditEntryDto> entries;
  _FakeAuditRepository(this.entries);

  @override
  Future<AuditPageResult> getAuditHistoryPage({
    required int page,
    required int size,
    String? entityType,
    String? entityId,
  }) async => AuditPageResult(entries: entries, hasMore: false);
}

class _NeverCompleteAuditRepository implements AuditRepository {
  final Future<AuditPageResult> _future;
  _NeverCompleteAuditRepository(this._future);

  @override
  Future<AuditPageResult> getAuditHistoryPage({
    required int page,
    required int size,
    String? entityType,
    String? entityId,
  }) => _future;
}

// ── Helper ─────────────────────────────────────────────────────────────────

late SharedPreferences _prefs;

List<Override> _baseOverrides(List<AuditEntryDto> entries) => [
      sharedPreferencesProvider.overrideWithValue(_prefs),
      currentUserIdProvider.overrideWith((_) => Future.value('user-uuid-ABCDEF01')),
      auditRepositoryProvider.overrideWith(
        (_) => _FakeAuditRepository(entries),
      ),
    ];

Widget _buildWidget(
  List<AuditEntryDto> entries, {
  String? entityType,
  String? entityId,
}) {
  return ProviderScope(
    overrides: _baseOverrides(entries),
    child: MaterialApp(
      home: Scaffold(
        body: StockHistoryWidget(
          entityType: entityType,
          entityId: entityId,
        ),
      ),
    ),
  );
}

// ── Test entries ───────────────────────────────────────────────────────────

final _stockEntry = AuditEntryDto(
  id: 'entry-uuid-001',
  entityType: 'Product',
  entityId: 'product-uuid-001',
  action: 'STOCK_ADJUSTED',
  valueBefore: '{"qty":5}',
  valueAfter: '{"movementType":"ADJUSTMENT","quantityChange":5,"quantityAfter":10}',
  userId: 'user-uuid-ABCDEF01',
  occurredAt: DateTime.utc(2026, 3, 1, 10, 0, 0),
);

final _loginEntry = AuditEntryDto(
  id: 'entry-uuid-002',
  entityType: 'User',
  entityId: 'user-uuid-001',
  action: 'USER_AUTHENTICATED',
  valueBefore: null,
  valueAfter: '{"role":"OWNER"}',
  userId: 'user-uuid-ABCDEF01',
  occurredAt: DateTime.utc(2026, 3, 1, 9, 0, 0),
);

void main() {
  group('StockHistoryWidget', () {
    setUp(() async {
      SharedPreferences.setMockInitialValues({});
      _prefs = await SharedPreferences.getInstance();
    });

    testWidgets('renders list of AuditEntryDto items — shows action and actor',
        (tester) async {
      await tester.pumpWidget(
        _buildWidget([_stockEntry, _loginEntry]),
      );
      await tester.pumpAndSettle();

      // French action labels should be shown
      expect(find.text('Mouvement de stock'), findsOneWidget);
      expect(find.text('Connexion'), findsOneWidget);

      // Actor: current user → "Vous"
      expect(find.textContaining('Vous'), findsWidgets);
    });

    testWidgets('empty list → shows "Aucun historique disponible"', (tester) async {
      await tester.pumpWidget(_buildWidget([]));
      await tester.pumpAndSettle();

      expect(find.text('Aucun historique disponible'), findsOneWidget);
      expect(find.byType(CircularProgressIndicator), findsNothing);
    });

    testWidgets('loading state → shows CircularProgressIndicator', (tester) async {
      // Override repository with one that never completes,
      // keeping the notifier in AsyncLoading.
      final completer = Completer<AuditPageResult>();

      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            sharedPreferencesProvider.overrideWithValue(_prefs),
            currentUserIdProvider.overrideWith((_) => Future.value('test-user')),
            auditRepositoryProvider.overrideWith(
              (_) => _NeverCompleteAuditRepository(completer.future),
            ),
          ],
          child: const MaterialApp(
            home: Scaffold(
              body: StockHistoryWidget(),
            ),
          ),
        ),
      );

      // First pump — provider is in loading state (completer not yet resolved)
      await tester.pump();
      expect(find.byType(CircularProgressIndicator), findsOneWidget);
    });

    testWidgets('shows valueBefore → valueAfter when present', (tester) async {
      await tester.pumpWidget(_buildWidget([_stockEntry]));
      await tester.pumpAndSettle();

      // Widget decodes JSON → French description
      expect(find.textContaining('ajusté'), findsOneWidget);
      expect(find.textContaining('+5'), findsOneWidget);
      expect(find.textContaining('total : 10'), findsOneWidget);
    });
  });
}
