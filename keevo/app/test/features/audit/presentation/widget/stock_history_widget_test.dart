import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:keevo/core/di/providers.dart';
import 'package:keevo/features/audit/domain/model/audit_entry_dto.dart';
import 'package:keevo/features/audit/domain/model/audit_filter.dart';
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
  }) async =>
      AuditPageResult(entries: entries, hasMore: false);

  @override
  Future<AuditSlice> getAuditSlice(AuditFilter filter,
          {String? cursor, int size = 20}) =>
      throw UnimplementedError();

  @override
  Future<AuditEntryDto> getAuditEntry(String id) => throw UnimplementedError();
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
  }) =>
      _future;

  @override
  Future<AuditSlice> getAuditSlice(AuditFilter filter,
          {String? cursor, int size = 20}) =>
      throw UnimplementedError();

  @override
  Future<AuditEntryDto> getAuditEntry(String id) => throw UnimplementedError();
}

// ── Helper ─────────────────────────────────────────────────────────────────

late SharedPreferences _prefs;

List<Override> _baseOverrides(List<AuditEntryDto> entries) => [
      sharedPreferencesProvider.overrideWithValue(_prefs),
      currentUserIdProvider
          .overrideWith((_) => Future.value('user-uuid-ABCDEF01')),
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
  valueAfter:
      '{"movementType":"ADJUSTMENT","quantityChange":5,"quantityAfter":10}',
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

/// Counts calls; page 1 fails [failPage1Times] times before succeeding.
class _ScriptedAuditRepository implements AuditRepository {
  final List<AuditEntryDto> page0;
  final bool page0HasMore;
  int failPage1Times;
  final calls = <int>[];
  _ScriptedAuditRepository(this.page0,
      {this.page0HasMore = false, this.failPage1Times = 0});

  @override
  Future<AuditPageResult> getAuditHistoryPage({
    required int page,
    required int size,
    String? entityType,
    String? entityId,
  }) async {
    calls.add(page);
    if (page == 0) {
      return AuditPageResult(entries: page0, hasMore: page0HasMore);
    }
    if (failPage1Times > 0) {
      failPage1Times--;
      throw Exception('network');
    }
    return const AuditPageResult(entries: [], hasMore: false);
  }

  @override
  Future<AuditSlice> getAuditSlice(AuditFilter filter,
          {String? cursor, int size = 20}) =>
      throw UnimplementedError();

  @override
  Future<AuditEntryDto> getAuditEntry(String id) => throw UnimplementedError();
}

Widget _buildWithRepo(AuditRepository repo) => ProviderScope(
      overrides: [
        sharedPreferencesProvider.overrideWithValue(_prefs),
        currentUserIdProvider.overrideWith((_) => Future.value('me')),
        auditRepositoryProvider.overrideWith((_) => repo),
      ],
      child: const MaterialApp(home: Scaffold(body: StockHistoryWidget())),
    );

AuditEntryDto _entry(int i, {String userId = 'user-uuid-ABCDEF01'}) =>
    AuditEntryDto(
      id: 'e-$i',
      entityType: 'Product',
      entityId: 'p-$i',
      action: 'PRODUCT_UPDATED',
      userId: userId,
      occurredAt: DateTime.utc(2026, 3, 1, 10, i % 60),
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

    testWidgets('empty list → shows "Aucun historique disponible"',
        (tester) async {
      await tester.pumpWidget(_buildWidget([]));
      await tester.pumpAndSettle();

      expect(find.text('Aucun historique disponible'), findsOneWidget);
      expect(find.byType(CircularProgressIndicator), findsNothing);
    });

    testWidgets('loading state → shows CircularProgressIndicator',
        (tester) async {
      // Override repository with one that never completes,
      // keeping the notifier in AsyncLoading.
      final completer = Completer<AuditPageResult>();

      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            sharedPreferencesProvider.overrideWithValue(_prefs),
            currentUserIdProvider
                .overrideWith((_) => Future.value('test-user')),
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

    testWidgets('v1s-16-20: price override in XAF, stock entry readable',
        (tester) async {
      await tester.pumpWidget(_buildWidget([
        AuditEntryDto(
          id: 'e-price',
          entityType: 'PriceOverride',
          entityId: 'p-1',
          action: 'PRICE_OVERRIDDEN',
          valueBefore: '{"cataloguePrice":1000}',
          valueAfter: '{"appliedPrice":1500,"productName":"Savon"}',
          userId: 'other-user',
          actorName: 'Awa',
          occurredAt: DateTime.utc(2026, 3, 1, 11),
        ),
        AuditEntryDto(
          id: 'e-entry',
          entityType: 'StockMovement',
          entityId: 'p-1',
          action: 'STOCK_ADJUSTED',
          valueAfter: '{"movementType":"STOCK_ENTRY","quantityChange":4,'
              '"quantityAfter":9}',
          userId: 'other-user',
          occurredAt: DateTime.utc(2026, 3, 1, 10),
        ),
      ]));
      await tester.pumpAndSettle();

      expect(find.text('Prix modifié : 1 000 XAF → 1 500 XAF'), findsOneWidget);
      expect(find.text('Stock réceptionné : +4 unité(s) · total : 9'),
          findsOneWidget);
      expect(find.textContaining('STOCK_ENTRY'), findsNothing);
      expect(find.textContaining('FCFA'), findsNothing);
    });

    testWidgets('v1s-16-23: transfer line names the other store',
        (tester) async {
      AuditEntryDto t(String id, String type, String? metadata, int change) =>
          AuditEntryDto(
            id: id,
            entityType: 'StockMovement',
            entityId: 'p-1',
            action: 'STOCK_ADJUSTED',
            valueAfter: '{"movementType":"$type","quantityChange":$change,'
                '"quantityAfter":7}',
            metadata: metadata,
            userId: 'other-user',
            occurredAt: DateTime.utc(2026, 3, 1, 10),
          );
      await tester.pumpWidget(_buildWidget([
        t('e-out', 'TRANSFER_OUT', '{"destinationStoreName":"Boutique B"}', -3),
        t('e-in', 'TRANSFER_IN', '{"sourceStoreName":"Boutique A"}', 3),
        t('e-old', 'TRANSFER_OUT', null, -2),
      ]));
      await tester.pumpAndSettle();

      expect(
          find.text(
              'Stock transféré (sortie) vers Boutique B : -3 unité(s) · total : 7'),
          findsOneWidget);
      expect(
          find.text(
              'Stock transféré (entrée) depuis Boutique A : +3 unité(s) · total : 7'),
          findsOneWidget);
      expect(find.text('Stock transféré (sortie) : -2 unité(s) · total : 7'),
          findsOneWidget);
    });

    testWidgets('shows valueBefore → valueAfter when present', (tester) async {
      await tester.pumpWidget(_buildWidget([_stockEntry]));
      await tester.pumpAndSettle();

      // Widget decodes JSON → French description
      expect(find.textContaining('ajusté'), findsOneWidget);
      expect(find.textContaining('+5'), findsOneWidget);
      expect(find.textContaining('total : 10'), findsOneWidget);
    });

    testWidgets('short actor id without phone does not throw', (tester) async {
      await tester.pumpWidget(_buildWithRepo(
          _ScriptedAuditRepository([_entry(1, userId: 'system')])));
      await tester.pumpAndSettle();

      expect(tester.takeException(), isNull);
      expect(find.text('system'), findsNothing); // never an id (v1s-16-20)
      expect(find.text('Un utilisateur'), findsOneWidget);
    });

    testWidgets('empty state supports pull-to-refresh', (tester) async {
      final repo = _ScriptedAuditRepository([]);
      await tester.pumpWidget(_buildWithRepo(repo));
      await tester.pumpAndSettle();
      expect(repo.calls, [0]);

      await tester.fling(
          find.text('Aucun historique disponible'), const Offset(0, 400), 1000);
      await tester.pumpAndSettle();

      expect(repo.calls, [0, 0]);
    });

    testWidgets('loadMore failure does not block later pagination',
        (tester) async {
      final repo = _ScriptedAuditRepository(
        List.generate(20, _entry),
        page0HasMore: true,
        failPage1Times: 1,
      );
      await tester.pumpWidget(_buildWithRepo(repo));
      await tester.pumpAndSettle();

      // Footer spinner keeps animating while hasMore → no pumpAndSettle.
      await tester.drag(find.byType(ListView), const Offset(0, -3000));
      await tester.pump(const Duration(milliseconds: 100));
      await tester.pump(const Duration(milliseconds: 100));
      expect(repo.calls, contains(1));
      expect(find.textContaining('Impossible de charger'), findsOneWidget);

      await tester.drag(find.byType(ListView), const Offset(0, -300));
      await tester.pump(const Duration(milliseconds: 100));
      // Retried after the failure instead of staying stuck.
      expect(repo.calls.where((p) => p == 1).length, greaterThanOrEqualTo(2));
    });
  });
}
