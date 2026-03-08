import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:keevo/features/audit/domain/model/audit_entry_dto.dart';
import 'package:keevo/features/audit/domain/repository/audit_repository.dart';
import 'package:keevo/features/audit/presentation/provider/audit_provider.dart';
import 'package:keevo/features/audit/presentation/widget/stock_history_widget.dart';

// ── Mock repository ────────────────────────────────────────────────────────

class _FakeAuditRepository implements AuditRepository {
  final List<AuditEntryDto> entries;
  _FakeAuditRepository(this.entries);

  @override
  Future<List<AuditEntryDto>> getAuditHistory({
    String? entityType,
    String? entityId,
  }) async => entries;
}

// ── Helper ─────────────────────────────────────────────────────────────────

Widget _buildWidget(
  List<AuditEntryDto> entries, {
  String? entityType,
  String? entityId,
}) {
  return ProviderScope(
    overrides: [
      auditRepositoryProvider.overrideWith(
        (_) => _FakeAuditRepository(entries),
      ),
    ],
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
  valueAfter: '{"qty":10}',
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
    testWidgets('renders list of AuditEntryDto items — shows action and actor',
        (tester) async {
      await tester.pumpWidget(
        _buildWidget([_stockEntry, _loginEntry]),
      );
      await tester.pumpAndSettle();

      // French action labels should be shown
      expect(find.text('Ajustement stock'), findsOneWidget);
      expect(find.text('Connexion'), findsOneWidget);

      // Actor: last 8 chars of userId
      expect(find.textContaining('ABCDEF01'), findsWidgets);
    });

    testWidgets('empty list → shows "Aucun historique disponible"', (tester) async {
      await tester.pumpWidget(_buildWidget([]));
      await tester.pumpAndSettle();

      expect(find.text('Aucun historique disponible'), findsOneWidget);
      expect(find.byType(CircularProgressIndicator), findsNothing);
    });

    testWidgets('loading state → shows CircularProgressIndicator', (tester) async {
      // Use a Completer to create a Future that never completes,
      // forcing the provider to stay in loading state.
      final completer = Completer<List<AuditEntryDto>>();

      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            auditHistoryProvider(entityType: null, entityId: null).overrideWith(
              (_) => completer.future,
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

      expect(find.textContaining('{"qty":5}'), findsOneWidget);
      expect(find.textContaining('{"qty":10}'), findsOneWidget);
    });
  });
}
